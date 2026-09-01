package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RegistryFakeTool(override val name: ToolName, private val outcome: ToolOutcome) : Tool {
    var callCount = 0
    override suspend fun execute(call: ToolCall): ToolOutcome {
        callCount++
        return outcome
    }
}

class ToolRegistryTest {

    @Test
    fun `dispatches to the tool matching the call's name`() = runBlocking {
        val statsOutcome = ToolOutcome.Error("unused")
        val statsTool = RegistryFakeTool(ToolName.GET_STORAGE_STATISTICS, statsOutcome)
        val dupTool = RegistryFakeTool(ToolName.FIND_DUPLICATES, ToolOutcome.Error("unused2"))
        val registry = ToolRegistry(listOf(statsTool, dupTool))

        val result = registry.dispatch(ToolCall(tool = ToolName.GET_STORAGE_STATISTICS))

        assertEquals(statsOutcome, result)
        assertEquals(1, statsTool.callCount)
        assertEquals(0, dupTool.callCount)
    }

    @Test
    fun `returns an error outcome when no tool matches`() = runBlocking {
        val registry = ToolRegistry(emptyList())

        val result = registry.dispatch(ToolCall(tool = ToolName.FIND_DUPLICATES))

        assertTrue(result is ToolOutcome.Error)
    }
}
