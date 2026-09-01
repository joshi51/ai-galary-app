package com.localphotoai.photomanager.domain.face

/**
 * A persisted face detection result for one photo. Bounding box coordinates are normalized
 * `[0,1]` relative to the bitmap the detector ran on. [rotationDegrees] records the source
 * photo's EXIF orientation at detection time, for audit/debugging — the box coordinates
 * themselves are in the detector's own (pre-rotation) coordinate space.
 */
data class Face(
    val id: Long = 0,
    val photoId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val rotationDegrees: Int,
    val markedIncorrect: Boolean = false,
)
