package com.localphotoai.photomanager.domain.organization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L

private fun photo(id: Long, lat: Double, lon: Double, dayOffset: Long) =
    GpsTaggedPhoto(photoId = id, latitude = lat, longitude = lon, dateTakenMs = dayOffset * DAY_MS)

class TripClustererTest {

    @Test
    fun `photos close in both distance and time join one cluster`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.3000, 74.1250, dayOffset = 100),
            photo(3, 15.2990, 74.1245, dayOffset = 101),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(1, clusters.size)
        assertEquals(setOf(1L, 2L, 3L), clusters[0].photoIds.toSet())
    }

    @Test
    fun `photos far apart in distance do not join even if taken on the same day`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
            photo(3, 15.2991, 74.1241, dayOffset = 100),
            photo(4, 28.6139, 77.2090, dayOffset = 100),
            photo(5, 28.6140, 77.2091, dayOffset = 100),
            photo(6, 28.6141, 77.2089, dayOffset = 100),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(2, clusters.size)
        assertTrue(clusters.none { it.photoIds.contains(1L) && it.photoIds.contains(4L) })
    }

    @Test
    fun `photos close in distance but far apart in time do not join`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
            photo(3, 15.2991, 74.1241, dayOffset = 100),
            photo(4, 15.2993, 74.1240, dayOffset = 400),
            photo(5, 15.2995, 74.1242, dayOffset = 400),
            photo(6, 15.2991, 74.1241, dayOffset = 400),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(2, clusters.size)
    }

    @Test
    fun `a cluster smaller than MIN_PHOTOS is discarded as noise`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(0, clusters.size)
    }

    @Test
    fun `cluster date range spans its earliest to latest member`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 101),
            photo(3, 15.2991, 74.1241, dayOffset = 102),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(1, clusters.size)
        assertEquals(100 * DAY_MS, clusters[0].startDateMs)
        assertEquals(102 * DAY_MS, clusters[0].endDateMs)
    }
}
