package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.ClusterFacesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one clustering pass via [ClusterFacesUseCase]. Safe to interrupt and re-run: it's
 * incremental (already-clustered faces are never touched), so a job killed mid-run simply picks
 * up any still-unclustered faces on the next trigger.
 */
@HiltWorker
class ClusteringWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val clusterFacesUseCase: ClusterFacesUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = clusterFacesUseCase()
        return when (result) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "clustering_immediate"
        const val WORK_NAME_PERIODIC = "clustering_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
