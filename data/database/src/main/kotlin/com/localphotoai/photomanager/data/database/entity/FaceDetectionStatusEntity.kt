package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table holding durable face-detection pipeline progress, mirroring [IndexingStatusEntity]. */
@Entity(tableName = "face_detection_status")
data class FaceDetectionStatusEntity(
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
