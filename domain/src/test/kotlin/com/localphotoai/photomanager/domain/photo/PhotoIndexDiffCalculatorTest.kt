package com.localphotoai.photomanager.domain.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoIndexDiffCalculatorTest {

    @Test
    fun `photo present in both with same dateModified is untouched`() {
        val record = LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 1_000L)

        val diff = PhotoIndexDiffCalculator.computeDiff(remote = listOf(record), local = listOf(record))

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `photo present remotely but not locally is new`() {
        val remote = listOf(LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 1_000L))

        val diff = PhotoIndexDiffCalculator.computeDiff(remote = remote, local = emptyList())

        assertEquals(listOf(1L), diff.newOrChangedIds)
        assertTrue(diff.deletedIds.isEmpty())
    }

    @Test
    fun `photo with a newer dateModified locally is treated as changed`() {
        val remote = listOf(LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 2_000L))
        val local = listOf(LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 1_000L))

        val diff = PhotoIndexDiffCalculator.computeDiff(remote = remote, local = local)

        assertEquals(listOf(1L), diff.newOrChangedIds)
        assertTrue(diff.deletedIds.isEmpty())
    }

    @Test
    fun `photo present locally but not remotely is deleted`() {
        val local = listOf(LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 1_000L))

        val diff = PhotoIndexDiffCalculator.computeDiff(remote = emptyList(), local = local)

        assertTrue(diff.newOrChangedIds.isEmpty())
        assertEquals(listOf(1L), diff.deletedIds)
    }

    @Test
    fun `mixed new, changed, unchanged, and deleted photos are all classified correctly`() {
        val unchanged = LightPhotoRecord(mediaStoreId = 1L, dateModifiedMs = 1_000L)
        val changedRemote = LightPhotoRecord(mediaStoreId = 2L, dateModifiedMs = 5_000L)
        val changedLocal = LightPhotoRecord(mediaStoreId = 2L, dateModifiedMs = 4_000L)
        val newPhoto = LightPhotoRecord(mediaStoreId = 3L, dateModifiedMs = 1_000L)
        val deletedPhoto = LightPhotoRecord(mediaStoreId = 4L, dateModifiedMs = 1_000L)

        val diff = PhotoIndexDiffCalculator.computeDiff(
            remote = listOf(unchanged, changedRemote, newPhoto),
            local = listOf(unchanged, changedLocal, deletedPhoto),
        )

        assertEquals(setOf(2L, 3L), diff.newOrChangedIds.toSet())
        assertEquals(listOf(4L), diff.deletedIds)
    }

    @Test
    fun `empty remote and local produces an empty diff`() {
        val diff = PhotoIndexDiffCalculator.computeDiff(remote = emptyList(), local = emptyList())

        assertTrue(diff.isEmpty)
    }
}
