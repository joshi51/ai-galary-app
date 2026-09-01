package com.localphotoai.photomanager.domain.organization

import kotlinx.coroutines.flow.Flow

enum class OperationRecordResult {
    SUCCESS, FAILURE
}

/**
 * Whatever was true before an operation ran, captured only where undo needs it to reverse the
 * operation. A `null` field means "nothing to restore" — e.g. a brand-new file/album has no prior
 * state, undo means removing it, not restoring something. [createdUri] is the MediaStore URI of a
 * newly-created row (`COPY`); [createdAlbumId] is the id of a newly-created virtual album
 * (`CREATE_ALBUM`); the two are mutually exclusive with the display-name/relative-path pair, which
 * exists only for `MOVE`/`RENAME` (whose original location a photo actually had before the op).
 */
data class OperationPreviousState(
    val previousDisplayName: String? = null,
    val previousRelativePath: String? = null,
    val createdUri: String? = null,
    val createdAlbumId: Long? = null,
)

/**
 * A durable, permanent record of one executed (or attempted) organization operation — written
 * once, right after execution, and never edited afterward (only [undone] flips true/false).
 * [batchId] groups every record from one "Execute" pass of one [OrganizationPlan] (== [planId]),
 * which is the unit "Undo last organization" operates on.
 */
data class OperationRecord(
    val id: Long = 0,
    val batchId: Long,
    val planId: Long,
    val operationId: Long,
    val timestampMs: Long,
    val opType: OperationType,
    val source: String?,
    val destination: String,
    val previousState: OperationPreviousState?,
    val result: OperationRecordResult,
    val failureReason: String?,
    val reversible: Boolean,
    val undone: Boolean = false,
)

/** Access to the permanent operation-history log. Implemented in `:data:database` (Room only). */
interface OperationHistoryRepository {
    suspend fun recordBatch(records: List<OperationRecord>): List<OperationRecord>
    suspend fun fetchLatestUndoableBatchId(): Long?
    suspend fun fetchBatch(batchId: Long): List<OperationRecord>
    fun observeHistory(): Flow<List<OperationRecord>>
    suspend fun markUndone(recordIds: List<Long>)
}

data class UndoResult(val recordId: Long, val success: Boolean, val error: String? = null)

/**
 * Reverses one already-executed [OperationRecord]. Implemented in `:fsops` — the only module with
 * real filesystem/MediaStore write access, mirroring [OrganizationPlanRepository]'s split between
 * this domain-owned interface and its Android-dependent implementation.
 */
interface OperationUndoExecutor {
    suspend fun undo(record: OperationRecord): UndoResult
}
