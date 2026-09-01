package com.localphotoai.photomanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.face.EmbeddingModelDownloader
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import com.localphotoai.photomanager.domain.settings.ThemeMode
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val embeddingModelDownloader: EmbeddingModelDownloader,
    private val llmModelDownloader: LlmModelDownloader,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val modelDownloadState: StateFlow<ModelDownloadState> = embeddingModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelDownloadState.NotDownloaded)

    val llmModelDownloadState: StateFlow<LlmModelDownloadState> = llmModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmModelDownloadState.NotDownloaded)

    val savedSearchLocation: StateFlow<SavedSearchLocation?> = settingsRepository.observeSavedSearchLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun onDownloadModelClicked() {
        viewModelScope.launch {
            embeddingModelDownloader.downloadModel()
        }
    }

    fun onDownloadLlmModelClicked() {
        viewModelScope.launch {
            llmModelDownloader.downloadModel()
        }
    }

    fun onSaveSearchLocation(latitude: Double, longitude: Double, radiusKm: Double) {
        viewModelScope.launch {
            settingsRepository.setSavedSearchLocation(latitude, longitude, radiusKm)
        }
    }

    fun onClearSearchLocation() {
        viewModelScope.launch {
            settingsRepository.clearSavedSearchLocation()
        }
    }
}
