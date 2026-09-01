package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult

data class OperationDecision(
    val operationId: Long,
    val status: ReviewStatus,
    val editedDestination: String? = null,
    val editedMemberPhotoIds: List<Long>? = null,
)

class ConfirmOrganizationPlanUseCase(
    private val organizationPlanRepository: OrganizationPlanRepository,
    private val albumRepository: AlbumRepository,
) {
    suspend fun confirm(planId: Long, decisions: List<OperationDecision>): AppResult<OrganizationPlan> {
        val plan = organizationPlanRepository.fetchPlan(planId)
            ?: return AppResult.Failure(AppError.NotFound("No organization plan found with id $planId"))

        val decisionsById = decisions.associateBy { it.operationId }
        for (operation in plan.operations) {
            val decision = decisionsById[operation.id] ?: continue
            val updated = operation.copy(
                reviewStatus = decision.status,
                destination = decision.editedDestination ?: operation.destination,
                memberPhotoIds = decision.editedMemberPhotoIds ?: operation.memberPhotoIds,
            )
            organizationPlanRepository.updateOperation(updated)

            if (updated.opType == OperationType.CREATE_ALBUM &&
                updated.reviewStatus in setOf(ReviewStatus.APPROVED, ReviewStatus.EDITED)
            ) {
                albumRepository.createAlbum(updated.destination, updated.memberPhotoIds)
            }
        }

        val refreshed = organizationPlanRepository.fetchPlan(planId)
            ?: return AppResult.Failure(AppError.NotFound("Plan $planId disappeared during confirmation"))
        return AppResult.Success(refreshed)
    }
}
