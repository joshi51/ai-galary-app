package com.localphotoai.photomanager.domain.face

/** Schedules background embedding-generation work. Implemented in `:data:media` on top of WorkManager. */
interface EmbeddingScheduler {

    /** One-time run over any faces still pending embedding, e.g. right after detection completes. */
    fun scheduleImmediateEmbedding()

    /** Periodic reconciliation safety net. */
    fun scheduleIncrementalEmbedding()
}
