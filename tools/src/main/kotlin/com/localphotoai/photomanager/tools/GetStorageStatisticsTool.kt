package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class GetStorageStatisticsTool(
    private val getStorageStatisticsUseCase: GetStorageStatisticsUseCase,
) : Tool {
    override val name = ToolName.GET_STORAGE_STATISTICS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val stats = getStorageStatisticsUseCase()
        val message = "${stats.photoCount} photos, ${stats.peopleCount} people, " +
            "${stats.duplicateGroupCount} duplicate group(s), ${stats.similarGroupCount} similar group(s)."
        return ToolOutcome.Statistics(stats, message)
    }
}
