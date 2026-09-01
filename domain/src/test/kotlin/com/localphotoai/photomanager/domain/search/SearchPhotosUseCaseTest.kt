package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

private class FakeSearchRepository : SearchRepository {
    var lastFilter: PhotoSearchFilter? = null
    var callCount = 0
    var onceResult: List<Photo> = emptyList()
    var lastOnceLimit: Int? = null

    override fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>> {
        lastFilter = filter
        callCount++
        return flowOf(PagingData.empty())
    }

    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        lastFilter = filter
        lastOnceLimit = limit
        callCount++
        return onceResult
    }
}

class SearchPhotosUseCaseTest {

    @Test
    fun `accepts a filter with no selected people`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(PhotoSearchFilter(personIds = emptySet()))

        assertTrue(result is AppResult.Success)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `rejects a date range where start is after end`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(
            PhotoSearchFilter(personIds = setOf(1L), startDateMs = 2_000L, endDateMs = 1_000L),
        )

        assertTrue(result is AppResult.Failure)
        assertEquals(0, repository.callCount)
    }

    @Test
    fun `delegates a valid filter to the repository unchanged`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)
        val filter = PhotoSearchFilter(personIds = setOf(1L, 2L), startDateMs = 1_000L, endDateMs = 2_000L)

        val result = useCase(filter)

        assertTrue(result is AppResult.Success)
        assertEquals(filter, repository.lastFilter)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `accepts a filter with only a start date and no end date`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(PhotoSearchFilter(personIds = setOf(1L), startDateMs = 1_000L))

        assertTrue(result is AppResult.Success)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `searchOnce rejects an invalid date range without calling the repository`() = runBlocking {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase.searchOnce(
            PhotoSearchFilter(startDateMs = 2_000L, endDateMs = 1_000L),
            limit = 200,
        )

        assertTrue(result is AppResult.Failure)
        assertEquals(0, repository.callCount)
    }

    @Test
    fun `searchOnce passes the limit and sort order through to the repository`() = runBlocking {
        val repository = FakeSearchRepository()
        repository.onceResult = listOf(testPhoto(1L))
        val useCase = SearchPhotosUseCase(repository)
        val filter = PhotoSearchFilter(sortBy = PhotoSortOrder.LARGEST)

        val result = useCase.searchOnce(filter, limit = 50)

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).value.size)
        assertEquals(50, repository.lastOnceLimit)
        assertEquals(PhotoSortOrder.LARGEST, repository.lastFilter?.sortBy)
    }
}
