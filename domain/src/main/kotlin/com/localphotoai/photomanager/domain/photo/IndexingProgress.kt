package com.localphotoai.photomanager.domain.photo

enum class IndexingState { IDLE, RUNNING, COMPLETE, ERROR }

/**
 * Durable, observable state of the photo-indexing pipeline, backed by a Room row so the UI
 * can observe progress as a [kotlinx.coroutines.flow.Flow] independent of WorkManager's own
 * (non-durable, harder to observe cross-process) work-info APIs.
 */
data class IndexingProgress(
    val state: IndexingState,
    val itemsProcessed: Int,
    val itemsTotal: Int,
    val lastRunAtMs: Long,
    val lastError: String?,
) {
    companion object {
        val IDLE = IndexingProgress(
            state = IndexingState.IDLE,
            itemsProcessed = 0,
            itemsTotal = 0,
            lastRunAtMs = 0L,
            lastError = null,
        )
    }
}
