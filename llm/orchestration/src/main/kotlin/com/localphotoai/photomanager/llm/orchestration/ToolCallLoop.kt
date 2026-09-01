package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import com.localphotoai.photomanager.tools.ToolRegistry

sealed class SearchOutcome {
    data class Answered(val outcome: ToolOutcome) : SearchOutcome()
    object Misunderstood : SearchOutcome()
}

private val SYSTEM_PROMPT = """
    You are a photo search and organization assistant. Given the user's request, respond with
    exactly one JSON tool call matching the grammar.

    Use search_photos (params: people, startDate, endDate, location, sortBy) only when the user
    wants to SEE/FIND specific photos, e.g. "show me photos of Rahul" or "find my largest photos".

    Use build_organization_plan (params: category [SCREENSHOTS|BY_DATE|TRIP|ARCHIVE], dateHint,
    nameHint) whenever the user wants to ORGANIZE, TIDY, MOVE, SORT, ARCHIVE, or ALBUM their
    photos — this never shows photos directly, it proposes a plan for the user to review first.
    Examples:
    User: Organize my photos
    {"tool":"build_organization_plan","params":{"category":"BY_DATE"}}
    User: Organize my screenshots
    {"tool":"build_organization_plan","params":{"category":"SCREENSHOTS"}}
    User: Put photos from my Goa trip into an album
    {"tool":"build_organization_plan","params":{"category":"TRIP","nameHint":"Goa Trip"}}
    User: Find photos that should be archived
    {"tool":"build_organization_plan","params":{"category":"ARCHIVE"}}

    Other tools: find_duplicates (no params), find_similar_photos (no params), get_photo_metadata
    (params: photoId), get_storage_statistics (no params).
""".trimIndent()

class ToolCallLoop(
    private val engine: LlmEngine,
    private val toolRegistry: ToolRegistry,
    private val traceLogger: TraceLogger,
) {
    suspend fun run(query: String): SearchOutcome {
        val startedAt = System.currentTimeMillis()
        traceLogger.logQuery(query)

        val grammar = GrammarBuilder.build()
        var prompt = "$SYSTEM_PROMPT\n\nUser: $query"

        repeat(2) { attempt ->
            val raw = engine.generate(prompt, grammar)
            when (val parsed = ToolCallParser.parse(raw)) {
                is AppResult.Success -> {
                    traceLogger.logIntent(parsed.value)
                    traceLogger.logValidation(true, null)
                    val toolStartedAt = System.currentTimeMillis()
                    val outcome = toolRegistry.dispatch(parsed.value)
                    val toolDurationMs = System.currentTimeMillis() - toolStartedAt

                    if (outcome is ToolOutcome.Error) {
                        traceLogger.logValidation(false, outcome.message)
                        if (attempt == 0) {
                            prompt = "$SYSTEM_PROMPT\n\nUser: $query\nYour last response was invalid: " +
                                "${outcome.message} Try again."
                            return@repeat
                        }
                        traceLogger.logResponse(outcome.message, System.currentTimeMillis() - startedAt)
                        return SearchOutcome.Misunderstood
                    }

                    val resultCount = when (outcome) {
                        is ToolOutcome.Photos -> outcome.photos.size
                        is ToolOutcome.Metadata -> 1
                        is ToolOutcome.Statistics -> 1
                        is ToolOutcome.Plan -> 1
                        is ToolOutcome.Error -> 0
                    }
                    traceLogger.logToolResult(parsed.value.tool.id, resultCount, toolDurationMs)

                    val message = when (outcome) {
                        is ToolOutcome.Photos -> outcome.message
                        is ToolOutcome.Metadata -> outcome.message
                        is ToolOutcome.Statistics -> outcome.message
                        is ToolOutcome.Plan -> outcome.message
                        is ToolOutcome.Error -> outcome.message
                    }
                    traceLogger.logResponse(message, System.currentTimeMillis() - startedAt)
                    return SearchOutcome.Answered(outcome)
                }
                is AppResult.Failure -> {
                    traceLogger.logValidation(false, parsed.error.message)
                    if (attempt == 0) {
                        prompt = "$SYSTEM_PROMPT\n\nUser: $query\nYour last response was invalid: " +
                            "${parsed.error.message} Reply with valid JSON matching the grammar."
                        return@repeat
                    }
                }
            }
        }

        traceLogger.logResponse("Couldn't understand that — try the filters above.", System.currentTimeMillis() - startedAt)
        return SearchOutcome.Misunderstood
    }
}
