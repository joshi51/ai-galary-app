package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parses the grammar-constrained JSON `:llm:runtime` produces into a [ToolCall]. Grammar
 * constraints guarantee syntax, not tool-name/value correctness — both are still checked here. */
object ToolCallParser {

    fun parse(rawJson: String): AppResult<ToolCall> = try {
        val root = (Json.parseToJsonElement(rawJson) as? JsonObject)
            ?: return AppResult.Failure(AppError.Validation("Tool-call output was not a JSON object."))

        val toolId = root["tool"]?.jsonPrimitive?.content
            ?: return AppResult.Failure(AppError.Validation("Tool-call output had no \"tool\" field."))
        val tool = ToolName.fromId(toolId)
            ?: return AppResult.Failure(AppError.Validation("Unknown tool \"$toolId\"."))

        val params = (root["params"] as? JsonObject) ?: JsonObject(emptyMap())

        AppResult.Success(
            ToolCall(
                tool = tool,
                people = (params["people"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                startDate = params["startDate"]?.jsonPrimitive?.content,
                endDate = params["endDate"]?.jsonPrimitive?.content,
                location = params["location"]?.jsonPrimitive?.content,
                sortBy = params["sortBy"]?.jsonPrimitive?.content,
                photoId = params["photoId"]?.jsonPrimitive?.longOrNull,
                category = params["category"]?.jsonPrimitive?.content,
                dateHint = params["dateHint"]?.jsonPrimitive?.content,
                nameHint = params["nameHint"]?.jsonPrimitive?.content,
            ),
        )
    } catch (e: Exception) {
        AppResult.Failure(AppError.Validation("Couldn't parse tool-call output: ${e.message}"))
    }
}
