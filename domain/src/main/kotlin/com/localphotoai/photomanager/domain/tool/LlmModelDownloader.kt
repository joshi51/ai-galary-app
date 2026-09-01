package com.localphotoai.photomanager.domain.tool

import kotlinx.coroutines.flow.StateFlow

sealed class LlmModelDownloadState {
    object NotDownloaded : LlmModelDownloadState()
    data class Downloading(val percent: Int) : LlmModelDownloadState()
    object Ready : LlmModelDownloadState()
    data class Failed(val reason: String) : LlmModelDownloadState()
}

interface LlmModelDownloader {
    fun observeDownloadState(): StateFlow<LlmModelDownloadState>
    suspend fun downloadModel()

    /** The search-assistant LLM's own version number — surfaced on Phase 11's diagnostics screen. */
    val modelVersion: Int
}
