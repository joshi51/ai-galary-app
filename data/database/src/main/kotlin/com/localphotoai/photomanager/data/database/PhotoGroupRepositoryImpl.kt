package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.dao.SimilarityEmbeddingDao
import com.localphotoai.photomanager.data.database.entity.SimilarGroupEntity
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind as EntityKind
import com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity
import com.localphotoai.photomanager.data.database.entity.SimilarityEmbeddingEntity
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.similarity.ClusterAssignmentDto
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.ExistingSimilarCentroid
import com.localphotoai.photomanager.domain.similarity.PhotoEmbeddingForSimilarity
import com.localphotoai.photomanager.domain.similarity.PhotoForHashing
import com.localphotoai.photomanager.domain.similarity.PhotoForSimilarityEmbedding
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.PhotoHashInput
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class PhotoGroupRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    private val duplicateGroupDao: DuplicateGroupDao,
    private val similarGroupDao: SimilarGroupDao,
    private val similarityEmbeddingDao: SimilarityEmbeddingDao,
) : PhotoGroupRepository {

    // In-memory progress state — mirrors the durable-status-table pattern used elsewhere in this
    // project (IndexingStatus, FaceDetectionStatus, ...) would be the fuller version; Phase 7
    // keeps it in-memory since the grouping stages complete quickly enough that cross-process
    // durability isn't as load-bearing as multi-minute face detection was.
    private val hashProgress = MutableStateFlow(IndexingProgress.IDLE)
    private val similarityEmbeddingProgress = MutableStateFlow(IndexingProgress.IDLE)
    private val groupingProgress = MutableStateFlow(IndexingProgress.IDLE)

    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> =
        photoDao.getPhotosNeedingHash().map { it.toDomain() }

    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {
        photoDao.updateHashes(photoId, contentHash, perceptualHash)
    }

    override suspend fun markHashFailed(photoId: Long, error: String) {
        photoDao.markHashFailed(photoId, error)
    }

    override fun observeHashProgress(): Flow<IndexingProgress> = hashProgress

    override suspend fun updateHashProgress(progress: IndexingProgress) {
        hashProgress.value = progress
    }

    override suspend fun fetchAllHashes(): List<PhotoHashInput> = photoDao.getAllHashes().map { it.toDomain() }

    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
        duplicateGroupDao.replaceAllGroups(photoIdGroupsByHash)
    }

    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> =
        duplicateGroupDao.observeGroups().map { rows -> rows.map { it.toDomain() } }

    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) {
        val byKind = groups.groupBy { it.first }
        for (kind in SimilarGroupKindResult.entries) {
            val groupsOfKind = (byKind[kind] ?: emptyList())
                .mapIndexed { index, (_, photoIds) -> index to photoIds.map { it to 1f } }
                .toMap()
            similarGroupDao.replaceGroupsOfKind(kind.toEntity(), groupsOfKind)
        }
    }

    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> =
        similarGroupDao.observeGroupsByKind(kind.toEntity()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> =
        photoDao.getPhotosNeedingSimilarityEmbedding(currentModelVersion).map { it.toDomain() }

    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {
        similarityEmbeddingDao.upsertEmbedding(SimilarityEmbeddingEntity(photoId, modelVersion, floatArrayToBytes(vector)))
        photoDao.markSimilarityEmbeddingComplete(photoId, modelVersion)
    }

    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {
        photoDao.markSimilarityEmbeddingFailed(photoId, modelVersion, error)
    }

    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = similarityEmbeddingProgress

    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) {
        similarityEmbeddingProgress.value = progress
    }

    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> {
        // Only photos not yet in a VISUALLY_SIMILAR group — mirrors Phase 5's
        // fetchFacesNeedingClustering "not yet assigned" filter. Without this, every re-run
        // (periodic reconciliation, a fresh app launch) would reprocess every embedding ever
        // generated through the clusterer again, silently reassigning photos to brand-new
        // groups and leaving the old ones orphaned at zero members — a real bug caught during
        // Phase 7's on-device verification, not a hypothetical.
        val alreadyGrouped = similarGroupDao.getPhotoIdsInGroupsOfKind(EntityKind.VISUALLY_SIMILAR).toSet()
        return similarityEmbeddingDao.getAllEmbeddings()
            .filter { it.photoId !in alreadyGrouped }
            .map { row -> PhotoEmbeddingForSimilarity(row.photoId, bytesToFloatArray(row.vector)) }
    }

    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> {
        // Existing VISUALLY_SIMILAR groups' centroids are recomputed from their current members'
        // stored vectors each run (rather than persisting a separate centroid column) — simpler,
        // and cheap at this phase's expected group sizes.
        val rows = similarGroupDao.getGroupsByKind(EntityKind.VISUALLY_SIMILAR)
        return rows.mapNotNull { row ->
            val photoIds = row.photoIdsCsv.split(",").map { it.toLong() }
            var sum: FloatArray? = null
            for (id in photoIds) {
                val vectorBytes = similarityEmbeddingDao.getVector(id) ?: continue
                val vector = bytesToFloatArray(vectorBytes)
                val currentSum = sum
                sum = if (currentSum == null) vector.copyOf() else FloatArray(currentSum.size) { i -> currentSum[i] + vector[i] }
            }
            sum?.let { ExistingSimilarCentroid(row.groupId, it) }
        }
    }

    override suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    ) {
        val newClusterMembers = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()
        for (assignment in assignments) {
            val groupId = assignment.groupId
            val newClusterIndex = assignment.newClusterIndex
            if (groupId != null) {
                similarGroupDao.upsertMember(
                    SimilarGroupMemberEntity(assignment.photoId, groupId, assignment.confidence),
                )
            } else if (newClusterIndex != null) {
                newClusterMembers.getOrPut(newClusterIndex) { mutableListOf() }
                    .add(assignment.photoId to assignment.confidence)
            }
        }
        for ((_, members) in newClusterMembers) {
            if (members.size < 2) continue
            val avg = members.map { it.second }.average().toFloat()
            val groupId = similarGroupDao.insertGroup(SimilarGroupEntity(kind = EntityKind.VISUALLY_SIMILAR, avgSimilarity = avg))
            for ((photoId, similarity) in members) {
                similarGroupDao.upsertMember(SimilarGroupMemberEntity(photoId, groupId, similarity))
            }
        }
    }

    override fun observeGroupingProgress(): Flow<IndexingProgress> = groupingProgress

    override suspend fun updateGroupingProgress(progress: IndexingProgress) {
        groupingProgress.value = progress
    }

    override suspend fun removePhotoFromAllGroups(photoId: Long) {
        duplicateGroupDao.removeMember(photoId)
        duplicateGroupDao.deleteUndersizedGroups()
        similarGroupDao.removeMember(photoId)
        for (kind in EntityKind.entries) similarGroupDao.deleteUndersizedGroupsOfKind(kind)
    }
}
