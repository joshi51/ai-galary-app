package com.localphotoai.photomanager.domain.face

/**
 * Schedules background face-detection work. Implemented in `:data:media` on top of WorkManager.
 */
interface FaceDetectionScheduler {

    /** One-time run over any photos still pending detection, e.g. right after indexing completes. */
    fun scheduleImmediateDetection()

    /** Periodic reconciliation safety net, in case an immediate trigger was ever missed. */
    fun scheduleIncrementalDetection()
}
