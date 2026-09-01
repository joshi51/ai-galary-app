package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class HashFakeRepository(private val pending: List<PhotoForHashing>) : NoOpPhotoGroupRepository() {
    val saved = LinkedHashMap<Long, PhotoHashResult>()
    val failed = LinkedHashMap<Long, String>()
    val progressUpdates = mutableListOf<IndexingProgress>()
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = pending
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {
        saved[photoId] = PhotoHashResult(contentHash, perceptualHash)
    }
    override suspend fun markHashFailed(photoId: Long, error: String) { failed[photoId] = error }
    override suspend fun updateHashProgress(progress: IndexingProgress) { progressUpdates += progress }
}

private class FakeHasher(private val failingUris: Set<String> = emptySet()) : PhotoHasher {
    override suspend fun hash(photoUri: String): PhotoHashResult {
        if (photoUri in failingUris) error("simulated decode failure")
        return PhotoHashResult(contentHash = "hash-$photoUri", perceptualHash = 0L)
    }
}

class HashPhotosUseCaseTest {

    @Test
    fun `no pending photos completes immediately`() = runBlocking {
        val repository = HashFakeRepository(emptyList())
        val result = HashPhotosUseCase(repository, FakeHasher(), NoOpLogger())()
        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(0, result.value.itemsTotal)
    }

    @Test
    fun `a failed hash is flagged but does not abort the batch`() = runBlocking {
        val bad = PhotoForHashing(1L, "content://bad")
        val good = PhotoForHashing(2L, "content://good")
        val repository = HashFakeRepository(listOf(bad, good))
        val result = HashPhotosUseCase(repository, FakeHasher(failingUris = setOf("content://bad")), NoOpLogger())()
        assertTrue(result is AppResult.Success)
        assertTrue(repository.failed.containsKey(1L))
        assertTrue(repository.saved.containsKey(2L))
        assertEquals(2, (result as AppResult.Success).value.itemsProcessed)
    }

    @Test
    fun `progress reports RUNNING before COMPLETE`() = runBlocking {
        val photos = (1L..25L).map { PhotoForHashing(it, "content://$it") }
        val repository = HashFakeRepository(photos)
        HashPhotosUseCase(repository, FakeHasher(), NoOpLogger())()
        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        assertEquals(IndexingState.COMPLETE, repository.progressUpdates.last().state)
    }
}
