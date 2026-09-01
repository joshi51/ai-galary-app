package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import kotlinx.coroutines.flow.first

class BuildOrganizationPlanUseCase(
    private val photoRepository: PhotoRepository,
    private val photoGroupRepository: PhotoGroupRepository,
    private val organizationPlanRepository: OrganizationPlanRepository,
) {
    suspend operator fun invoke(
        requestText: String,
        category: OrganizationCategory,
        dateHint: String?,
        nameHint: String?,
    ): AppResult<OrganizationPlan> {
        val photos = photoRepository.observePhotos().first()

        val operations = when (category) {
            OrganizationCategory.SCREENSHOTS -> ScreenshotOrganizationStrategy.build(photos)
            OrganizationCategory.BY_DATE -> ByDateOrganizationStrategy.build(photos)
            OrganizationCategory.TRIP -> TripOrganizationStrategy.build(photos, dateHint, nameHint)
            OrganizationCategory.ARCHIVE -> {
                val duplicateGroups = photoGroupRepository.observeDuplicateGroups().first()
                ArchiveOrganizationStrategy.build(photos, duplicateGroups, System.currentTimeMillis())
            }
        }

        if (operations.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No photos matched this organization request."))
        }

        val plan = OrganizationPlan(
            requestText = requestText,
            category = category,
            createdAtMs = System.currentTimeMillis(),
            operations = operations,
        )
        return AppResult.Success(organizationPlanRepository.savePlan(plan))
    }
}
