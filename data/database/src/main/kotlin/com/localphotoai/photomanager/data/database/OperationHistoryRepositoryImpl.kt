package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.OperationHistoryDao
import com.localphotoai.photomanager.data.database.entity.OperationRecordEntity
import com.localphotoai.photomanager.domain.organization.OperationHistoryRepository
import com.localphotoai.photomanager.domain.organization.OperationPreviousState
import com.localphotoai.photomanager.domain.organization.OperationRecord
import com.localphotoai.photomanager.domain.organization.OperationRecordResult
import com.localphotoai.photomanager.domain.organization.OperationType
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OperationHistoryRepositoryImpl @Inject constructor(
    private val operationHistoryDao: OperationHistoryDao,
) : OperationHistoryRepository {

    override suspend fun recordBatch(records: List<OperationRecord>): List<OperationRecord> {
        val ids = operationHistoryDao.insertRecords(records.map { it.toEntity() })
        return records.zip(ids) { record, id -> record.copy(id = id) }
    }

    override suspend fun fetchLatestUndoableBatchId(): Long? = operationHistoryDao.fetchLatestUndoableBatchId()

    override suspend fun fetchBatch(batchId: Long): List<OperationRecord> =
        operationHistoryDao.fetchBatch(batchId).map { it.toDomain() }

    override fun observeHistory(): Flow<List<OperationRecord>> =
        operationHistoryDao.observeHistory().map { entities -> entities.map { it.toDomain() } }

    override suspend fun markUndone(recordIds: List<Long>) {
        if (recordIds.isNotEmpty()) operationHistoryDao.markUndone(recordIds)
    }
}

private fun OperationRecord.toEntity() = OperationRecordEntity(
    id = id,
    batchId = batchId,
    planId = planId,
    operationId = operationId,
    timestampMs = timestampMs,
    opType = opType.name,
    source = source,
    destination = destination,
    previousDisplayName = previousState?.previousDisplayName,
    previousRelativePath = previousState?.previousRelativePath,
    createdUri = previousState?.createdUri,
    createdAlbumId = previousState?.createdAlbumId,
    result = result.name,
    failureReason = failureReason,
    reversible = reversible,
    undone = undone,
)

private fun OperationRecordEntity.toDomain(): OperationRecord {
    val previousState = if (previousDisplayName != null || previousRelativePath != null || createdUri != null || createdAlbumId != null) {
        OperationPreviousState(
            previousDisplayName = previousDisplayName,
            previousRelativePath = previousRelativePath,
            createdUri = createdUri,
            createdAlbumId = createdAlbumId,
        )
    } else {
        null
    }
    return OperationRecord(
        id = id,
        batchId = batchId,
        planId = planId,
        operationId = operationId,
        timestampMs = timestampMs,
        opType = OperationType.valueOf(opType),
        source = source,
        destination = destination,
        previousState = previousState,
        result = OperationRecordResult.valueOf(result),
        failureReason = failureReason,
        reversible = reversible,
        undone = undone,
    )
}
