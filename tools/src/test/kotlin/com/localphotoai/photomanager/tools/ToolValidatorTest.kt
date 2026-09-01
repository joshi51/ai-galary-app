package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolValidatorTest {

    @Test
    fun `parseIsoDate returns null for a null input`() {
        val result = ToolValidator.parseIsoDate(null)
        assertTrue(result is AppResult.Success)
        assertNull((result as AppResult.Success).value)
    }

    @Test
    fun `parseIsoDate parses a valid yyyy-MM-dd date to epoch millis`() {
        val result = ToolValidator.parseIsoDate("2025-01-01")
        assertTrue(result is AppResult.Success)
        assertEquals(1735689600000L, (result as AppResult.Success).value)
    }

    @Test
    fun `parseIsoDate rejects a malformed date`() {
        val result = ToolValidator.parseIsoDate("not-a-date")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseSortOrder defaults to NEWEST for a null input`() {
        val result = ToolValidator.parseSortOrder(null)
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `parseSortOrder rejects an unrecognized value`() {
        val result = ToolValidator.parseSortOrder("biggest")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseSortOrder accepts largest and smallest case-insensitively`() {
        assertTrue(ToolValidator.parseSortOrder("LARGEST") is AppResult.Success)
        assertTrue(ToolValidator.parseSortOrder("smallest") is AppResult.Success)
    }

    @Test
    fun `requirePhotoId rejects a null id`() {
        val result = ToolValidator.requirePhotoId(null)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `requirePhotoId accepts a non-null id`() {
        val result = ToolValidator.requirePhotoId(42L)
        assertTrue(result is AppResult.Success)
        assertEquals(42L, (result as AppResult.Success).value)
    }

    @Test
    fun `parseOrganizationCategory accepts a known category case-insensitively`() {
        val result = ToolValidator.parseOrganizationCategory("screenshots")
        assertTrue(result is AppResult.Success)
        assertEquals(com.localphotoai.photomanager.domain.organization.OrganizationCategory.SCREENSHOTS, (result as AppResult.Success).value)
    }

    @Test
    fun `parseOrganizationCategory rejects an unknown category`() {
        val result = ToolValidator.parseOrganizationCategory("vacation_photos")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseOrganizationCategory rejects a null category`() {
        val result = ToolValidator.parseOrganizationCategory(null)
        assertTrue(result is AppResult.Failure)
    }
}
