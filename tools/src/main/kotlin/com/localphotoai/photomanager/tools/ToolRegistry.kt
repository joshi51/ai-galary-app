package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

interface Tool {
    val name: ToolName
    suspend fun execute(call: ToolCall): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<ToolName, Tool> = tools.associateBy { it.name }

    suspend fun dispatch(call: ToolCall): ToolOutcome =
        byName[call.tool]?.execute(call) ?: ToolOutcome.Error("Unknown tool: ${call.tool}")
}
