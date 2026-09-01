package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.GroupVisuallySimilarPhotosUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one visually-similar grouping pass via [GroupVisuallySimilarPhotosUseCase]. Chained off
 * [SimilarityEmbeddingWorker]. Terminal — no further stage depends on this one.
 */
@HiltWorker
class VisuallySimilarGroupingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val groupVisuallySimilarPhotosUseCase: GroupVisuallySimilarPhotosUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = groupVisuallySimilarPhotosUseCase()
        return when (result) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "visually_similar_grouping_immediate"
        const val WORK_NAME_PERIODIC = "visually_similar_grouping_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
