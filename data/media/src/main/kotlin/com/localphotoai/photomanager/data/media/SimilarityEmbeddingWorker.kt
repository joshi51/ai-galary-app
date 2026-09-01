package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.GenerateImageSimilarityEmbeddingsUseCase
import com.localphotoai.photomanager.domain.similarity.VisuallySimilarGroupingScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one similarity-embedding-generation pass. Chained off [HashWorker]. On success, triggers
 * an immediate visually-similar grouping pass so newly embedded photos get grouped without
 * waiting for that stage's own periodic reconciliation job.
 */
@HiltWorker
class SimilarityEmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val generateImageSimilarityEmbeddingsUseCase: GenerateImageSimilarityEmbeddingsUseCase,
    private val visuallySimilarGroupingScheduler: VisuallySimilarGroupingScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = generateImageSimilarityEmbeddingsUseCase()
        return when (result) {
            is AppResult.Success -> {
                visuallySimilarGroupingScheduler.scheduleImmediateGrouping()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "similarity_embedding_immediate"
        const val WORK_NAME_PERIODIC = "similarity_embedding_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
