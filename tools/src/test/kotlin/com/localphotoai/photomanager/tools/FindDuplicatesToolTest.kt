package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
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
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = null, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null,
)

private class DuplicatesToolFakePhotoGroupRepository(
    private val duplicateGroups: List<DuplicateGroupSummary> = emptyList(),
    private val similarGroups: List<SimilarGroupSummary> = emptyList(),
) : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = emptyList()
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {}
    override suspend fun markHashFailed(photoId: Long, error: String) {}
    override fun observeHashProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateHashProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = emptyList()
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {}
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = flowOf(duplicateGroups)
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) {}
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = flowOf(similarGroups)
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> = emptyList()
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {}
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {}
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = emptyList()
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = emptyList()
    override suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    ) {}
    override fun observeGroupingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateGroupingProgress(progress: IndexingProgress) {}
    override suspend fun removePhotoFromAllGroups(photoId: Long) {}
}

private class DuplicatesToolFakePhotoRepository(private val photos: List<Photo>) : PhotoRepository {
    override fun observePhotos(): Flow<List<Photo>> = emptyFlow()
    override fun observeIndexingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun fetchGeneration(): Long? = null
    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> = emptyList()
    override suspend fun upsert(photos: List<PhotoMetadata>) {}
    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) {}
    override suspend fun updateIndexingProgress(progress: IndexingProgress) {}
    override suspend fun saveGeneration(generation: Long) {}
    override suspend fun lastSavedGeneration(): Long? = null
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photos.firstOrNull { it.mediaStoreId == mediaStoreId }
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> =
        photos.filter { it.mediaStoreId in mediaStoreIds }
}

class FindDuplicatesToolTest {

    @Test
    fun `reports no duplicates when there are none`() = runBlocking {
        val tool = FindDuplicatesTool(DuplicatesToolFakePhotoGroupRepository(), DuplicatesToolFakePhotoRepository(emptyList()))

        val result = tool.execute(ToolCall(tool = ToolName.FIND_DUPLICATES))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(0, (result as ToolOutcome.Photos).photos.size)
    }

    @Test
    fun `resolves duplicate group photo ids to photos`() = runBlocking {
        val groups = listOf(DuplicateGroupSummary(groupId = 1L, photoIds = listOf(1L, 2L), totalSizeBytes = 200L))
        val photos = listOf(testPhoto(1L), testPhoto(2L))
        val tool = FindDuplicatesTool(DuplicatesToolFakePhotoGroupRepository(duplicateGroups = groups), DuplicatesToolFakePhotoRepository(photos))

        val result = tool.execute(ToolCall(tool = ToolName.FIND_DUPLICATES))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(2, (result as ToolOutcome.Photos).photos.size)
    }
}
