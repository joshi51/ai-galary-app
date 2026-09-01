package com.localphotoai.photomanager.fsops

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import javax.inject.Inject

/**
 * Wraps the one real Android-version-dependent decision this module needs: on API 30+, request
 * write access via [MediaStore.createWriteRequest] up front; below 30, attempt the write
 * directly and only surface an [IntentSender] if a [RecoverableSecurityException] is thrown —
 * exactly mirroring Phase 7's `DuplicatesScreen` delete flow, per operation, not batched.
 */
class MediaStoreWriter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    fun contentResolver(): ContentResolver = context.contentResolver

    fun writeRequestIntentSender(uri: Uri): IntentSender {
        val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        return request.intentSender
    }

    fun isPreApi30(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun intentSenderFromRecoverableSecurityException(e: RecoverableSecurityException): IntentSender =
        e.userAction.actionIntent.intentSender
}
