package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import kotlinx.coroutines.flow.Flow

/**
 * Access to hashing, duplicate/near-duplicate/burst/similar grouping, and their pipeline state.
 * Implemented in `:data:database` (Room only). Mirrors the shape of Phase 3-5's repositories
 * (fetch-pending / save / mark-failed / observe-progress) for each of Phase 7's stages.
 */
interface PhotoGroupRepository {

    // Hashing stage
    suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing>
    suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long)
    suspend fun markHashFailed(photoId: Long, error: String)
    fun observeHashProgress(): Flow<IndexingProgress>
    suspend fun updateHashProgress(progress: IndexingProgress)

    // Exact-duplicate grouping (pure grouping over stored hashes)
    suspend fun fetchAllHashes(): List<PhotoHashInput>
    suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>)
    fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>>

    // Near-duplicate/burst grouping (pure grouping over stored hashes + dates)
    suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>)
    fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>>

    // Similarity-embedding stage
    suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding>
    suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray)
    suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String)
    fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress>
    suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress)

    // Visually-similar grouping (embedding-based nearest-centroid)
    suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity>
    suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid>
    suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    )
    fun observeGroupingProgress(): Flow<IndexingProgress>
    suspend fun updateGroupingProgress(progress: IndexingProgress)

    /** Called after a confirmed deletion so group membership doesn't reference a gone photo. */
    suspend fun removePhotoFromAllGroups(photoId: Long)
}
