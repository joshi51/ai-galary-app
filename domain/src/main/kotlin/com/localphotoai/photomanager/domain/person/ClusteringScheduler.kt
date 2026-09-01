package com.localphotoai.photomanager.domain.person

/** Schedules background clustering work. Implemented in `:data:media` on top of WorkManager. */
interface ClusteringScheduler {

    /** One-time run over any faces still pending clustering, e.g. right after embedding completes. */
    fun scheduleImmediateClustering()

    /** Periodic reconciliation safety net. */
    fun scheduleIncrementalClustering()
}
