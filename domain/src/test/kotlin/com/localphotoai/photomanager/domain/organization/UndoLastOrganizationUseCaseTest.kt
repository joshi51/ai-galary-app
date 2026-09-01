package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOperationHistoryRepository(
    private var batches: MutableMap<Long, List<OperationRecord>>,
    private var latestUndoableBatchId: Long?,
) : OperationHistoryRepository {
    val markedUndone = mutableListOf<Long>()

    override suspend fun recordBatch(records: List<OperationRecord>): List<OperationRecord> = records
    override suspend fun fetchLatestUndoableBatchId(): Long? = latestUndoableBatchId
    override suspend fun fetchBatch(batchId: Long): List<OperationRecord> = batches[batchId].orEmpty()
    override fun observeHistory(): Flow<List<OperationRecord>> = emptyFlow()
    override suspend fun markUndone(recordIds: List<Long>) {
        markedUndone += recordIds
    }
}

private class FakeOperationUndoExecutor(private val failFor: Set<Long> = emptySet()) : OperationUndoExecutor {
    val undone = mutableListOf<Long>()
    override suspend fun undo(record: OperationRecord): UndoResult {
        undone += record.id
        return if (record.id in failFor) {
            UndoResult(record.id, success = false, error = "boom")
        } else {
            UndoResult(record.id, success = true)
        }
    }
}

private fun testRecord(
    id: Long,
    result: OperationRecordResult = OperationRecordResult.SUCCESS,
    reversible: Boolean = true,
    undone: Boolean = false,
) = OperationRecord(
    id = id, batchId = 1L, planId = 1L, operationId = id, timestampMs = 1L,
    opType = OperationType.MOVE, source = "content://$id", destination = "dest/$id",
    previousState = null, result = result, failureReason = null, reversible = reversible, undone = undone,
)

class UndoLastOrganizationUseCaseTest {

    @Test
    fun `no history to undo returns a failure`() = runBlocking {
        val useCase = UndoLastOrganizationUseCase(
            FakeOperationHistoryRepository(mutableMapOf(), latestUndoableBatchId = null),
            FakeOperationUndoExecutor(),
        )
        val result = useCase.undoLast()
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `only successful reversible not-already-undone records are reversed`() = runBlocking {
        val batch = listOf(
            testRecord(1, result = OperationRecordResult.SUCCESS, reversible = true),
            testRecord(2, result = OperationRecordResult.FAILURE, reversible = true),
            testRecord(3, result = OperationRecordResult.SUCCESS, reversible = false),
            testRecord(4, result = OperationRecordResult.SUCCESS, reversible = true, undone = true),
        )
        val historyRepository = FakeOperationHistoryRepository(mutableMapOf(1L to batch), latestUndoableBatchId = 1L)
        val executor = FakeOperationUndoExecutor()
        val useCase = UndoLastOrganizationUseCase(historyRepository, executor)

        val result = useCase.undoLast()

        assertTrue(result is AppResult.Success)
        assertEquals(listOf(1L), executor.undone)
        assertEquals(listOf(1L), historyRepository.markedUndone)
    }

    @Test
    fun `a partial undo failure still marks the successful ones undone`() = runBlocking {
        val batch = listOf(
            testRecord(1, result = OperationRecordResult.SUCCESS, reversible = true),
            testRecord(2, result = OperationRecordResult.SUCCESS, reversible = true),
        )
        val historyRepository = FakeOperationHistoryRepository(mutableMapOf(1L to batch), latestUndoableBatchId = 1L)
        val executor = FakeOperationUndoExecutor(failFor = setOf(2L))
        val useCase = UndoLastOrganizationUseCase(historyRepository, executor)

        val result = useCase.undoLast()

        assertTrue(result is AppResult.Success)
        val undoBatchResult = (result as AppResult.Success).value
        assertEquals(1, undoBatchResult.results.count { it.success })
        assertEquals(1, undoBatchResult.results.count { !it.success })
        assertEquals(listOf(1L), historyRepository.markedUndone)
    }

    @Test
    fun `a batch with nothing left to undo returns a failure`() = runBlocking {
        val batch = listOf(testRecord(1, result = OperationRecordResult.SUCCESS, reversible = true, undone = true))
        val historyRepository = FakeOperationHistoryRepository(mutableMapOf(1L to batch), latestUndoableBatchId = 1L)
        val useCase = UndoLastOrganizationUseCase(historyRepository, FakeOperationUndoExecutor())

        val result = useCase.undoLast()

        assertTrue(result is AppResult.Failure)
    }
}
