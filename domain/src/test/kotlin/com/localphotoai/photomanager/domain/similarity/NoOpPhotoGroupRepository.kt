package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A `PhotoGroupRepository` fake base that errors on any unimplemented method — each test
 *  overrides only what it exercises, keeping fakes short and explicit about what they use. */
internal abstract class NoOpPhotoGroupRepository : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = error("not stubbed")
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long): Unit = error("not stubbed")
    override suspend fun markHashFailed(photoId: Long, error: String): Unit = error("not stubbed")
    override fun observeHashProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateHashProgress(progress: IndexingProgress): Unit = error("not stubbed")
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = error("not stubbed")
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>): Unit = error("not stubbed")
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = error("not stubbed")
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>): Unit =
        error("not stubbed")
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = error("not stubbed")
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> =
        error("not stubbed")
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray): Unit =
        error("not stubbed")
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String): Unit =
        error("not stubbed")
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress): Unit = error("not stubbed")
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = error("not stubbed")
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = error("not stubbed")
    override suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    ): Unit = error("not stubbed")
    override fun observeGroupingProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateGroupingProgress(progress: IndexingProgress): Unit = error("not stubbed")
    override suspend fun removePhotoFromAllGroups(photoId: Long): Unit = error("not stubbed")
}
