package com.localphotoai.photomanager.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMergeSplitTest {

    @Test
    fun `merging into an already-named target keeps the target's name`() {
        val outcome = planMerge(sourcePersonId = 1L, sourceName = "Alice", targetPersonId = 2L, targetName = "Bob")

        assertEquals("Bob", outcome.resultingName)
        assertEquals(2L, outcome.targetPersonId)
        assertEquals(1L, outcome.sourcePersonIdToDelete)
    }

    @Test
    fun `merging a named source into an unnamed target adopts the source's name`() {
        val outcome = planMerge(sourcePersonId = 1L, sourceName = "Alice", targetPersonId = 2L, targetName = null)

        assertEquals("Alice", outcome.resultingName)
    }

    @Test
    fun `merging two unnamed people results in still-unnamed`() {
        val outcome = planMerge(sourcePersonId = 1L, sourceName = null, targetPersonId = 2L, targetName = null)

        assertNull(outcome.resultingName)
    }

    @Test
    fun `the source person is always the one deleted, regardless of naming`() {
        val outcome = planMerge(sourcePersonId = 5L, sourceName = "Carol", targetPersonId = 9L, targetName = "Dave")

        assertEquals(5L, outcome.sourcePersonIdToDelete)
        assertEquals(9L, outcome.targetPersonId)
    }

    @Test
    fun `removing the last face from a person means it should be deleted`() {
        assertTrue(shouldDeletePersonAfterRemoval(remainingFaceCount = 0))
    }

    @Test
    fun `a person with remaining faces after removal is not deleted`() {
        assertFalse(shouldDeletePersonAfterRemoval(remainingFaceCount = 1))
        assertFalse(shouldDeletePersonAfterRemoval(remainingFaceCount = 5))
    }
}
