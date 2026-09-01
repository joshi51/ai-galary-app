package com.localphotoai.photomanager.fsops

import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

private fun testOperation(
    opType: OperationType = OperationType.MOVE,
    source: String? = "content://1",
    destination: String = "Pictures/Screenshots/1.jpg",
) = OrganizationOperation(id = 1, opType = opType, source = source, destination = destination, reason = "r", confidence = 1.0f)

private class ValidatorFakePhotoRepository(private val photos: List<Photo>) : PhotoRepository {
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
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photos.firstOrNull { it.mediaStoreId == mediaStoreId }
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = photos.filter { it.mediaStoreId in mediaStoreIds }
}

class PlanValidatorTest {

    @Test
    fun `a MOVE whose source photo no longer exists is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(emptyList()))
        val result = validator.validate(testOperation(source = "content://1"), emptySet())
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a destination outside the allowed roots is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(testOperation(destination = "../../etc/passwd"), emptySet())
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a destination colliding with another operation in the same plan is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(
            testOperation(destination = "Pictures/Screenshots/1.jpg"),
            setOf("Pictures/Screenshots/1.jpg"),
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a valid MOVE with an existing source and a clean destination is valid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(testOperation(), emptySet())
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `CREATE_FOLDER and CREATE_ALBUM need no source photo`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(emptyList()))
        val folderResult = validator.validate(
            testOperation(opType = OperationType.CREATE_FOLDER, source = null, destination = "Pictures/Archive"),
            emptySet(),
        )
        val albumResult = validator.validate(
            testOperation(opType = OperationType.CREATE_ALBUM, source = null, destination = "My Album"),
            emptySet(),
        )
        assertTrue(folderResult is ValidationResult.Valid)
        assertTrue(albumResult is ValidationResult.Valid)
    }
}
