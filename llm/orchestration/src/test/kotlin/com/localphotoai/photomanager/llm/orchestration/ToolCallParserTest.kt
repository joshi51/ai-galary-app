package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun `parses a valid search_photos call`() {
        val json = """{"tool":"search_photos","params":{"people":["Rahul"],"startDate":"2025-01-01","endDate":"2025-12-31"}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        val call = (result as AppResult.Success).value
        assertEquals(ToolName.SEARCH_PHOTOS, call.tool)
        assertEquals(listOf("Rahul"), call.people)
        assertEquals("2025-01-01", call.startDate)
    }

    @Test
    fun `parses a valid get_photo_metadata call with a numeric photoId`() {
        val json = """{"tool":"get_photo_metadata","params":{"photoId":42}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        assertEquals(42L, (result as AppResult.Success).value.photoId)
    }

    @Test
    fun `parses a valid no-parameter call`() {
        val json = """{"tool":"find_duplicates","params":{}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        assertEquals(ToolName.FIND_DUPLICATES, (result as AppResult.Success).value.tool)
    }

    @Test
    fun `rejects an unknown tool name`() {
        val json = """{"tool":"delete_everything","params":{}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `rejects malformed JSON without crashing`() {
        val result = ToolCallParser.parse("not json at all")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parses a valid build_organization_plan call`() {
        val json = """{"tool":"build_organization_plan","params":{"category":"TRIP","dateHint":"2025-03-15","nameHint":"Goa Trip"}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        val call = (result as AppResult.Success).value
        assertEquals(ToolName.BUILD_ORGANIZATION_PLAN, call.tool)
        assertEquals("TRIP", call.category)
        assertEquals("2025-03-15", call.dateHint)
        assertEquals("Goa Trip", call.nameHint)
    }
}
