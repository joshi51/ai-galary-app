package com.localphotoai.photomanager.domain.person

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.face.l2Normalize
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePersonRepository(
    private val pending: List<FaceEmbeddingForClustering>,
    private val existing: List<ExistingClusterCentroid> = emptyList(),
) : PersonRepository {
    var appliedFaces: List<FaceEmbeddingForClustering>? = null
    var appliedResult: ClusteringResult? = null
    val progressUpdates = mutableListOf<IndexingProgress>()
    private val progressFlow = MutableStateFlow(IndexingProgress.IDLE)

    override fun observePeopleWithStats(): Flow<List<PersonWithStats>> =
        throw UnsupportedOperationException("not used in this test")

    override fun observeMembers(personId: Long): Flow<List<PersonMember>> =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun fetchFacesNeedingClustering(): List<FaceEmbeddingForClustering> = pending

    override suspend fun fetchExistingClusters(): List<ExistingClusterCentroid> = existing

    override suspend fun applyClusteringResult(faces: List<FaceEmbeddingForClustering>, result: ClusteringResult) {
        appliedFaces = faces
        appliedResult = result
    }

    override suspend fun namePerson(personId: Long, name: String?) = throw UnsupportedOperationException()
    override suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long) = throw UnsupportedOperationException()
    override suspend fun splitFaceIntoNewPerson(faceId: Long): Long = throw UnsupportedOperationException()
    override suspend fun markFaceIncorrect(faceId: Long) = throw UnsupportedOperationException()

    override fun observeClusteringProgress(): Flow<IndexingProgress> = progressFlow

    override suspend fun updateClusteringProgress(progress: IndexingProgress) {
        progressUpdates += progress
        progressFlow.value = progress
    }
}

class ClusterFacesUseCaseTest {

    @Test
    fun `no pending faces completes immediately without applying any result`() = runBlocking {
        val repository = FakePersonRepository(pending = emptyList())
        val useCase = ClusterFacesUseCase(repository, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(null, repository.appliedResult)
    }

    @Test
    fun `pending faces are clustered and the result applied via the repository`() = runBlocking {
        val face = FaceEmbeddingForClustering(1L, l2Normalize(floatArrayOf(1f, 0f)))
        val repository = FakePersonRepository(pending = listOf(face))
        val useCase = ClusterFacesUseCase(repository, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertEquals(1, repository.appliedResult?.outcomes?.size)
        assertEquals(1, (result as AppResult.Success).value.itemsProcessed)
    }

    @Test
    fun `progress reports RUNNING before COMPLETE`() = runBlocking {
        val faces = (1L..10L).map { FaceEmbeddingForClustering(it, l2Normalize(floatArrayOf(it.toFloat(), 1f))) }
        val repository = FakePersonRepository(pending = faces)
        val useCase = ClusterFacesUseCase(repository, NoOpLogger())

        useCase()

        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        assertEquals(IndexingState.COMPLETE, repository.progressUpdates.last().state)
    }
}
