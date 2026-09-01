package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class GetPhotoMetadataTool(
    private val getPhotoMetadataUseCase: GetPhotoMetadataUseCase,
) : Tool {
    override val name = ToolName.GET_PHOTO_METADATA

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val photoId = when (val r = ToolValidator.requirePhotoId(call.photoId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        return when (val result = getPhotoMetadataUseCase(photoId)) {
            is AppResult.Success -> ToolOutcome.Metadata(result.value, "Found photo ${result.value.filename}.")
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }
}
