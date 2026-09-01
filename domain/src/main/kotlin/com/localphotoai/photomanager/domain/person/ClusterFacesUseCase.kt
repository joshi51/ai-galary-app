package com.localphotoai.photomanager.domain.person

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState

private const val TAG = "ClusterFacesUseCase"

/**
 * Runs one clustering pass: every embedded, not-yet-clustered, not-marked-incorrect face is
 * assigned to the closest existing person or seeds a new one (see [FaceClusterer]). This is
 * incremental, not a destructive full rebuild — already-clustered faces (including anything the
 * user has since merged, split, or named) are never touched by a normal run, only newly
 * available faces are processed. A full re-cluster from scratch (e.g. after a genuine algorithm
 * version bump) is a deliberately separate, not-yet-built capability — see ARCHITECTURE.md's
 * Phase 5 notes.
 */
class ClusterFacesUseCase(
    private val repository: PersonRepository,
    private val logger: Logger,
) {

    suspend operator fun invoke(): AppResult<IndexingProgress> {
        return try {
            val startedAt = System.currentTimeMillis()
            val pending = repository.fetchFacesNeedingClustering().sortedBy { it.faceId }

            if (pending.isEmpty()) {
                val progress = IndexingProgress(
                    state = IndexingState.COMPLETE,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = null,
                )
                repository.updateClusteringProgress(progress)
                return AppResult.Success(progress)
            }

            repository.updateClusteringProgress(
                IndexingProgress(
                    state = IndexingState.RUNNING,
                    itemsProcessed = 0,
                    itemsTotal = pending.size,
                    lastRunAtMs = startedAt,
                    lastError = null,
                ),
            )

            val existingClusters = repository.fetchExistingClusters()
            val result = FaceClusterer.cluster(pending, existingClusters)
            repository.applyClusteringResult(pending, result)

            val finalProgress = IndexingProgress(
                state = IndexingState.COMPLETE,
                itemsProcessed = pending.size,
                itemsTotal = pending.size,
                lastRunAtMs = startedAt,
                lastError = null,
            )
            repository.updateClusteringProgress(finalProgress)
            logger.info(
                TAG,
                "Clustering complete: ${pending.size} face(s) processed, ${result.newClusterCount} new cluster(s)",
            )
            AppResult.Success(finalProgress)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown clustering error"
            logger.error(TAG, "Clustering run failed", t)
            repository.updateClusteringProgress(
                IndexingProgress(
                    state = IndexingState.ERROR,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = System.currentTimeMillis(),
                    lastError = message,
                ),
            )
            AppResult.Failure(AppError.Io(message = "Clustering failed: $message", cause = t))
        }
    }
}
