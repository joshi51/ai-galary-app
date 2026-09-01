package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger

private const val TAG = "IndexPhotosUseCase"
private const val CHUNK_SIZE = 30

/**
 * Runs one incremental indexing pass: diffs MediaStore against what's locally persisted and
 * applies only the changes, in chunks that are committed (and checkpointed via progress) one at
 * a time. A run interrupted mid-chunk loses at most the in-flight chunk — the next run re-diffs
 * from scratch and picks up whatever wasn't yet applied, since already-applied chunks are no
 * longer part of the diff.
 */
class IndexPhotosUseCase(
    private val repository: PhotoRepository,
    private val logger: Logger,
) {

    suspend operator fun invoke(): AppResult<IndexingProgress> {
        return try {
            val startedAt = System.currentTimeMillis()

            val generation = repository.fetchGeneration()
            val lastGeneration = repository.lastSavedGeneration()
            if (generation != null && lastGeneration != null && generation == lastGeneration) {
                val progress = IndexingProgress(
                    state = IndexingState.COMPLETE,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = null,
                )
                repository.updateIndexingProgress(progress)
                return AppResult.Success(progress)
            }

            repository.updateIndexingProgress(
                IndexingProgress(
                    state = IndexingState.RUNNING,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = null,
                ),
            )

            val remote = repository.fetchRemoteLightSnapshot()
            val local = repository.fetchLocalLightSnapshot()
            val diff = PhotoIndexDiffCalculator.computeDiff(remote, local)

            val totalItems = diff.newOrChangedIds.size + diff.deletedIds.size
            var processed = 0

            if (diff.deletedIds.isNotEmpty()) {
                for (chunk in diff.deletedIds.chunked(CHUNK_SIZE)) {
                    repository.deleteByMediaStoreIds(chunk)
                    processed += chunk.size
                    reportProgress(startedAt, processed, totalItems)
                }
            }

            if (diff.newOrChangedIds.isNotEmpty()) {
                for (chunk in diff.newOrChangedIds.chunked(CHUNK_SIZE)) {
                    val metadata = repository.fetchFullMetadata(chunk)
                    repository.upsert(metadata)
                    processed += chunk.size
                    reportProgress(startedAt, processed, totalItems)
                }
            }

            if (generation != null) {
                repository.saveGeneration(generation)
            }

            val finalProgress = IndexingProgress(
                state = IndexingState.COMPLETE,
                itemsProcessed = totalItems,
                itemsTotal = totalItems,
                lastRunAtMs = startedAt,
                lastError = null,
            )
            repository.updateIndexingProgress(finalProgress)
            logger.info(TAG, "Indexing complete: $totalItems item(s) changed")
            AppResult.Success(finalProgress)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown indexing error"
            logger.error(TAG, "Indexing failed", t)
            repository.updateIndexingProgress(
                IndexingProgress(
                    state = IndexingState.ERROR,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = System.currentTimeMillis(),
                    lastError = message,
                ),
            )
            AppResult.Failure(AppError.Io(message = "Photo indexing failed: $message", cause = t))
        }
    }

    private suspend fun reportProgress(startedAt: Long, processed: Int, total: Int) {
        repository.updateIndexingProgress(
            IndexingProgress(
                state = IndexingState.RUNNING,
                itemsProcessed = processed,
                itemsTotal = total,
                lastRunAtMs = startedAt,
                lastError = null,
            ),
        )
    }
}
