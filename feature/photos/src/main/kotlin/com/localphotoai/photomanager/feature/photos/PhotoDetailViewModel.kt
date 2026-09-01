package com.localphotoai.photomanager.feature.photos

import androidx.lifecycle.ViewModel
import com.localphotoai.photomanager.domain.face.Face
import com.localphotoai.photomanager.domain.face.FaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@HiltViewModel
class PhotoDetailViewModel @Inject constructor(
    private val faceRepository: FaceRepository,
) : ViewModel() {

    fun observeFaces(photoId: Long): Flow<List<Face>> = faceRepository.observeFacesForPhoto(photoId)
}
