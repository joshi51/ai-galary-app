package com.localphotoai.photomanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.diagnostics.DatabaseDiagnosticsRepository
import com.localphotoai.photomanager.domain.face.EmbeddingModelDownloader
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import com.localphotoai.photomanager.domain.similarity.ImageSimilarityEmbeddingGenerator
import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.statistics.StorageStatistics
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A snapshot of on-device AI/data state — what Phase 11 requires the diagnostics screen to show,
 * gathered fresh on demand rather than kept live, since none of it changes fast enough to justify
 * an observing Flow (unlike e.g. indexing progress). */
data class DiagnosticsSnapshot(
    val statistics: StorageStatistics,
    val databaseSizeBytes: Long,
    val faceEmbeddingModelState: ModelDownloadState,
    val faceEmbeddingModelVersion: Int,
    val llmModelState: LlmModelDownloadState,
    val llmModelVersion: Int,
    val similarityModelVersion: Int,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val getStorageStatisticsUseCase: GetStorageStatisticsUseCase,
    private val databaseDiagnosticsRepository: DatabaseDiagnosticsRepository,
    private val embeddingModelDownloader: EmbeddingModelDownloader,
    private val llmModelDownloader: LlmModelDownloader,
    private val similarityEmbeddingGenerator: ImageSimilarityEmbeddingGenerator,
) : ViewModel() {

    private val snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)
    val state: StateFlow<DiagnosticsSnapshot?> = snapshot.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            snapshot.value = DiagnosticsSnapshot(
                statistics = getStorageStatisticsUseCase(),
                databaseSizeBytes = databaseDiagnosticsRepository.fetchDatabaseSizeBytes(),
                faceEmbeddingModelState = embeddingModelDownloader.observeDownloadState().first(),
                faceEmbeddingModelVersion = embeddingModelDownloader.modelVersion,
                llmModelState = llmModelDownloader.observeDownloadState().first(),
                llmModelVersion = llmModelDownloader.modelVersion,
                similarityModelVersion = similarityEmbeddingGenerator.modelVersion,
            )
        }
    }
}
