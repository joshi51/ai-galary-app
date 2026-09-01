package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

private const val SEARCH_RESULT_LIMIT = 200

/** Plain constructor, not `@Inject` — `:tools` is a plain-Kotlin module with no Hilt plugin
 * applied (matching `:domain`'s existing convention); `:llm:runtime`'s `RuntimeModule` (Task 9)
 * wires this via `@Provides`. */
class SearchPhotosTool(
    private val searchPhotosUseCase: SearchPhotosUseCase,
    private val personRepository: PersonRepository,
) : Tool {
    override val name = ToolName.SEARCH_PHOTOS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val personIds = mutableSetOf<Long>()
        if (call.people.isNotEmpty()) {
            val people = personRepository.observePeopleWithStats().first()
            for (queryName in call.people) {
                val match = people.firstOrNull { it.name?.equals(queryName, ignoreCase = true) == true }
                    ?: return ToolOutcome.Error("No person found matching \"$queryName\".")
                personIds += match.id
            }
        }

        val startDateMs = when (val r = ToolValidator.parseIsoDate(call.startDate)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        val endDateMs = when (val r = ToolValidator.parseIsoDate(call.endDate)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        val sortOrder = when (val r = ToolValidator.parseSortOrder(call.sortBy)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }

        // call.location is intentionally not consumed yet — free-text location resolution
        // against a saved point requires SettingsRepository, deliberately left out of :tools'
        // dependency set for this phase (see the Phase 8 design spec §9's scope cut). A
        // location value is accepted but has no effect, so the LLM isn't forced into a retry
        // loop over a field this phase doesn't act on.

        val filter = PhotoSearchFilter(
            personIds = personIds,
            startDateMs = startDateMs,
            endDateMs = endDateMs,
            sortBy = sortOrder,
        )

        return when (val result = searchPhotosUseCase.searchOnce(filter, limit = SEARCH_RESULT_LIMIT)) {
            is AppResult.Success -> ToolOutcome.Photos(result.value, buildMessage(result.value.size, call))
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }

    private fun buildMessage(count: Int, call: ToolCall): String {
        val who = if (call.people.isNotEmpty()) " of ${call.people.joinToString(" and ")}" else ""
        return if (count == 0) "No photos found$who." else "Found $count photo${if (count == 1) "" else "s"}$who."
    }
}
