package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L
private const val NOW_MS = 2_000_000_000_000L

private fun testPhoto(id: Long, filename: String, dateTakenMs: Long?) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dateTakenMs, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

class ArchiveOrganizationStrategyTest {

    @Test
    fun `an old screenshot is flagged for archiving`() {
        val oldScreenshot = testPhoto(1, "Screenshot_old.png", NOW_MS - (400L * DAY_MS))
        val ops = ArchiveOrganizationStrategy.build(listOf(oldScreenshot), emptyList(), NOW_MS)
        assertEquals(1, ops.count { it.opType == OperationType.MOVE })
    }

    @Test
    fun `a recent screenshot is not flagged`() {
        val recentScreenshot = testPhoto(1, "Screenshot_new.png", NOW_MS - DAY_MS)
        val ops = ArchiveOrganizationStrategy.build(listOf(recentScreenshot), emptyList(), NOW_MS)
        assertTrue(ops.none { it.opType == OperationType.MOVE })
    }

    @Test
    fun `a non-representative duplicate group member is flagged, the representative is not`() {
        val photos = listOf(
            testPhoto(1, "IMG_1.jpg", NOW_MS - DAY_MS),
            testPhoto(2, "IMG_2.jpg", NOW_MS - DAY_MS),
        )
        val groups = listOf(DuplicateGroupSummary(groupId = 1L, photoIds = listOf(1L, 2L), totalSizeBytes = 200L))

        val ops = ArchiveOrganizationStrategy.build(photos, groups, NOW_MS)

        val movedSources = ops.filter { it.opType == OperationType.MOVE }.map { it.source }
        assertTrue(movedSources.any { it?.contains("2") == true })
        assertTrue(movedSources.none { it?.contains("content://1") == true })
    }

    @Test
    fun `includes a CREATE_FOLDER when anything matches`() {
        val ops = ArchiveOrganizationStrategy.build(
            listOf(testPhoto(1, "Screenshot_old.png", NOW_MS - (400L * DAY_MS))),
            emptyList(),
            NOW_MS,
        )
        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
    }
}
