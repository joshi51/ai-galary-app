package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult

data class UndoBatchResult(val batchId: Long, val results: List<UndoResult>)

/**
 * "Undo last organization" — reverses every successful, reversible, not-already-undone operation
 * from the most recent execution batch. Only ever acts on [OperationRecord.result] ==
 * [OperationRecordResult.SUCCESS] rows: a record for a failed operation means nothing actually
 * happened on disk, so there is nothing to reverse. A record already marked [OperationRecord.undone]
 * is skipped so calling undo twice on the same batch can't double-reverse anything. Marks each
 * successfully-reversed record undone individually — a partial undo failure (e.g. one MOVE's
 * destination was itself since deleted) never blocks the others or gets silently swallowed.
 */
class UndoLastOrganizationUseCase(
    private val operationHistoryRepository: OperationHistoryRepository,
    private val operationUndoExecutor: OperationUndoExecutor,
) {
    suspend fun undoLast(): AppResult<UndoBatchResult> {
        val batchId = operationHistoryRepository.fetchLatestUndoableBatchId()
            ?: return AppResult.Failure(AppError.NotFound("No organization history to undo"))

        val records = operationHistoryRepository.fetchBatch(batchId)
        val undoable = records.filter {
            it.result == OperationRecordResult.SUCCESS && it.reversible && !it.undone
        }
        if (undoable.isEmpty()) {
            return AppResult.Failure(AppError.Validation("Nothing left to undo in the last organization"))
        }

        val results = undoable.map { record -> operationUndoExecutor.undo(record) }
        val undoneIds = results.filter { it.success }.map { it.recordId }
        if (undoneIds.isNotEmpty()) {
            operationHistoryRepository.markUndone(undoneIds)
        }
        return AppResult.Success(UndoBatchResult(batchId, results))
    }
}
