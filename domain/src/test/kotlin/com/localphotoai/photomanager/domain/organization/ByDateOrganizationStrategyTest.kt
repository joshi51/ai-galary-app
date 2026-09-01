package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, dateTakenMs: Long?, relativePath: String? = "DCIM/Camera/") = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dateTakenMs, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = relativePath,
)

class ByDateOrganizationStrategyTest {

    @Test
    fun `groups photos in the raw camera folder by year-month`() {
        val photos = listOf(
            testPhoto(1, dateTakenMs = 1_735_689_600_000L), // 2025-01-01
            testPhoto(2, dateTakenMs = 1_738_368_000_000L), // 2025-02-01
        )

        val ops = ByDateOrganizationStrategy.build(photos)

        assertEquals(2, ops.count { it.opType == OperationType.CREATE_FOLDER })
        assertEquals(2, ops.count { it.opType == OperationType.MOVE })
    }

    @Test
    fun `photos with a null dateTakenMs are skipped`() {
        val ops = ByDateOrganizationStrategy.build(listOf(testPhoto(1, dateTakenMs = null)))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `photos already outside the raw camera folder are skipped`() {
        val ops = ByDateOrganizationStrategy.build(
            listOf(testPhoto(1, dateTakenMs = 1_735_689_600_000L, relativePath = "Pictures/2025/2025-01/")),
        )
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `same year-month photos share one CREATE_FOLDER operation`() {
        val photos = listOf(
            testPhoto(1, dateTakenMs = 1_735_689_600_000L),
            testPhoto(2, dateTakenMs = 1_735_776_000_000L),
        )

        val ops = ByDateOrganizationStrategy.build(photos)

        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
        assertEquals(2, ops.count { it.opType == OperationType.MOVE })
    }
}
