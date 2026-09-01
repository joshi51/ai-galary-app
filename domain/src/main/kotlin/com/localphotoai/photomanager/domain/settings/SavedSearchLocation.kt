package com.localphotoai.photomanager.domain.settings

/** A user-saved point + radius used to build a location-search bounding box. */
data class SavedSearchLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
)
