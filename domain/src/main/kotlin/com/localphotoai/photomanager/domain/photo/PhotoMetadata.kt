package com.localphotoai.photomanager.domain.photo

/**
 * Full metadata scanned for one photo (MediaStore columns + EXIF), before it is persisted.
 * Produced only for photos the light diff pass ([LightPhotoRecord]) determined are new or
 * changed, so EXIF/file reads never happen for photos that haven't changed.
 */
data class PhotoMetadata(
    val mediaStoreId: Long,
    val uri: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val dateTakenMs: Long?,
    val orientationDegrees: Int,
    val latitude: Double?,
    val longitude: Double?,
    val indexError: String? = null,
    val relativePath: String? = null,
)

/**
 * Cheap-to-fetch identity + change-detection fields for one photo, used purely to diff a
 * MediaStore snapshot against what's already indexed locally — never triggers an EXIF/file read.
 */
data class LightPhotoRecord(
    val mediaStoreId: Long,
    val dateModifiedMs: Long,
)
