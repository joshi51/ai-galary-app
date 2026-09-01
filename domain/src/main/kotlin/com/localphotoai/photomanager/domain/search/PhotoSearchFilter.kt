package com.localphotoai.photomanager.domain.search

enum class PhotoSortOrder { NEWEST, LARGEST, SMALLEST }

/**
 * A deterministic (non-LLM) photo search request. [personIds] may be empty — an empty set means
 * "no person filter" (e.g. Phase 8's "find my largest photos"), not "match nothing". The
 * deterministic Search UI still requires the user to pick at least one person before submitting
 * a filter (see `SearchViewModel.toDomainFilterOrNull`) — that's a UI choice, not a domain
 * invariant, now that Phase 8's tool layer needs person-less queries to be valid. Multi-person
 * selection is AND (intersection): a matching photo must contain every id in [personIds].
 */
data class PhotoSearchFilter(
    val personIds: Set<Long> = emptySet(),
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val locationBoundingBox: BoundingBox? = null,
    val sortBy: PhotoSortOrder = PhotoSortOrder.NEWEST,
)

/** A GPS bounding box used for location filtering, in decimal degrees. */
data class BoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
)
