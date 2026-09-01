package com.localphotoai.photomanager.domain.similarity

/** Schedules background hashing work. Implemented in `:data:media` on top of WorkManager. */
interface HashScheduler {
    fun scheduleImmediateHashing()
    fun scheduleIncrementalHashing()
}
