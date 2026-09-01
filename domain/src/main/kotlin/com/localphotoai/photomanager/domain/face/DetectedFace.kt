package com.localphotoai.photomanager.domain.face

/**
 * Raw output of one face-detection pass over a photo, before it's attached to a photo id and
 * persisted. Bounding box coordinates are normalized to `[0,1]` relative to the bitmap the
 * detector actually ran on, so they're resolution-independent regardless of any downsampling
 * applied before detection.
 *
 * ML Kit's on-device face detector does not expose a raw detection-confidence score (unlike
 * object-detection APIs) — only per-attribute probabilities (smiling, eyes open, etc.) that
 * don't apply here. [confidence] is fixed at `1.0f` for every detected face until a model that
 * genuinely reports one is introduced; this is a known, documented limitation rather than a bug.
 */
data class DetectedFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)
