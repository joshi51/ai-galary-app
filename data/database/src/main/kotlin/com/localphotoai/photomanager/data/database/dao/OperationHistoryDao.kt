package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.localphotoai.photomanager.data.database.entity.OperationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationHistoryDao {
    @Insert
    suspend fun insertRecords(records: List<OperationRecordEntity>): List<Long>

    /** The most recent batch that has at least one reversible, not-yet-undone success — i.e. the
     * batch "Undo last organization" would act on. Batches that were entirely failures, entirely
     * non-reversible (e.g. all CREATE_FOLDER), or already fully undone are skipped so undo always
     * targets the most recent organization that actually has something left to reverse. */
    @Query(
        """
        SELECT batchId FROM operation_records
        WHERE result = 'SUCCESS' AND reversible = 1 AND undone = 0
        ORDER BY timestampMs DESC LIMIT 1
        """,
    )
    suspend fun fetchLatestUndoableBatchId(): Long?

    @Query("SELECT * FROM operation_records WHERE batchId = :batchId ORDER BY id ASC")
    suspend fun fetchBatch(batchId: Long): List<OperationRecordEntity>

    @Query("SELECT * FROM operation_records ORDER BY timestampMs DESC, id DESC")
    fun observeHistory(): Flow<List<OperationRecordEntity>>

    @Query("UPDATE operation_records SET undone = 1 WHERE id IN (:recordIds)")
    suspend fun markUndone(recordIds: List<Long>)
}
