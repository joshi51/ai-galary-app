package com.localphotoai.photomanager.ml.vision

import com.localphotoai.photomanager.domain.face.FaceDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionModule {

    @Binds
    @Singleton
    abstract fun bindFaceDetector(impl: MlKitFaceDetectorImpl): FaceDetector
}
