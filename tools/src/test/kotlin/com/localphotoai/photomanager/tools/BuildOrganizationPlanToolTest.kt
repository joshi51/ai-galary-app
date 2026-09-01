package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.OrganizationPlanRepository
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every member throws — used to prove validation rejects a bad `category` before the use case
 * is ever actually invoked. Unlike `throw NotImplementedError(...)` passed as a constructor
 * argument (which evaluates eagerly, before `execute()` even runs), these only throw if one of
 * their methods is actually called during the test. */
private class UnreachablePhotoRepository : PhotoRepository {
    override fun observePhotos(): Flow<List<Photo>> = error("not reached — validation fails first")
    override fun observeIndexingProgress(): Flow<IndexingProgress> = error("not reached")
    override suspend fun fetchGeneration(): Long? = error("not reached")
    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> = error("not reached")
    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> = error("not reached")
    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> = error("not reached")
    override suspend fun upsert(photos: List<PhotoMetadata>) = error("not reached")
    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) = error("not reached")
    override suspend fun updateIndexingProgress(progress: IndexingProgress) = error("not reached")
    override suspend fun saveGeneration(generation: Long) = error("not reached")
    override suspend fun lastSavedGeneration(): Long? = error("not reached")
    override suspend fun fetchById(mediaStoreId: Long): Photo? = error("not reached")
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = error("not reached")
}

private class UnreachablePhotoGroupRepository : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = error("not reached")
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) = error("not reached")
    override suspend fun markHashFailed(photoId: Long, error: String) = error("not reached")
    override fun observeHashProgress(): Flow<IndexingProgress> = error("not reached")
    override suspend fun updateHashProgress(progress: IndexingProgress) = error("not reached")
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = error("not reached")
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) = error("not reached")
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = error("not reached")
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) = error("not reached")
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = error("not reached")
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> = error("not reached")
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) = error("not reached")
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) = error("not reached")
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = error("not reached")
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) = error("not reached")
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = error("not reached")
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = error("not reached")
    override suspend fun applyVisuallySimilarGroupingResult(embeddings: List<PhotoEmbeddingForSimilarity>, assignments: List<ClusterAssignmentDto>, newClusterCount: Int) = error("not reached")
    override fun observeGroupingProgress(): Flow<IndexingProgress> = error("not reached")
    override suspend fun updateGroupingProgress(progress: IndexingProgress) = error("not reached")
    override suspend fun removePhotoFromAllGroups(photoId: Long) = error("not reached")
}

private class UnreachableOrganizationPlanRepository : OrganizationPlanRepository {
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan = error("not reached")
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = error("not reached")
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = error("not reached")
    override suspend fun updateOperation(operation: OrganizationOperation) = error("not reached")
}

class BuildOrganizationPlanToolTest {

    private fun unreachableTool() = BuildOrganizationPlanTool(
        BuildOrganizationPlanUseCase(
            UnreachablePhotoRepository(),
            UnreachablePhotoGroupRepository(),
            UnreachableOrganizationPlanRepository(),
        ),
    )

    @Test
    fun `rejects a missing category`() = runBlocking {
        val result = unreachableTool().execute(ToolCall(tool = ToolName.BUILD_ORGANIZATION_PLAN, category = null))
        assertTrue(result is ToolOutcome.Error)
    }

    @Test
    fun `rejects an unknown category`() = runBlocking {
        val result = unreachableTool().execute(ToolCall(tool = ToolName.BUILD_ORGANIZATION_PLAN, category = "vacation_photos"))
        assertTrue(result is ToolOutcome.Error)
    }
}
