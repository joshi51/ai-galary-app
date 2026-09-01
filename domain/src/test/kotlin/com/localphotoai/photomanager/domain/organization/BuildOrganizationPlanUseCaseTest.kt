package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppResult
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, filename: String) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1_000L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

private class BuildPlanFakePhotoRepository(private val photos: List<Photo>) : PhotoRepository {
    override fun observePhotos(): Flow<List<Photo>> = flowOf(photos)
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
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = photos.filter { it.mediaStoreId in mediaStoreIds }
}

private class BuildPlanFakePhotoGroupRepository : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = emptyList()
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {}
    override suspend fun markHashFailed(photoId: Long, error: String) {}
    override fun observeHashProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateHashProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = emptyList()
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {}
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = flowOf(emptyList())
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) {}
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = flowOf(emptyList())
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> = emptyList()
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {}
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {}
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = emptyList()
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = emptyList()
    override suspend fun applyVisuallySimilarGroupingResult(embeddings: List<PhotoEmbeddingForSimilarity>, assignments: List<ClusterAssignmentDto>, newClusterCount: Int) {}
    override fun observeGroupingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateGroupingProgress(progress: IndexingProgress) {}
    override suspend fun removePhotoFromAllGroups(photoId: Long) {}
}

private class BuildPlanFakeOrganizationPlanRepository : OrganizationPlanRepository {
    var savedPlan: OrganizationPlan? = null
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan {
        val saved = plan.copy(id = 1L)
        savedPlan = saved
        return saved
    }
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = savedPlan
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = flowOf(savedPlan)
    override suspend fun updateOperation(operation: OrganizationOperation) {}
}

class BuildOrganizationPlanUseCaseTest {

    @Test
    fun `builds and persists a plan for a matching category`() = runBlocking {
        val photoRepository = BuildPlanFakePhotoRepository(listOf(testPhoto(1, "Screenshot_1.png")))
        val planRepository = BuildPlanFakeOrganizationPlanRepository()
        val useCase = BuildOrganizationPlanUseCase(photoRepository, BuildPlanFakePhotoGroupRepository(), planRepository)

        val result = useCase("Organize my screenshots", OrganizationCategory.SCREENSHOTS, null, null)

        assertTrue(result is AppResult.Success)
        val plan = (result as AppResult.Success).value
        assertEquals(1L, plan.id)
        assertTrue(plan.operations.isNotEmpty())
        assertEquals(plan, planRepository.savedPlan)
    }

    @Test
    fun `returns a validation failure when nothing matches`() = runBlocking {
        val photoRepository = BuildPlanFakePhotoRepository(listOf(testPhoto(1, "IMG_1.jpg")))
        val useCase = BuildOrganizationPlanUseCase(photoRepository, BuildPlanFakePhotoGroupRepository(), BuildPlanFakeOrganizationPlanRepository())

        val result = useCase("Organize my screenshots", OrganizationCategory.SCREENSHOTS, null, null)

        assertTrue(result is AppResult.Failure)
    }
}
