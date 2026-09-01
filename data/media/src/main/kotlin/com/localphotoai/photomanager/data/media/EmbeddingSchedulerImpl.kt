package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localphotoai.photomanager.domain.face.EmbeddingScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddingSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmbeddingScheduler {

    override fun scheduleImmediateEmbedding() {
        val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(EmbeddingWorker.WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleIncrementalEmbedding() {
        val request = PeriodicWorkRequestBuilder<EmbeddingWorker>(6, TimeUnit.HOURS)
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(EmbeddingWorker.WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
