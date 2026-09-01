package com.localphotoai.photomanager.domain.organization

/** What actually happened when one [OrganizationOperation] was executed — the fsops-layer
 * equivalent info, expressed as plain domain data so this use case can turn it into a permanent
 * [OperationRecord] without depending on `:fsops`. */
data class OperationExecutionOutcome(
    val operationId: Long,
    val success: Boolean,
    val error: String? = null,
    val previousState: OperationPreviousState? = null,
    val reversible: Boolean = false,
)

/**
 * Turns one execution pass of an [OrganizationPlan] into a permanent, inspectable history batch —
 * written once, right after execution, so a crash mid-batch never loses the record of what already
 * happened. [OperationExecutionOutcome.success] is taken at face value per-operation: a batch of
 * 20 with 2 failures records 18 [OperationRecordResult.SUCCESS] rows and 2
 * [OperationRecordResult.FAILURE] rows, never a blanket success.
 */
class RecordOrganizationExecutionUseCase(
    private val operationHistoryRepository: OperationHistoryRepository,
) {
    suspend fun record(plan: OrganizationPlan, outcomes: List<OperationExecutionOutcome>): List<OperationRecord> {
        val timestampMs = System.currentTimeMillis()
        val operationsById = plan.operations.associateBy { it.id }
        val records = outcomes.mapNotNull { outcome ->
            val operation = operationsById[outcome.operationId] ?: return@mapNotNull null
            OperationRecord(
                batchId = plan.id,
                planId = plan.id,
                operationId = operation.id,
                timestampMs = timestampMs,
                opType = operation.opType,
                source = operation.source,
                destination = operation.destination,
                previousState = outcome.previousState,
                result = if (outcome.success) OperationRecordResult.SUCCESS else OperationRecordResult.FAILURE,
                failureReason = outcome.error,
                reversible = outcome.success && outcome.reversible,
            )
        }
        return operationHistoryRepository.recordBatch(records)
    }
}
