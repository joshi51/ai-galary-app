package com.localphotoai.photomanager.domain.face

import kotlinx.coroutines.flow.Flow

sealed class ModelDownloadState {
    data object NotDownloaded : ModelDownloadState()
    data class Downloading(val progressPercent: Int) : ModelDownloadState()
    data object Ready : ModelDownloadState()
    data class Failed(val error: String) : ModelDownloadState()
}

/**
 * Downloads the face-embedding model into app-private storage. Never happens implicitly — only
 * in response to an explicit user action (e.g. tapping "Download" in Settings), per the app's
 * no-implicit-network-use principle. Implemented in `:ml:embeddings`.
 */
interface EmbeddingModelDownloader {
    fun observeDownloadState(): Flow<ModelDownloadState>
    suspend fun downloadModel()

    /** The face-embedding model's own version number (bumped whenever the model file itself
     * changes) — surfaced on Phase 11's diagnostics screen, independent of download state. */
    val modelVersion: Int
}
