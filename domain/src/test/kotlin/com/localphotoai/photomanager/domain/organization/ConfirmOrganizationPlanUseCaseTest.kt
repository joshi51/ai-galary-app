package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ConfirmFakeOrganizationPlanRepository(private var plan: OrganizationPlan?) : OrganizationPlanRepository {
    val updated = mutableListOf<OrganizationOperation>()
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan = plan
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = plan
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = flowOf(plan)
    override suspend fun updateOperation(operation: OrganizationOperation) {
        updated += operation
        plan = plan?.copy(operations = plan!!.operations.map { if (it.id == operation.id) operation else it })
    }
}

private class ConfirmFakeAlbumRepository : AlbumRepository {
    var created: Pair<String, List<Long>>? = null
    override suspend fun createAlbum(name: String, photoIds: List<Long>): Long {
        created = name to photoIds
        return 42L
    }
}

private fun testOperation(id: Long, opType: OperationType = OperationType.MOVE, memberPhotoIds: List<Long> = emptyList()) =
    OrganizationOperation(id = id, opType = opType, source = "content://$id", destination = "dest/$id", reason = "r", confidence = 1.0f, memberPhotoIds = memberPhotoIds)

class ConfirmOrganizationPlanUseCaseTest {

    @Test
    fun `rejecting an operation just updates its status, no album is created`() = runBlocking {
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.SCREENSHOTS, createdAtMs = 1L, operations = listOf(testOperation(10)))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val albumRepository = ConfirmFakeAlbumRepository()
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, albumRepository)

        val result = useCase.confirm(1L, listOf(OperationDecision(operationId = 10, status = ReviewStatus.REJECTED)))

        assertTrue(result is AppResult.Success)
        assertEquals(ReviewStatus.REJECTED, planRepository.updated.single().reviewStatus)
        assertEquals(null, albumRepository.created)
    }

    @Test
    fun `approving a CREATE_ALBUM operation creates the album with the confirmed members`() = runBlocking {
        val op = testOperation(20, opType = OperationType.CREATE_ALBUM, memberPhotoIds = listOf(1L, 2L, 3L)).copy(destination = "Goa Trip")
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.TRIP, createdAtMs = 1L, operations = listOf(op))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val albumRepository = ConfirmFakeAlbumRepository()
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, albumRepository)

        val result = useCase.confirm(
            1L,
            listOf(OperationDecision(operationId = 20, status = ReviewStatus.APPROVED, editedMemberPhotoIds = listOf(1L, 3L))),
        )

        assertTrue(result is AppResult.Success)
        assertEquals("Goa Trip" to listOf(1L, 3L), albumRepository.created)
    }

    @Test
    fun `an edited destination is applied before the operation is marked EDITED`() = runBlocking {
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.SCREENSHOTS, createdAtMs = 1L, operations = listOf(testOperation(10)))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, ConfirmFakeAlbumRepository())

        useCase.confirm(1L, listOf(OperationDecision(operationId = 10, status = ReviewStatus.EDITED, editedDestination = "new/dest.png")))

        val updated = planRepository.updated.single()
        assertEquals("new/dest.png", updated.destination)
        assertEquals(ReviewStatus.EDITED, updated.reviewStatus)
    }

    @Test
    fun `returns a failure when the plan doesn't exist`() = runBlocking {
        val useCase = ConfirmOrganizationPlanUseCase(ConfirmFakeOrganizationPlanRepository(null), ConfirmFakeAlbumRepository())
        val result = useCase.confirm(999L, emptyList())
        assertTrue(result is AppResult.Failure)
    }
}
