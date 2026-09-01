package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.core.common.AppDispatchers
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val TAG = "HttpLlmModelDownloader"
private const val MAX_REDIRECTS = 5

/**
 * `HttpURLConnection.setInstanceFollowRedirects(true)` does NOT follow a redirect to a different
 * host (a real JDK limitation, not a bug in this code) — and the model's download URL redirects
 * from huggingface.co to a separate CDN host, so redirects are followed manually here.
 */
private fun openConnectionFollowingRedirects(url: String): HttpURLConnection {
    var currentUrl = url
    repeat(MAX_REDIRECTS) {
        val connection = URL(currentUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.connect()

        if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location") ?: error("Redirect with no Location header")
            connection.disconnect()
            currentUrl = location
            return@repeat
        }
        return connection
    }
    error("Too many redirects downloading model")
}

@Singleton
class HttpLlmModelDownloader @Inject constructor(
    private val modelFileStore: ModelFileStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : LlmModelDownloader {

    private val state = MutableStateFlow<LlmModelDownloadState>(
        if (modelFileStore.isModelPresent()) LlmModelDownloadState.Ready else LlmModelDownloadState.NotDownloaded,
    )

    override val modelVersion: Int = Llama32ModelSpec.MODEL_VERSION

    override fun observeDownloadState(): StateFlow<LlmModelDownloadState> = state

    override suspend fun downloadModel() {
        if (modelFileStore.isModelPresent()) {
            state.value = LlmModelDownloadState.Ready
            return
        }
        state.value = LlmModelDownloadState.Downloading(0)
        withContext(dispatchers.io) {
            val tempFile = modelFileStore.tempFile()
            try {
                val connection = openConnectionFollowingRedirects(Llama32ModelSpec.DOWNLOAD_URL)
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
                                state.value = LlmModelDownloadState.Downloading(percent.coerceIn(0, 100))
                            }
                        }
                    }
                }
                connection.disconnect()

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(Llama32ModelSpec.SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    error("Downloaded model checksum mismatch (got $actualHash)")
                }

                if (!tempFile.renameTo(modelFileStore.modelFile)) {
                    error("Failed to finalize downloaded model file")
                }
                state.value = LlmModelDownloadState.Ready
                logger.info(TAG, "LLM model downloaded and verified ($downloadedBytes bytes)")
            } catch (t: Throwable) {
                tempFile.delete()
                logger.error(TAG, "LLM model download failed", t)
                state.value = LlmModelDownloadState.Failed(t.message ?: "Download failed")
            }
        }
    }
}
