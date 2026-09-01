package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

class FindDuplicatesTool(
    private val photoGroupRepository: PhotoGroupRepository,
    private val photoRepository: PhotoRepository,
) : Tool {
    override val name = ToolName.FIND_DUPLICATES

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val groups = photoGroupRepository.observeDuplicateGroups().first()
        val photoIds = groups.flatMap { it.photoIds }.distinct()
        val photos = photoRepository.fetchByIds(photoIds)
        val message = if (groups.isEmpty()) {
            "No duplicate photos found."
        } else {
            "Found ${groups.size} duplicate group${if (groups.size == 1) "" else "s"} (${photos.size} photos)."
        }
        return ToolOutcome.Photos(photos, message)
    }
}
