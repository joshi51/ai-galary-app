package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table holding durable clustering-pipeline progress, mirroring [EmbeddingStatusEntity]. */
@Entity(tableName = "clustering_status")
data class ClusteringStatusEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val state: String,
    val itemsProcessed: Int,
    val itemsTotal: Int,
    val lastRunAtMs: Long,
    val lastError: String?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
