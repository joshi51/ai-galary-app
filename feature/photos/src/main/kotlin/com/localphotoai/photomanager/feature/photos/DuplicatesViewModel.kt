package com.localphotoai.photomanager.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: PhotoGroupRepository,
) : ViewModel() {

    val exactDuplicates: StateFlow<List<DuplicateGroupSummary>> = repository.observeDuplicateGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bursts: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.BURST)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nearDuplicates: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.NEAR_DUPLICATE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visuallySimilar: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.VISUALLY_SIMILAR)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called after a deletion is confirmed and actually performed by the system/legacy delete path. */
    fun onPhotoDeleted(photoId: Long) {
        viewModelScope.launch { repository.removePhotoFromAllGroups(photoId) }
    }
}
