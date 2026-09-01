package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class BuildOrganizationPlanTool(
    private val buildOrganizationPlanUseCase: BuildOrganizationPlanUseCase,
) : Tool {
    override val name = ToolName.BUILD_ORGANIZATION_PLAN

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val category = when (val r = ToolValidator.parseOrganizationCategory(call.category)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }

        return when (
            val result = buildOrganizationPlanUseCase(
                requestText = call.toString(),
                category = category,
                dateHint = call.dateHint,
                nameHint = call.nameHint,
            )
        ) {
            is AppResult.Success -> ToolOutcome.Plan(
                result.value,
                "Proposed ${result.value.operations.size} operation(s) — review before anything changes.",
            )
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }
}
