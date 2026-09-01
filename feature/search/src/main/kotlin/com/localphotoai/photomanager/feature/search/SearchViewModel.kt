package com.localphotoai.photomanager.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.LocationBoundingBoxCalculator
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import com.localphotoai.photomanager.llm.orchestration.SearchOutcome
import com.localphotoai.photomanager.llm.orchestration.ToolCallLoop
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In-progress filter selection made by the user in the Search screen. */
data class SearchFilterState(
    val selectedPersonIds: Set<Long> = emptySet(),
    val selectedYear: Int? = null,
    val locationFilterEnabled: Boolean = false,
) {
    fun toDomainFilterOrNull(savedLocation: SavedSearchLocation?): PhotoSearchFilter? {
        if (selectedPersonIds.isEmpty()) return null

        val (startDateMs, endDateMs) = selectedYear?.let { yearRangeMs(it) } ?: (null to null)

        val boundingBox = if (locationFilterEnabled && savedLocation != null) {
            LocationBoundingBoxCalculator.fromPointAndRadiusKm(
                latitude = savedLocation.latitude,
                longitude = savedLocation.longitude,
                radiusKm = savedLocation.radiusKm,
            )
        } else {
            null
        }

        return PhotoSearchFilter(
            personIds = selectedPersonIds,
            startDateMs = startDateMs,
            endDateMs = endDateMs,
            locationBoundingBox = boundingBox,
        )
    }

    private fun yearRangeMs(year: Int): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, 12, 31).atTime(23, 59, 59)
            .atZone(zone).toInstant().toEpochMilli()
        return start to end
    }
}

sealed class NlSearchUiState {
    object Idle : NlSearchUiState()
    object Loading : NlSearchUiState()
    data class Results(val photos: List<Photo>, val message: String) : NlSearchUiState()
    data class Message(val text: String) : NlSearchUiState()
    data class Plan(val plan: com.localphotoai.photomanager.domain.organization.OrganizationPlan, val message: String) : NlSearchUiState()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    personRepository: PersonRepository,
    settingsRepository: SettingsRepository,
    private val searchPhotosUseCase: SearchPhotosUseCase,
    private val toolCallLoop: ToolCallLoop,
    llmModelDownloader: LlmModelDownloader,
) : ViewModel() {

    val llmModelDownloadState: StateFlow<LlmModelDownloadState> = llmModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmModelDownloadState.NotDownloaded)

    private val nlState = MutableStateFlow<NlSearchUiState>(NlSearchUiState.Idle)
    val nlSearchUiState: StateFlow<NlSearchUiState> = nlState.asStateFlow()

    fun onNlQuerySubmitted(query: String) {
        if (query.isBlank()) return
        nlState.value = NlSearchUiState.Loading
        viewModelScope.launch {
            when (val outcome = toolCallLoop.run(query)) {
                is SearchOutcome.Answered -> nlState.value = when (val result = outcome.outcome) {
                    is ToolOutcome.Photos -> NlSearchUiState.Results(result.photos, result.message)
                    is ToolOutcome.Metadata -> NlSearchUiState.Results(listOf(result.photo), result.message)
                    is ToolOutcome.Statistics -> NlSearchUiState.Message(result.message)
                    is ToolOutcome.Plan -> NlSearchUiState.Plan(result.plan, result.message)
                    is ToolOutcome.Error -> NlSearchUiState.Message(result.message)
                }
                is SearchOutcome.Misunderstood -> nlState.value =
                    NlSearchUiState.Message("Couldn't understand that — try the filters below.")
            }
        }
    }

    val people: StateFlow<List<PersonWithStats>> = personRepository.observePeopleWithStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedLocation: StateFlow<SavedSearchLocation?> = settingsRepository.observeSavedSearchLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val filterState = MutableStateFlow(SearchFilterState())
    val filterUiState: StateFlow<SearchFilterState> = filterState.asStateFlow()

    val results: Flow<PagingData<Photo>> = combine(filterState, savedLocation) { state, saved ->
        state.toDomainFilterOrNull(saved)
    }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            if (filter == null) {
                flowOf(PagingData.empty())
            } else {
                when (val result = searchPhotosUseCase(filter)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> flowOf(PagingData.empty())
                }
            }
        }
        .cachedIn(viewModelScope)

    fun onPersonToggled(personId: Long) {
        filterState.update { current ->
            val newSelection = if (personId in current.selectedPersonIds) {
                current.selectedPersonIds - personId
            } else {
                current.selectedPersonIds + personId
            }
            current.copy(selectedPersonIds = newSelection)
        }
    }

    fun onYearSelected(year: Int?) {
        filterState.update { it.copy(selectedYear = year) }
    }

    fun onLocationFilterToggled(enabled: Boolean) {
        filterState.update { it.copy(locationFilterEnabled = enabled) }
    }
}
