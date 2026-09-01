package com.localphotoai.photomanager.domain.face

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun photo(id: Long) = Photo(
    mediaStoreId = id,
    uri = "content://media/external/images/media/$id",
    filename = "IMG_$id.jpg",
    mimeType = "image/jpeg",
    sizeBytes = 1_000L,
    width = 100,
    height = 100,
    dateAddedMs = 1_000L,
    dateModifiedMs = 1_000L,
    dateTakenMs = 1_000L,
    orientationDegrees = 0,
    latitude = null,
    longitude = null,
    lastIndexedAtMs = 1_000L,
    indexError = null,
)

private class FakeFaceRepository(private var pending: List<Photo>) : FaceRepository {
    val savedFaces = LinkedHashMap<Long, List<DetectedFace>>()
    val completedErrors = LinkedHashMap<Long, String?>()
    val progressUpdates = mutableListOf<IndexingProgress>()
    private val progressFlow = MutableStateFlow(IndexingProgress.IDLE)

    override suspend fun fetchPhotosNeedingDetection(): List<Photo> = pending

    override suspend fun saveFaces(photoId: Long, rotationDegrees: Int, faces: List<DetectedFace>) {
        savedFaces[photoId] = faces
    }

    override suspend fun markDetectionComplete(photoId: Long, error: String?) {
        completedErrors[photoId] = error
    }

    override fun observeFacesForPhoto(photoId: Long): Flow<List<Face>> =
        throw UnsupportedOperationException("not used in this test")

    override fun observeDetectionProgress(): Flow<IndexingProgress> = progressFlow

    override suspend fun updateDetectionProgress(progress: IndexingProgress) {
        progressUpdates += progress
        progressFlow.value = progress
    }
}

private class FakeFaceDetector(
    private val resultsByUri: Map<String, List<DetectedFace>> = emptyMap(),
    private val failingUris: Set<String> = emptySet(),
) : FaceDetector {
    override suspend fun detectFaces(
        photoUri: String,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        orientationDegrees: Int,
    ): List<DetectedFace> {
        if (photoUri in failingUris) error("simulated corrupted image")
        return resultsByUri[photoUri] ?: emptyList()
    }
}

class DetectFacesUseCaseTest {

    @Test
    fun `no pending photos completes immediately with zero items`() = runBlocking {
        val repository = FakeFaceRepository(pending = emptyList())
        val useCase = DetectFacesUseCase(repository, FakeFaceDetector(), NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(0, result.value.itemsTotal)
    }

    @Test
    fun `detected faces are saved and the photo marked complete without error`() = runBlocking {
        val target = photo(1L)
        val face = DetectedFace(left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.5f, confidence = 1f)
        val repository = FakeFaceRepository(pending = listOf(target))
        val detector = FakeFaceDetector(resultsByUri = mapOf(target.uri to listOf(face)))
        val useCase = DetectFacesUseCase(repository, detector, NoOpLogger())

        useCase()

        assertEquals(listOf(face), repository.savedFaces[1L])
        assertNull(repository.completedErrors[1L])
    }

    @Test
    fun `a corrupted image is flagged with an error but does not abort the batch`() = runBlocking {
        val corrupted = photo(1L)
        val healthy = photo(2L)
        val face = DetectedFace(left = 0f, top = 0f, right = 1f, bottom = 1f, confidence = 1f)
        val repository = FakeFaceRepository(pending = listOf(corrupted, healthy))
        val detector = FakeFaceDetector(
            resultsByUri = mapOf(healthy.uri to listOf(face)),
            failingUris = setOf(corrupted.uri),
        )
        val useCase = DetectFacesUseCase(repository, detector, NoOpLogger())

        val result = useCase()

        assertTrue(result is AppResult.Success)
        assertTrue(repository.completedErrors[1L]?.isNotBlank() == true)
        assertNull(repository.completedErrors[2L])
        assertEquals(listOf(face), repository.savedFaces[2L])
        assertEquals(2, (result as AppResult.Success).value.itemsProcessed)
    }

    @Test
    fun `photos with many faces are all saved`() = runBlocking {
        val target = photo(1L)
        val manyFaces = (1..12).map {
            DetectedFace(left = 0f, top = 0f, right = 0.1f * it, bottom = 0.1f, confidence = 1f)
        }
        val repository = FakeFaceRepository(pending = listOf(target))
        val detector = FakeFaceDetector(resultsByUri = mapOf(target.uri to manyFaces))
        val useCase = DetectFacesUseCase(repository, detector, NoOpLogger())

        useCase()

        assertEquals(12, repository.savedFaces[1L]?.size)
    }

    @Test
    fun `progress reports RUNNING before COMPLETE with correct totals`() = runBlocking {
        val photos = (1L..25L).map { photo(it) }
        val repository = FakeFaceRepository(pending = photos)
        val useCase = DetectFacesUseCase(repository, FakeFaceDetector(), NoOpLogger())

        useCase()

        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        val last = repository.progressUpdates.last()
        assertEquals(IndexingState.COMPLETE, last.state)
        assertEquals(25, last.itemsProcessed)
        assertEquals(25, last.itemsTotal)
    }
}
