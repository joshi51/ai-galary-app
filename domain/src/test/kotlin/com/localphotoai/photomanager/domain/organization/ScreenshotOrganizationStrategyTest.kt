package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, filename: String, relativePath: String? = "DCIM/Camera/") = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1_000L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = relativePath,
)

class ScreenshotOrganizationStrategyTest {

    @Test
    fun `matches filenames containing Screenshot case-insensitively`() {
        val photos = listOf(
            testPhoto(1, "Screenshot_20250101.png"),
            testPhoto(2, "screenshot-2.png"),
            testPhoto(3, "IMG_1234.jpg"),
        )

        val ops = ScreenshotOrganizationStrategy.build(photos)

        val moves = ops.filter { it.opType == OperationType.MOVE }
        assertEquals(setOf("1", "2"), moves.map { it.source!!.substringAfterLast("//") }.toSet())
    }

    @Test
    fun `photos already in the Screenshots folder are skipped`() {
        val photos = listOf(testPhoto(1, "Screenshot_1.png", relativePath = "Pictures/Screenshots/"))

        val ops = ScreenshotOrganizationStrategy.build(photos)

        assertTrue(ops.none { it.opType == OperationType.MOVE })
    }

    @Test
    fun `includes exactly one CREATE_FOLDER when there is at least one match`() {
        val photos = listOf(testPhoto(1, "Screenshot_1.png"))

        val ops = ScreenshotOrganizationStrategy.build(photos)

        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
    }

    @Test
    fun `no matches produces no operations at all`() {
        val ops = ScreenshotOrganizationStrategy.build(listOf(testPhoto(1, "IMG_1.jpg")))
        assertEquals(0, ops.size)
    }
}
