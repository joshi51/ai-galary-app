package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

class SearchPhotosUseCase(
    private val searchRepository: SearchRepository,
) {
    operator fun invoke(filter: PhotoSearchFilter): AppResult<Flow<PagingData<Photo>>> {
        validate(filter)?.let { return AppResult.Failure(it) }
        return AppResult.Success(searchRepository.observeSearchResults(filter))
    }

    suspend fun searchOnce(filter: PhotoSearchFilter, limit: Int): AppResult<List<Photo>> {
        validate(filter)?.let { return AppResult.Failure(it) }
        return AppResult.Success(searchRepository.fetchOnce(filter, limit))
    }

    private fun validate(filter: PhotoSearchFilter): AppError? {
        val start = filter.startDateMs
        val end = filter.endDateMs
        if (start != null && end != null && start > end) {
            return AppError.Validation("Start date must be before end date.")
        }
        return null
    }
}
