package com.localphotoai.photomanager.domain.tool

import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.statistics.StorageStatistics

enum class ToolName(val id: String) {
    SEARCH_PHOTOS("search_photos"),
    FIND_DUPLICATES("find_duplicates"),
    FIND_SIMILAR_PHOTOS("find_similar_photos"),
    GET_PHOTO_METADATA("get_photo_metadata"),
    GET_STORAGE_STATISTICS("get_storage_statistics"),
    BUILD_ORGANIZATION_PLAN("build_organization_plan"),
    ;

    companion object {
        fun fromId(id: String): ToolName? = entries.find { it.id == id }
    }
}

/** A parsed, not-yet-validated tool invocation — every field beyond [tool] is optional because
 * the flat shape covers every tool uniformly (the grammar in `:llm:orchestration` only emits
 * fields relevant to the chosen [tool]; unused fields are simply absent/null). */
data class ToolCall(
    val tool: ToolName,
    val people: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val sortBy: String? = null,
    val photoId: Long? = null,
    val category: String? = null,
    val dateHint: String? = null,
    val nameHint: String? = null,
)

sealed class ToolOutcome {
    data class Photos(val photos: List<Photo>, val message: String) : ToolOutcome()
    data class Metadata(val photo: Photo, val message: String) : ToolOutcome()
    data class Statistics(val statistics: StorageStatistics, val message: String) : ToolOutcome()
    data class Plan(val plan: OrganizationPlan, val message: String) : ToolOutcome()
    data class Error(val message: String) : ToolOutcome()
}
