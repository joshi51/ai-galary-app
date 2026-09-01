package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupVisuallySimilarPhotosUseCaseTest {

    @Test
    fun `two similar embeddings end up in the same new cluster`() = runBlocking {
        val embeddings = listOf(
            PhotoEmbeddingForSimilarity(1L, floatArrayOf(1f, 0f)),
            PhotoEmbeddingForSimilarity(2L, floatArrayOf(0.99f, 0.01f)),
        )
        var appliedAssignments: List<ClusterAssignmentDto>? = null
        val repository = object : NoOpPhotoGroupRepository() {
            override suspend fun fetchAllSimilarityEmbeddings() = embeddings
            override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = emptyList()
            override suspend fun applyVisuallySimilarGroupingResult(
                embeddings: List<PhotoEmbeddingForSimilarity>,
                assignments: List<ClusterAssignmentDto>,
                newClusterCount: Int,
            ) {
                appliedAssignments = assignments
            }
        }

        val result = GroupVisuallySimilarPhotosUseCase(repository, NoOpLogger())()

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).value)
        val indices = appliedAssignments!!.map { it.newClusterIndex }
        assertEquals(indices[0], indices[1])
    }

    @Test
    fun `no embeddings produces zero new clusters`() = runBlocking {
        val repository = object : NoOpPhotoGroupRepository() {
            override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = emptyList()
            override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = emptyList()
            override suspend fun applyVisuallySimilarGroupingResult(
                embeddings: List<PhotoEmbeddingForSimilarity>,
                assignments: List<ClusterAssignmentDto>,
                newClusterCount: Int,
            ) = Unit
        }

        val result = GroupVisuallySimilarPhotosUseCase(repository, NoOpLogger())()

        assertEquals(0, (result as AppResult.Success).value)
    }
}
