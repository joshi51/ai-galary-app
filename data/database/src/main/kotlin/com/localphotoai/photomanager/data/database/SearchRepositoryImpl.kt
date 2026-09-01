package com.localphotoai.photomanager.data.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.localphotoai.photomanager.data.database.dao.SearchDao
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SEARCH_PAGE_SIZE = 30

class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao,
) : SearchRepository {

    override fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>> {
        val box = filter.locationBoundingBox
        return Pager(
            config = PagingConfig(pageSize = SEARCH_PAGE_SIZE, enablePlaceholders = false),
        ) {
            searchDao.searchPhotos(
                personIds = filter.personIds.toList(),
                personCount = filter.personIds.size,
                startDateMs = filter.startDateMs,
                endDateMs = filter.endDateMs,
                minLat = box?.minLatitude,
                maxLat = box?.maxLatitude,
                minLon = box?.minLongitude,
                maxLon = box?.maxLongitude,
                sortBy = filter.sortBy.name,
            )
        }.flow.map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        val box = filter.locationBoundingBox
        return searchDao.searchPhotosOnce(
            personIds = filter.personIds.toList(),
            personCount = filter.personIds.size,
            startDateMs = filter.startDateMs,
            endDateMs = filter.endDateMs,
            minLat = box?.minLatitude,
            maxLat = box?.maxLatitude,
            minLon = box?.minLongitude,
            maxLon = box?.maxLongitude,
            sortBy = filter.sortBy.name,
            limit = limit,
        ).map { it.toDomain() }
    }
}
