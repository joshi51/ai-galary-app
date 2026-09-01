package com.localphotoai.photomanager.domain.face

/**
 * On-device face detection. Implemented in `:ml:vision` on top of ML Kit — kept behind an
 * interface so `:domain` has no ML Kit/Android dependency. Never sends image data anywhere;
 * detection runs entirely on-device.
 */
interface FaceDetector {

    /**
     * Detects faces in the photo at [photoUri]. [sourceWidthPx]/[sourceHeightPx] are the
     * photo's known dimensions (from indexed metadata), used to bound decode memory without a
     * second "just measure" pass. [orientationDegrees] is the photo's EXIF orientation, passed
     * through to the detector so rotated photos are detected correctly.
     *
     * Throws on decode/detection failure (corrupted or unreadable image) — callers are expected
     * to catch per-photo so one bad image never aborts a batch.
     */
    suspend fun detectFaces(
        photoUri: String,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        orientationDegrees: Int,
    ): List<DetectedFace>
}
