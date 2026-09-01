package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

class FindSimilarPhotosTool(
    private val photoGroupRepository: PhotoGroupRepository,
    private val photoRepository: PhotoRepository,
) : Tool {
    override val name = ToolName.FIND_SIMILAR_PHOTOS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val groups = photoGroupRepository.observeSimilarGroups(SimilarGroupKind.VISUALLY_SIMILAR).first()
        val photoIds = groups.flatMap { it.photoIds }.distinct()
        val photos = photoRepository.fetchByIds(photoIds)
        val message = if (groups.isEmpty()) {
            "No visually similar photo groups found."
        } else {
            "Found ${groups.size} visually similar group${if (groups.size == 1) "" else "s"} (${photos.size} photos)."
        }
        return ToolOutcome.Photos(photos, message)
    }
}
