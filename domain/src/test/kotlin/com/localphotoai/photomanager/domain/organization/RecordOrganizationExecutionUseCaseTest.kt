package com.localphotoai.photomanager.domain.organization

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingFakeOperationHistoryRepository : OperationHistoryRepository {
    val recorded = mutableListOf<OperationRecord>()
    override suspend fun recordBatch(records: List<OperationRecord>): List<OperationRecord> {
        recorded += records
        return records
    }
    override suspend fun fetchLatestUndoableBatchId(): Long? = null
    override suspend fun fetchBatch(batchId: Long): List<OperationRecord> = emptyList()
    override fun observeHistory(): Flow<List<OperationRecord>> = emptyFlow()
    override suspend fun markUndone(recordIds: List<Long>) {}
}

private fun testPlan(operations: List<OrganizationOperation>) =
    OrganizationPlan(id = 5L, requestText = "x", category = OrganizationCategory.SCREENSHOTS, createdAtMs = 1L, operations = operations)

private fun testOperation(id: Long, opType: OperationType = OperationType.MOVE) =
    OrganizationOperation(id = id, opType = opType, source = "content://$id", destination = "dest/$id", reason = "r", confidence = 1.0f)

class RecordOrganizationExecutionUseCaseTest {

    @Test
    fun `a batch of 20 with 2 failures records 18 successes and 2 failures, never a blanket success`() = runBlocking {
        val repository = RecordingFakeOperationHistoryRepository()
        val useCase = RecordOrganizationExecutionUseCase(repository)
        val operations = (1L..20L).map { testOperation(it) }
        val outcomes = operations.map {
            OperationExecutionOutcome(
                operationId = it.id,
                success = it.id !in setOf(5L, 17L),
                error = if (it.id in setOf(5L, 17L)) "failed" else null,
                reversible = true,
            )
        }

        useCase.record(testPlan(operations), outcomes)

        assertEquals(20, repository.recorded.size)
        assertEquals(18, repository.recorded.count { it.result == OperationRecordResult.SUCCESS })
        assertEquals(2, repository.recorded.count { it.result == OperationRecordResult.FAILURE })
    }

    @Test
    fun `a failed operation is never marked reversible, even if the outcome said so`() = runBlocking {
        val repository = RecordingFakeOperationHistoryRepository()
        val useCase = RecordOrganizationExecutionUseCase(repository)
        val operation = testOperation(1)
        val outcome = OperationExecutionOutcome(operationId = 1, success = false, error = "boom", reversible = true)

        useCase.record(testPlan(listOf(operation)), listOf(outcome))

        val record = repository.recorded.single()
        assertEquals(OperationRecordResult.FAILURE, record.result)
        assertTrue(!record.reversible)
        assertEquals("boom", record.failureReason)
    }

    @Test
    fun `every record in one batch shares the plan id as its batch id`() = runBlocking {
        val repository = RecordingFakeOperationHistoryRepository()
        val useCase = RecordOrganizationExecutionUseCase(repository)
        val operations = listOf(testOperation(1), testOperation(2))
        val outcomes = operations.map { OperationExecutionOutcome(operationId = it.id, success = true) }

        useCase.record(testPlan(operations), outcomes)

        assertTrue(repository.recorded.all { it.batchId == 5L && it.planId == 5L })
    }

    @Test
    fun `an outcome for an operation not in the plan is silently dropped`() = runBlocking {
        val repository = RecordingFakeOperationHistoryRepository()
        val useCase = RecordOrganizationExecutionUseCase(repository)
        val operations = listOf(testOperation(1))
        val outcomes = listOf(OperationExecutionOutcome(operationId = 999L, success = true))

        useCase.record(testPlan(operations), outcomes)

        assertTrue(repository.recorded.isEmpty())
        assertNull(repository.recorded.firstOrNull())
    }
}
