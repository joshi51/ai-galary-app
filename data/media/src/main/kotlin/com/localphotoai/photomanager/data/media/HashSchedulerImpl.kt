package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localphotoai.photomanager.domain.similarity.HashScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HashSchedulerImpl @Inject constructor(@ApplicationContext private val context: Context) : HashScheduler {

    override fun scheduleImmediateHashing() {
        val request = OneTimeWorkRequestBuilder<HashWorker>()
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(HashWorker.WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleIncrementalHashing() {
        val request = PeriodicWorkRequestBuilder<HashWorker>(6, TimeUnit.HOURS)
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HashWorker.WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
