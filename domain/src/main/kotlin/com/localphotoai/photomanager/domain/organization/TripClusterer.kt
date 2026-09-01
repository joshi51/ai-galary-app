package com.localphotoai.photomanager.domain.organization

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsTaggedPhoto(
    val photoId: Long,
    val latitude: Double,
    val longitude: Double,
    val dateTakenMs: Long,
)

data class TripCluster(
    val photoIds: List<Long>,
    val startDateMs: Long,
    val endDateMs: Long,
    /** Fraction of members within half [TripClusterer.DISTANCE_THRESHOLD_METERS] of the
     * cluster's centroid — a simple tightness proxy, not a claimed accuracy metric. */
    val tightness: Float,
)

/**
 * Groups GPS-tagged photos into candidate "trip" clusters via union-find: two photos join the
 * same cluster when both their distance and time gap clear the named thresholds below — both
 * conditions, not either alone, so a recurring nearby commute doesn't collapse into one endless
 * "trip" just because it repeats daily, and a same-day long-distance flight doesn't merge two
 * unrelated locations into one trip either. Named, documented, untuned constants — same honest
 * treatment as every prior phase's heuristic thresholds (no labeled ground-truth trip dataset
 * was available to calibrate against).
 */
object TripClusterer {
    const val DISTANCE_THRESHOLD_METERS = 50_000.0
    const val TIME_GAP_MS = 86_400_000L // 24 hours
    const val MIN_PHOTOS = 3

    fun cluster(photos: List<GpsTaggedPhoto>): List<TripCluster> {
        val parent = photos.associate { it.photoId to it.photoId }.toMutableMap()

        fun find(id: Long): Long {
            var root = id
            while (parent.getValue(root) != root) root = parent.getValue(root)
            return root
        }

        fun union(a: Long, b: Long) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootA] = rootB
        }

        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                val a = photos[i]
                val b = photos[j]
                val distance = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                val timeGap = kotlin.math.abs(a.dateTakenMs - b.dateTakenMs)
                if (distance <= DISTANCE_THRESHOLD_METERS && timeGap <= TIME_GAP_MS) {
                    union(a.photoId, b.photoId)
                }
            }
        }

        val byId = photos.associateBy { it.photoId }
        return photos.map { it.photoId }.groupBy { find(it) }.values
            .filter { it.size >= MIN_PHOTOS }
            .map { ids ->
                val members = ids.map { byId.getValue(it) }
                val centroidLat = members.sumOf { it.latitude } / members.size
                val centroidLon = members.sumOf { it.longitude } / members.size
                val halfThreshold = DISTANCE_THRESHOLD_METERS / 2
                val tightCount = members.count {
                    haversineMeters(it.latitude, it.longitude, centroidLat, centroidLon) <= halfThreshold
                }
                TripCluster(
                    photoIds = ids,
                    startDateMs = members.minOf { it.dateTakenMs },
                    endDateMs = members.maxOf { it.dateTakenMs },
                    tightness = tightCount.toFloat() / members.size,
                )
            }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
