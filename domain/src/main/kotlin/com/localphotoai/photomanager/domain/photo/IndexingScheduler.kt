package com.localphotoai.photomanager.domain.photo

/**
 * Schedules background indexing work. Implemented in `:data:media` on top of WorkManager —
 * kept behind an interface so `:domain` and `:feature:photos` never depend on WorkManager
 * directly.
 */
interface IndexingScheduler {

    /** One-time expedited run, e.g. right after permission is granted or the user pulls to refresh. */
    fun scheduleImmediateIndex()

    /** Periodic reconciliation safety net, plus starts observing MediaStore changes. */
    fun scheduleIncrementalIndexing()
}
