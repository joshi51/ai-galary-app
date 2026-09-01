package com.localphotoai.photomanager.domain.search

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Converts a center point + radius into a rectangular [BoundingBox] for a fast indexed
 * `BETWEEN` query, rather than an exact circular geo-distance calculation (unnecessary
 * precision for "near a saved location" search — a bounding box is a cheap superset).
 */
object LocationBoundingBoxCalculator {

    private const val KM_PER_DEGREE_LATITUDE = 111.0

    fun fromPointAndRadiusKm(latitude: Double, longitude: Double, radiusKm: Double): BoundingBox {
        val latDelta = radiusKm / KM_PER_DEGREE_LATITUDE
        // Longitude degrees shrink in real-world distance as latitude moves away from the
        // equator (meridians converge toward the poles); dividing by cos(latitude) keeps the
        // box's real-world east-west width roughly constant regardless of latitude. Clamp
        // near the poles (cos -> 0) to avoid dividing by (near-)zero.
        val cosLatitude = max(cos(Math.toRadians(latitude)), 0.01)
        val lonDelta = radiusKm / (KM_PER_DEGREE_LATITUDE * cosLatitude)

        return BoundingBox(
            minLatitude = max(latitude - latDelta, -90.0),
            maxLatitude = min(latitude + latDelta, 90.0),
            minLongitude = max(longitude - lonDelta, -180.0),
            maxLongitude = min(longitude + lonDelta, 180.0),
        )
    }
}
