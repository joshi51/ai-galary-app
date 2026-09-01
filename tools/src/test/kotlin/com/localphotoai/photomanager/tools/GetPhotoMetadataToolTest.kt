package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
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

private class MetadataToolFakePhotoRepository(private val photo: Photo?) : PhotoRepository {
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
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photo
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = listOfNotNull(photo)
}

class GetPhotoMetadataToolTest {

    @Test
    fun `rejects a missing photoId`() = runBlocking {
        val tool = GetPhotoMetadataTool(GetPhotoMetadataUseCase(MetadataToolFakePhotoRepository(null)))
        val result = tool.execute(ToolCall(tool = ToolName.GET_PHOTO_METADATA))
        assertTrue(result is ToolOutcome.Error)
    }

    @Test
    fun `reports a not-found photoId as an error, not a crash`() = runBlocking {
        val tool = GetPhotoMetadataTool(GetPhotoMetadataUseCase(MetadataToolFakePhotoRepository(null)))
        val result = tool.execute(ToolCall(tool = ToolName.GET_PHOTO_METADATA, photoId = 999L))
        assertTrue(result is ToolOutcome.Error)
    }

    @Test
    fun `returns metadata for an existing photo`() = runBlocking {
        val photo = testPhoto(5L)
        val tool = GetPhotoMetadataTool(GetPhotoMetadataUseCase(MetadataToolFakePhotoRepository(photo)))
        val result = tool.execute(ToolCall(tool = ToolName.GET_PHOTO_METADATA, photoId = 5L))
        assertTrue(result is ToolOutcome.Metadata)
        assertEquals(photo, (result as ToolOutcome.Metadata).photo)
    }
}
