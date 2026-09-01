package com.localphotoai.photomanager.data.media

import androidx.work.Constraints
import androidx.work.NetworkType

/** Shared constraints for all indexing/detection background work: fully offline, battery-friendly. */
internal val NO_NETWORK_REQUIRED = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
    .setRequiresBatteryNotLow(true)
    .build()
