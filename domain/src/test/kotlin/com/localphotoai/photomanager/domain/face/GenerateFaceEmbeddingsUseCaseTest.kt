package com.localphotoai.photomanager.domain.face

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun face(id: Long) = FaceForEmbedding(
    faceId = id,
    photoUri = "content://media/external/images/media/$id",
    photoWidthPx = 100,
    photoHeightPx = 100,
    orientationDegrees = 0,
    left = 0.1f,
    top = 0.1f,
    right = 0.5f,
    bottom = 0.5f,
)

private class FakeFaceEmbeddingRepository(private val pending: List<FaceForEmbedding>) : FaceEmbeddingRepository {
    val savedEmbeddings = LinkedHashMap<Long, FaceEmbedding>()
    val failedFaces = LinkedHashMap<Long, String>()
    val progressUpdates = mutableListOf<IndexingProgress>()
    private val progressFlow = MutableStateFlow(IndexingProgress.IDLE)

    override suspend fun fetchFacesNeedingEmbedding(currentModelVersion: Int): List<FaceForEmbedding> = pending

    override suspend fun saveEmbedding(embedding: FaceEmbedding) {
        savedEmbeddings[embedding.faceId] = embedding
    }

    override suspend fun markEmbeddingFailed(faceId: Long, modelVersion: Int, error: String) {
        failedFaces[faceId] = error
    }

    override fun observeEmbeddingProgress(): Flow<IndexingProgress> = progressFlow

    override suspend fun updateEmbeddingProgress(progress: IndexingProgress) {
        progressUpdates += progress
        progressFlow.value = progress
    }
}

private class FakeEmbeddingGenerator(
    override val modelVersion: Int = 1,
    private val ready: Boolean = true,
    private val vectorsByUri: Map<String, FloatArray> = emptyMap(),
    private val failingUris: Set<String> = emptySet(),
) : EmbeddingGenerator {
    override suspend fun isReady(): Boolean = ready

    override suspend fun generateEmbedding(
        photoUri: String,
        photoWidthPx: Int,
        photoHeightPx: Int,
        orientationDegrees: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): FloatArray {
        if (photoUri in failingUris) error("simulated bad crop")
        return vectorsByUri[photoUri] ?: floatArrayOf(1f, 0f)
    }
}

class GenerateFaceEmbeddingsUseCaseTest {

    @Test
    fun `model not ready is a no-op that does not touch any face rows`() = runBlocking {
        val repository = FakeFaceEmbeddingRepository(pending = listOf(face(1L)))
        val useCase = GenerateFaceEmbeddingsUseCase(repository, FakeEmbeddingGenerator(ready = false), NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertTrue(repository.savedEmbeddings.isEmpty())
        assertTrue(repository.failedFaces.isEmpty())
        assertEquals(IndexingState.IDLE, (result as AppResult.Success).value.state)
        assertTrue(result.value.lastError?.contains("not downloaded") == true)
    }

    @Test
    fun `no pending faces completes immediately`() = runBlocking {
        val repository = FakeFaceEmbeddingRepository(pending = emptyList())
        val useCase = GenerateFaceEmbeddingsUseCase(repository, FakeEmbeddingGenerator(), NoOpLogger())

        val result = useCase()

        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(0, result.value.itemsTotal)
    }

    @Test
    fun `generated embeddings are L2-normalized and saved with the model version`() = runBlocking {
        val target = face(1L)
        val repository = FakeFaceEmbeddingRepository(pending = listOf(target))
        val generator = FakeEmbeddingGenerator(
            modelVersion = 7,
            vectorsByUri = mapOf(target.photoUri to floatArrayOf(3f, 4f)),
        )
        val useCase = GenerateFaceEmbeddingsUseCase(repository, generator, NoOpLogger())

        useCase()

        val saved = repository.savedEmbeddings.getValue(1L)
        assertEquals(7, saved.modelVersion)
        assertEquals(0.6f, saved.vector[0], 1e-5f)
        assertEquals(0.8f, saved.vector[1], 1e-5f)
    }

    @Test
    fun `a bad crop is flagged with an error but does not abort the batch`() = runBlocking {
        val bad = face(1L)
        val good = face(2L)
        val repository = FakeFaceEmbeddingRepository(pending = listOf(bad, good))
        val generator = FakeEmbeddingGenerator(
            vectorsByUri = mapOf(good.photoUri to floatArrayOf(1f, 0f)),
            failingUris = setOf(bad.photoUri),
        )
        val useCase = GenerateFaceEmbeddingsUseCase(repository, generator, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertTrue(repository.failedFaces[1L]?.isNotBlank() == true)
        assertTrue(repository.savedEmbeddings.containsKey(2L))
        assertEquals(2, (result as AppResult.Success).value.itemsProcessed)
    }

    @Test
    fun `progress reports RUNNING before COMPLETE`() = runBlocking {
        val faces = (1L..25L).map { face(it) }
        val repository = FakeFaceEmbeddingRepository(pending = faces)
        val useCase = GenerateFaceEmbeddingsUseCase(repository, FakeEmbeddingGenerator(), NoOpLogger())

        useCase()

        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        val last = repository.progressUpdates.last()
        assertEquals(IndexingState.COMPLETE, last.state)
        assertEquals(25, last.itemsProcessed)
        assertNull(last.lastError)
    }
}
