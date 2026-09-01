package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun photo(id: Long) = PhotoForSimilarityEmbedding(id, "content://$id", 100, 100, 0)

private class EmbeddingFakeRepository(private val pending: List<PhotoForSimilarityEmbedding>) : NoOpPhotoGroupRepository() {
    val saved = LinkedHashMap<Long, FloatArray>()
    val failed = LinkedHashMap<Long, String>()
    val progressUpdates = mutableListOf<IndexingProgress>()
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int) = pending
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {
        saved[photoId] = vector
    }
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {
        failed[photoId] = error
    }
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) { progressUpdates += progress }
}

private class FakeGenerator(
    override val modelVersion: Int = 1,
    private val failingUris: Set<String> = emptySet(),
) : ImageSimilarityEmbeddingGenerator {
    override suspend fun generateEmbedding(photoUri: String, widthPx: Int, heightPx: Int, orientationDegrees: Int): FloatArray {
        if (photoUri in failingUris) error("simulated decode failure")
        return floatArrayOf(3f, 4f)
    }
}

class GenerateImageSimilarityEmbeddingsUseCaseTest {

    @Test
    fun `no pending photos completes immediately`() = runBlocking {
        val repository = EmbeddingFakeRepository(emptyList())
        val result = GenerateImageSimilarityEmbeddingsUseCase(repository, FakeGenerator(), NoOpLogger())()
        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(0, result.value.itemsTotal)
    }

    @Test
    fun `a failing photo is flagged without aborting the batch`() = runBlocking {
        val bad = photo(1L)
        val good = photo(2L)
        val repository = EmbeddingFakeRepository(listOf(bad, good))
        val result = GenerateImageSimilarityEmbeddingsUseCase(
            repository, FakeGenerator(failingUris = setOf("content://1")), NoOpLogger(),
        )()
        assertTrue(result is AppResult.Success)
        assertTrue(repository.failed.containsKey(1L))
        assertTrue(repository.saved.containsKey(2L))
    }

    @Test
    fun `saved embeddings are L2-normalized`() = runBlocking {
        val repository = EmbeddingFakeRepository(listOf(photo(1L)))
        GenerateImageSimilarityEmbeddingsUseCase(repository, FakeGenerator(), NoOpLogger())()
        val vector = repository.saved.getValue(1L)
        val magnitude = sqrt(vector.sumOf { (it * it).toDouble() })
        assertTrue(kotlin.math.abs(magnitude - 1.0) < 1e-4)
    }
}
