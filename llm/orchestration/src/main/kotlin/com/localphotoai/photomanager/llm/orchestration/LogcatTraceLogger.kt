package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.tool.ToolCall

private const val TAG = "LlmTrace"

/** Plain constructor, not `@Inject` — see the note on `SearchPhotosTool` in `:tools`; wired via
 * `@Provides`/`@Binds` in `:llm:runtime`'s `RuntimeModule`. */
class LogcatTraceLogger(private val logger: Logger) : TraceLogger {
    override fun logQuery(query: String) = logger.debug(TAG, "query=\"$query\"")
    override fun logIntent(call: ToolCall) = logger.debug(
        TAG,
        "intent=${call.tool.id} params={people=${call.people}, startDate=${call.startDate}, " +
            "endDate=${call.endDate}, sortBy=${call.sortBy}, photoId=${call.photoId}}",
    )
    override fun logValidation(ok: Boolean, error: String?) =
        logger.debug(TAG, if (ok) "validation=OK" else "validation=FAILED error=$error")
    override fun logToolResult(toolName: String, resultCount: Int, durationMs: Long) =
        logger.debug(TAG, "tool_result tool=$toolName count=$resultCount durationMs=$durationMs")
    override fun logResponse(message: String, totalLatencyMs: Long) =
        logger.debug(TAG, "response=\"$message\" totalLatencyMs=$totalLatencyMs")
}
