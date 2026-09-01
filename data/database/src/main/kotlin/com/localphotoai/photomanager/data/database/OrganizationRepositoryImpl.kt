package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.OrganizationDao
import com.localphotoai.photomanager.data.database.entity.OrganizationOperationEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationPlanEntity
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationCategory
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.OrganizationPlanRepository
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class OrganizationRepositoryImpl @Inject constructor(
    private val organizationDao: OrganizationDao,
) : OrganizationPlanRepository {

    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan {
        val planId = organizationDao.insertPlan(
            OrganizationPlanEntity(
                requestText = plan.requestText,
                category = plan.category.name,
                createdAtMs = plan.createdAtMs,
                status = "PROPOSED",
            ),
        )
        val operationIds = organizationDao.insertOperations(plan.operations.map { it.toEntity(planId) })
        val savedOperations = plan.operations.zip(operationIds) { op, id -> op.copy(id = id) }
        return plan.copy(id = planId, operations = savedOperations)
    }

    override suspend fun fetchPlan(planId: Long): OrganizationPlan? {
        val planEntity = organizationDao.getPlan(planId) ?: return null
        val operations = organizationDao.getOperations(planId)
        return planEntity.toDomain(operations)
    }

    override fun observePlan(planId: Long): Flow<OrganizationPlan?> =
        combine(organizationDao.observePlan(planId), organizationDao.observeOperations(planId)) { planEntity, operations ->
            planEntity?.toDomain(operations)
        }

    override suspend fun updateOperation(operation: OrganizationOperation) {
        val planId = organizationDao.getPlanIdForOperation(operation.id) ?: return
        organizationDao.updateOperation(operation.toEntity(planId))
    }
}

private fun OrganizationOperation.toEntity(planId: Long) = OrganizationOperationEntity(
    id = id,
    planId = planId,
    opType = opType.name,
    source = source,
    destination = destination,
    reason = reason,
    confidence = confidence,
    memberPhotoIdsCsv = memberPhotoIds.takeIf { it.isNotEmpty() }?.joinToString(","),
    reviewStatus = reviewStatus.name,
    executionResult = executionResult?.let { if (it) "SUCCESS" else "FAILURE" },
    executionError = executionError,
    createdAlbumId = createdAlbumId,
)

private fun OrganizationPlanEntity.toDomain(operations: List<OrganizationOperationEntity>) = OrganizationPlan(
    id = id,
    requestText = requestText,
    category = OrganizationCategory.valueOf(category),
    createdAtMs = createdAtMs,
    operations = operations.map { it.toDomain() },
)

private fun OrganizationOperationEntity.toDomain() = OrganizationOperation(
    id = id,
    opType = OperationType.valueOf(opType),
    source = source,
    destination = destination,
    reason = reason,
    confidence = confidence,
    memberPhotoIds = memberPhotoIdsCsv?.split(",")?.filter { it.isNotBlank() }?.map { it.toLong() } ?: emptyList(),
    reviewStatus = ReviewStatus.valueOf(reviewStatus),
    executionResult = executionResult?.let { it == "SUCCESS" },
    executionError = executionError,
    createdAlbumId = createdAlbumId,
)
