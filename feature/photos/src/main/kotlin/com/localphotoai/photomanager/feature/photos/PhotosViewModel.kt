package com.localphotoai.photomanager.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.face.EmbeddingScheduler
import com.localphotoai.photomanager.domain.face.FaceDetectionScheduler
import com.localphotoai.photomanager.domain.person.ClusteringScheduler
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingScheduler
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler
import com.localphotoai.photomanager.domain.similarity.HashScheduler
import com.localphotoai.photomanager.domain.similarity.SimilarityEmbeddingScheduler
import com.localphotoai.photomanager.domain.similarity.VisuallySimilarGroupingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PhotosUiState(
    val photos: List<Photo> = emptyList(),
    val indexingProgress: IndexingProgress = IndexingProgress.IDLE,
)

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val indexingScheduler: IndexingScheduler,
    private val faceDetectionScheduler: FaceDetectionScheduler,
    private val embeddingScheduler: EmbeddingScheduler,
    private val clusteringScheduler: ClusteringScheduler,
    private val hashScheduler: HashScheduler,
    private val hashGroupingScheduler: HashGroupingScheduler,
    private val similarityEmbeddingScheduler: SimilarityEmbeddingScheduler,
    private val visuallySimilarGroupingScheduler: VisuallySimilarGroupingScheduler,
) : ViewModel() {

    val uiState: StateFlow<PhotosUiState> = combine(
        photoRepository.observePhotos(),
        photoRepository.observeIndexingProgress(),
    ) { photos, progress -> PhotosUiState(photos = photos, indexingProgress = progress) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosUiState())

    /** Called once the user has granted photo-access permission (first grant, or an app relaunch). */
    fun onPhotoAccessGranted() {
        indexingScheduler.scheduleImmediateIndex()
        indexingScheduler.scheduleIncrementalIndexing()
        faceDetectionScheduler.scheduleIncrementalDetection()
        embeddingScheduler.scheduleIncrementalEmbedding()
        clusteringScheduler.scheduleIncrementalClustering()
        hashScheduler.scheduleIncrementalHashing()
        hashGroupingScheduler.scheduleIncrementalGrouping()
        similarityEmbeddingScheduler.scheduleIncrementalEmbedding()
        visuallySimilarGroupingScheduler.scheduleIncrementalGrouping()
    }

    fun onRefreshRequested() {
        indexingScheduler.scheduleImmediateIndex()
    }
}
