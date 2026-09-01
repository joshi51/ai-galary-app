package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A permanent, append-only history row for one executed organization operation — never updated
 * after insert except [undone] flipping to true. Deliberately has no foreign key to
 * `organization_plans`/`organization_operations`: history must survive even if the originating
 * plan is later deleted, since it is the durable audit trail Phase 10 requires.
 */
@Entity(tableName = "operation_records", indices = [Index("batchId")])
data class OperationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val planId: Long,
    val operationId: Long,
    val timestampMs: Long,
    val opType: String,
    val source: String?,
    val destination: String,
    val previousDisplayName: String?,
    val previousRelativePath: String?,
    val createdUri: String?,
    val createdAlbumId: Long?,
    val result: String,
    val failureReason: String?,
    val reversible: Boolean,
    val undone: Boolean,
)
