package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

// Named distinctly from IndexPhotosUseCaseTest's own "FakePhotoRepository" — Kotlin top-level
// `private` classes are still compiled to a plain JVM class file named after the class, so two
// same-named private classes in the same package (even in different files) collide at the
// bytecode level, not just a visibility question.
private class MetadataFakePhotoRepository : PhotoRepository {
    var photoToReturn: Photo? = null

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
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photoToReturn
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = listOfNotNull(photoToReturn)
}

class GetPhotoMetadataUseCaseTest {

    @Test
    fun `returns the photo when it exists`() = runBlocking {
        val photo = testPhoto(5L)
        val repository = MetadataFakePhotoRepository().apply { photoToReturn = photo }
        val useCase = GetPhotoMetadataUseCase(repository)

        val result = useCase(5L)

        assertTrue(result is AppResult.Success)
        assertEquals(photo, (result as AppResult.Success).value)
    }

    @Test
    fun `returns NotFound when no photo has that id`() = runBlocking {
        val repository = MetadataFakePhotoRepository()
        val useCase = GetPhotoMetadataUseCase(repository)

        val result = useCase(999L)

        assertTrue(result is AppResult.Failure)
    }
}
