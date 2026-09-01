package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import com.localphotoai.photomanager.tools.Tool
import com.localphotoai.photomanager.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedEngine(private val responses: List<String>) : LlmEngine {
    var callCount = 0
    override suspend fun generate(prompt: String, grammar: String): String {
        val response = responses[callCount.coerceAtMost(responses.size - 1)]
        callCount++
        return response
    }
}

private class LoopFakeTraceLogger : TraceLogger {
    val events = mutableListOf<String>()
    override fun logQuery(query: String) { events += "query" }
    override fun logIntent(call: ToolCall) { events += "intent" }
    override fun logValidation(ok: Boolean, error: String?) { events += "validation" }
    override fun logToolResult(toolName: String, resultCount: Int, durationMs: Long) { events += "tool_result" }
    override fun logResponse(message: String, totalLatencyMs: Long) { events += "response" }
}

private class LoopFakeStatsTool : Tool {
    override val name = ToolName.GET_STORAGE_STATISTICS
    var callCount = 0
    override suspend fun execute(call: ToolCall): ToolOutcome {
        callCount++
        return ToolOutcome.Statistics(
            com.localphotoai.photomanager.domain.statistics.StorageStatistics(1, 1L, 1, 1, 0, 0),
            "1 photo",
        )
    }
}

class ToolCallLoopTest {

    @Test
    fun `a well-formed response is dispatched on the first try`() = runBlocking {
        val engine = ScriptedEngine(listOf("""{"tool":"get_storage_statistics","params":{}}"""))
        val tool = LoopFakeStatsTool()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), LoopFakeTraceLogger())

        val outcome = loop.run("how many photos do I have")

        assertTrue(outcome is SearchOutcome.Answered)
        assertEquals(1, tool.callCount)
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `malformed output is retried exactly once before falling back`() = runBlocking {
        val engine = ScriptedEngine(listOf("not json", "still not json"))
        val loop = ToolCallLoop(engine, ToolRegistry(emptyList()), LoopFakeTraceLogger())

        val outcome = loop.run("asdf")

        assertTrue(outcome is SearchOutcome.Misunderstood)
        assertEquals(2, engine.callCount)
    }

    @Test
    fun `a corrected response on retry succeeds`() = runBlocking {
        val engine = ScriptedEngine(listOf("not json", """{"tool":"get_storage_statistics","params":{}}"""))
        val tool = LoopFakeStatsTool()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), LoopFakeTraceLogger())

        val outcome = loop.run("how many photos")

        assertTrue(outcome is SearchOutcome.Answered)
        assertEquals(2, engine.callCount)
        assertEquals(1, tool.callCount)
    }

    @Test
    fun `every stage is traced`() = runBlocking {
        val engine = ScriptedEngine(listOf("""{"tool":"get_storage_statistics","params":{}}"""))
        val traceLogger = LoopFakeTraceLogger()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(LoopFakeStatsTool())), traceLogger)

        loop.run("how many photos")

        assertEquals(listOf("query", "intent", "validation", "tool_result", "response"), traceLogger.events)
    }

    @Test
    fun `a Plan outcome is traced the same way as other outcomes`() = runBlocking {
        val plan = com.localphotoai.photomanager.domain.organization.OrganizationPlan(
            id = 1L, requestText = "x",
            category = com.localphotoai.photomanager.domain.organization.OrganizationCategory.SCREENSHOTS,
            createdAtMs = 1L, operations = emptyList(),
        )
        val engine = ScriptedEngine(listOf("""{"tool":"build_organization_plan","params":{"category":"SCREENSHOTS"}}"""))
        val tool = object : Tool {
            override val name = ToolName.BUILD_ORGANIZATION_PLAN
            override suspend fun execute(call: ToolCall): ToolOutcome = ToolOutcome.Plan(plan, "1 operation proposed")
        }
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), LoopFakeTraceLogger())

        val outcome = loop.run("organize my screenshots")

        assertTrue(outcome is SearchOutcome.Answered)
        assertTrue((outcome as SearchOutcome.Answered).outcome is ToolOutcome.Plan)
    }
}
