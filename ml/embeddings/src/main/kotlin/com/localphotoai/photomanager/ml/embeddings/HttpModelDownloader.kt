package com.localphotoai.photomanager.ml.embeddings

import com.localphotoai.photomanager.core.common.AppDispatchers
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.face.EmbeddingModelDownloader
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val TAG = "HttpModelDownloader"

/**
 * Downloads the face-embedding model over the network into app-private storage, only when
 * [downloadModel] is explicitly called — never implicitly. Verifies the download against a
 * pinned SHA-256 hash before making it available, and downloads to a temp file first so a
 * partial/interrupted download is never mistaken for a complete model.
 */
@Singleton
class HttpModelDownloader @Inject constructor(
    private val modelFileStore: ModelFileStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : EmbeddingModelDownloader {

    private val state = MutableStateFlow<ModelDownloadState>(
        if (modelFileStore.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotDownloaded,
    )

    override fun observeDownloadState(): StateFlow<ModelDownloadState> = state

    override suspend fun downloadModel() {
        if (modelFileStore.isModelPresent()) {
            state.value = ModelDownloadState.Ready
            return
        }
        state.value = ModelDownloadState.Downloading(0)
        withContext(dispatchers.io) {
            val tempFile = modelFileStore.tempFile()
            try {
                val connection = URL(FaceNetModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode} downloading model")
                }

                val totalBytes = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                state.value = ModelDownloadState.Downloading(percent.coerceIn(0, 100))
                            }
                        }
                    }
                }
                connection.disconnect()

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(FaceNetModelSpec.SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    error("Downloaded model checksum mismatch (got $actualHash)")
                }

                if (!tempFile.renameTo(modelFileStore.modelFile)) {
                    error("Failed to finalize downloaded model file")
                }
                state.value = ModelDownloadState.Ready
                logger.info(TAG, "Embedding model downloaded and verified ($downloadedBytes bytes)")
            } catch (t: Throwable) {
                tempFile.delete()
                logger.error(TAG, "Embedding model download failed", t)
                state.value = ModelDownloadState.Failed(t.message ?: "Download failed")
            }
        }
    }
}
