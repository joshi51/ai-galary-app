package com.localphotoai.photomanager.domain.similarity

/** Schedules background hash-grouping (duplicate/near-dup/burst) work. */
interface HashGroupingScheduler {
    fun scheduleImmediateGrouping()
    fun scheduleIncrementalGrouping()
}

/** Schedules background similarity-embedding generation. */
interface SimilarityEmbeddingScheduler {
    fun scheduleImmediateEmbedding()
    fun scheduleIncrementalEmbedding()
}

/** Schedules background visually-similar grouping (chained off similarity embedding). */
interface VisuallySimilarGroupingScheduler {
    fun scheduleImmediateGrouping()
    fun scheduleIncrementalGrouping()
}
