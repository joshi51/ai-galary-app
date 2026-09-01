package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.domain.tool.ToolCall

/** Structured query -> intent -> validation -> tool-result -> response tracing, per the Phase 8
 * spec §7 — counts/ids only in tool-result logs, never filenames/paths/coordinates. */
interface TraceLogger {
    fun logQuery(query: String)
    fun logIntent(call: ToolCall)
    fun logValidation(ok: Boolean, error: String?)
    fun logToolResult(toolName: String, resultCount: Int, durationMs: Long)
    fun logResponse(message: String, totalLatencyMs: Long)
}
