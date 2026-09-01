package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class NoOpLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

private class FakePhotoRepository(
    private var remoteLight: List<LightPhotoRecord> = emptyList(),
    private val remoteFull: Map<Long, PhotoMetadata> = emptyMap(),
    private var generation: Long? = null,
    private var failFullMetadataFetch: Boolean = false,
) : PhotoRepository {

    val stored = LinkedHashMap<Long, PhotoMetadata>()
    var savedGeneration: Long? = null
    val progressUpdates = mutableListOf<IndexingProgress>()
    private val progressFlow = MutableStateFlow(IndexingProgress.IDLE)

    override fun observePhotos(): Flow<List<Photo>> = throw UnsupportedOperationException("not used in this test")

    override fun observeIndexingProgress(): Flow<IndexingProgress> = progressFlow

    override suspend fun fetchGeneration(): Long? = generation

    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> = remoteLight

    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> =
        stored.values.map { LightPhotoRecord(it.mediaStoreId, it.dateModifiedMs) }

    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> {
        if (failFullMetadataFetch) error("boom")
        return mediaStoreIds.map { remoteFull.getValue(it) }
    }

    override suspend fun upsert(photos: List<PhotoMetadata>) {
        photos.forEach { stored[it.mediaStoreId] = it }
    }

    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) {
        mediaStoreIds.forEach { stored.remove(it) }
    }

    override suspend fun updateIndexingProgress(progress: IndexingProgress) {
        progressUpdates += progress
        progressFlow.value = progress
    }

    override suspend fun saveGeneration(generation: Long) {
        savedGeneration = generation
    }

    override suspend fun lastSavedGeneration(): Long? = savedGeneration

    override suspend fun fetchById(mediaStoreId: Long): Photo? =
        throw UnsupportedOperationException("not used in this test")

    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> =
        throw UnsupportedOperationException("not used in this test")
}

private fun metadata(id: Long, dateModifiedMs: Long) = PhotoMetadata(
    mediaStoreId = id,
    uri = "content://media/external/images/media/$id",
    filename = "IMG_$id.jpg",
    mimeType = "image/jpeg",
    sizeBytes = 1_000L,
    width = 100,
    height = 100,
    dateAddedMs = dateModifiedMs,
    dateModifiedMs = dateModifiedMs,
    dateTakenMs = dateModifiedMs,
    orientationDegrees = 0,
    latitude = null,
    longitude = null,
)

class IndexPhotosUseCaseTest {

    @Test
    fun `new photos are fetched with full metadata and upserted`() = runBlocking {
        val photo = metadata(id = 1L, dateModifiedMs = 1_000L)
        val repository = FakePhotoRepository(
            remoteLight = listOf(LightPhotoRecord(1L, 1_000L)),
            remoteFull = mapOf(1L to photo),
        )
        val useCase = IndexPhotosUseCase(repository, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertEquals(photo, repository.stored[1L])
        val progress = (result as AppResult.Success).value
        assertEquals(IndexingState.COMPLETE, progress.state)
        assertEquals(1, progress.itemsTotal)
    }

    @Test
    fun `deleted photos are removed from local storage`() = runBlocking {
        val repository = FakePhotoRepository(remoteLight = emptyList())
        repository.stored[1L] = metadata(id = 1L, dateModifiedMs = 1_000L)
        val useCase = IndexPhotosUseCase(repository, NoOpLogger())

        useCase()

        assertTrue(repository.stored.isEmpty())
    }

    @Test
    fun `unchanged generation skips the scan entirely`() = runBlocking {
        val repository = FakePhotoRepository(
            remoteLight = listOf(LightPhotoRecord(1L, 1_000L)),
            remoteFull = mapOf(1L to metadata(1L, 1_000L)),
            generation = 42L,
        )
        repository.savedGeneration = 42L
        val useCase = IndexPhotosUseCase(repository, NoOpLogger())

        useCase()

        assertTrue("full metadata should never be fetched when generation is unchanged", repository.stored.isEmpty())
    }

    @Test
    fun `failure is reported as an ERROR progress state and a Failure result`() = runBlocking {
        val repository = FakePhotoRepository(
            remoteLight = listOf(LightPhotoRecord(1L, 1_000L)),
            failFullMetadataFetch = true,
        )
        val useCase = IndexPhotosUseCase(repository, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Failure)
        val lastProgress = repository.progressUpdates.last()
        assertEquals(IndexingState.ERROR, lastProgress.state)
        assertTrue(lastProgress.lastError?.isNotBlank() == true)
    }

    @Test
    fun `progress reports intermediate RUNNING state before COMPLETE`() = runBlocking {
        val repository = FakePhotoRepository(
            remoteLight = listOf(LightPhotoRecord(1L, 1_000L)),
            remoteFull = mapOf(1L to metadata(1L, 1_000L)),
        )
        val useCase = IndexPhotosUseCase(repository, NoOpLogger())

        useCase()

        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        assertEquals(IndexingState.COMPLETE, repository.progressUpdates.last().state)
        assertNull(repository.progressUpdates.last().lastError)
    }
}
