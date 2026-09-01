package com.localphotoai.photomanager.domain.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGroupingTest {

    @Test
    fun `photos sharing a content hash are grouped, singletons are dropped`() {
        val hashes = listOf(
            PhotoHashInput(1L, "hashA", perceptualHash = 0L, dateTakenMs = null),
            PhotoHashInput(2L, "hashA", perceptualHash = 0L, dateTakenMs = null),
            PhotoHashInput(3L, "hashB", perceptualHash = 0L, dateTakenMs = null),
        )
        val groups = groupByExactHash(hashes)
        assertEquals(setOf(1L, 2L), groups.getValue("hashA").toSet())
        assertTrue(groups.containsKey("hashB").not())
    }

    @Test
    fun `near-identical hashes within the time window are grouped as BURST`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(1, groups.size)
        assertEquals(SimilarGroupKindResult.BURST, groups.single().first)
    }

    @Test
    fun `near-identical hashes outside the time window are grouped as NEAR_DUPLICATE, not BURST`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 100_000L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(1, groups.size)
        assertEquals(SimilarGroupKindResult.NEAR_DUPLICATE, groups.single().first)
    }

    @Test
    fun `dissimilar hashes are not grouped at all`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = -1L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a photo with a null dateTakenMs can still be a near-duplicate but never a burst`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = null),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(SimilarGroupKindResult.NEAR_DUPLICATE, groups.single().first)
    }
}
