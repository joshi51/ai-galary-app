package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

/** Access to deterministic search queries. Implemented in `:data:database` (Room only). */
interface SearchRepository {
    fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>>

    /** A bounded, non-paged snapshot — for Phase 8's tool-driven queries, which need a fixed
     * result set to summarize (a count for the templated response), not infinite scroll. */
    suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo>
}
