package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult

class GetPhotoMetadataUseCase(
    private val photoRepository: PhotoRepository,
) {
    suspend operator fun invoke(mediaStoreId: Long): AppResult<Photo> {
        val photo = photoRepository.fetchById(mediaStoreId)
            ?: return AppResult.Failure(AppError.NotFound("No photo found with id $mediaStoreId"))
        return AppResult.Success(photo)
    }
}
