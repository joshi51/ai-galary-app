package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table (fixed [id]) holding durable indexing progress/state, so the UI can observe
 * it as a Room [kotlinx.coroutines.flow.Flow] independent of whether a WorkManager job is
 * currently alive.
 */
@Entity(tableName = "indexing_status")
data class IndexingStatusEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val state: String,
    val itemsProcessed: Int,
    val itemsTotal: Int,
    val lastRunAtMs: Long,
    val lastError: String?,
    val lastGeneration: Long?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
