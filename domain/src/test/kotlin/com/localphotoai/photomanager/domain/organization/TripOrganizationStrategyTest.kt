package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L

private fun tripPhoto(id: Long, lat: Double, lon: Double, dayOffset: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dayOffset * DAY_MS, orientationDegrees = 0, latitude = lat, longitude = lon,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

class TripOrganizationStrategyTest {

    private val goaCluster = listOf(
        tripPhoto(1, 15.2993, 74.1240, 100),
        tripPhoto(2, 15.2995, 74.1242, 100),
        tripPhoto(3, 15.2991, 74.1241, 101),
    )
    private val delhiCluster = listOf(
        tripPhoto(4, 28.6139, 77.2090, 200),
        tripPhoto(5, 28.6140, 77.2091, 200),
        tripPhoto(6, 28.6141, 77.2089, 201),
    )

    @Test
    fun `with no dateHint, picks the most recent cluster`() {
        val ops = TripOrganizationStrategy.build(goaCluster + delhiCluster, dateHint = null, nameHint = "My Trip")

        val createAlbum = ops.single { it.opType == OperationType.CREATE_ALBUM }
        assertEquals(setOf(4L, 5L, 6L), createAlbum.memberPhotoIds.toSet())
    }

    @Test
    fun `a dateHint inside a cluster's range picks that cluster`() {
        val hintDate = java.text.SimpleDateFormat("yyyy-MM-dd").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(100 * DAY_MS))

        val ops = TripOrganizationStrategy.build(goaCluster + delhiCluster, dateHint = hintDate, nameHint = null)

        val createAlbum = ops.single { it.opType == OperationType.CREATE_ALBUM }
        assertEquals(setOf(1L, 2L, 3L), createAlbum.memberPhotoIds.toSet())
    }

    @Test
    fun `nameHint becomes the album name, falling back to a date-range name`() {
        val named = TripOrganizationStrategy.build(goaCluster, dateHint = null, nameHint = "Goa Trip")
        assertEquals("Goa Trip", named.single { it.opType == OperationType.CREATE_ALBUM }.destination)

        val unnamed = TripOrganizationStrategy.build(goaCluster, dateHint = null, nameHint = null)
        assertTrue(unnamed.single { it.opType == OperationType.CREATE_ALBUM }.destination.startsWith("Trip "))
    }

    @Test
    fun `no clusters found produces no operations`() {
        val ops = TripOrganizationStrategy.build(emptyList(), dateHint = null, nameHint = null)
        assertTrue(ops.isEmpty())
    }
}
