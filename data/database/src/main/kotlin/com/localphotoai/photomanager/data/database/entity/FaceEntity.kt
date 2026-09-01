package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One detected face within a photo. Bounding box coordinates are normalized `[0,1]` relative to
 * the bitmap the detector ran on. A photo's faces are fully replaced on each re-run of detection
 * (see `FaceDao.replaceFacesForPhoto`) rather than merged, since detection is idempotent given
 * the same photo and model version.
 */
@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["mediaStoreId"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("photoId")],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val rotationDegrees: Int,
    val markedIncorrect: Boolean = false,
    /** Null until embedding generation has run for this face at the current model version. */
    val embeddingVersion: Int? = null,
    val embeddingError: String? = null,
)
