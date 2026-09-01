package com.localphotoai.photomanager.domain.photo

/**
 * A photo indexed from the device's media store, along with the metadata extracted for it.
 * [uri] is a reference into MediaStore/the filesystem — the photo bytes themselves are never
 * copied or duplicated into app storage.
 */
data class Photo(
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
    val lastIndexedAtMs: Long,
    val indexError: String?,
    val relativePath: String? = null,
    val facesDetectedAt: Long? = null,
    val faceDetectionError: String? = null,
)
