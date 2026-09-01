package com.localphotoai.photomanager.data.media

import com.localphotoai.photomanager.domain.face.DetectFacesUseCase
import com.localphotoai.photomanager.domain.face.EmbeddingGenerator
import com.localphotoai.photomanager.domain.face.EmbeddingScheduler
import com.localphotoai.photomanager.domain.face.FaceDetectionScheduler
import com.localphotoai.photomanager.domain.face.FaceDetector
import com.localphotoai.photomanager.domain.face.FaceEmbeddingRepository
import com.localphotoai.photomanager.domain.face.FaceRepository
import com.localphotoai.photomanager.domain.face.GenerateFaceEmbeddingsUseCase
import com.localphotoai.photomanager.domain.person.ClusterFacesUseCase
import com.localphotoai.photomanager.domain.person.ClusteringScheduler
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.photo.IndexPhotosUseCase
import com.localphotoai.photomanager.domain.photo.IndexingScheduler
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler
import com.localphotoai.photomanager.domain.similarity.HashScheduler
import com.localphotoai.photomanager.domain.similarity.PhotoHasher
import com.localphotoai.photomanager.domain.similarity.SimilarityEmbeddingScheduler
import com.localphotoai.photomanager.domain.similarity.VisuallySimilarGroupingScheduler
import com.localphotoai.photomanager.core.common.Logger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(impl: PhotoRepositoryImpl): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindIndexingScheduler(impl: IndexingSchedulerImpl): IndexingScheduler

    @Binds
    @Singleton
    abstract fun bindFaceDetectionScheduler(impl: FaceDetectionSchedulerImpl): FaceDetectionScheduler

    @Binds
    @Singleton
    abstract fun bindEmbeddingScheduler(impl: EmbeddingSchedulerImpl): EmbeddingScheduler

    @Binds
    @Singleton
    abstract fun bindClusteringScheduler(impl: ClusteringSchedulerImpl): ClusteringScheduler

    @Binds
    @Singleton
    abstract fun bindPhotoHasher(impl: PhotoHasherImpl): PhotoHasher

    @Binds
    @Singleton
    abstract fun bindHashScheduler(impl: HashSchedulerImpl): HashScheduler

    @Binds
    @Singleton
    abstract fun bindHashGroupingScheduler(impl: HashGroupingSchedulerImpl): HashGroupingScheduler

    @Binds
    @Singleton
    abstract fun bindSimilarityEmbeddingScheduler(impl: SimilarityEmbeddingSchedulerImpl): SimilarityEmbeddingScheduler

    @Binds
    @Singleton
    abstract fun bindVisuallySimilarGroupingScheduler(
        impl: VisuallySimilarGroupingSchedulerImpl,
    ): VisuallySimilarGroupingScheduler

    companion object {
        @Provides
        fun provideIndexPhotosUseCase(repository: PhotoRepository, logger: Logger): IndexPhotosUseCase =
            IndexPhotosUseCase(repository, logger)

        @Provides
        fun provideDetectFacesUseCase(
            repository: FaceRepository,
            faceDetector: FaceDetector,
            logger: Logger,
        ): DetectFacesUseCase = DetectFacesUseCase(repository, faceDetector, logger)

        @Provides
        fun provideGenerateFaceEmbeddingsUseCase(
            repository: FaceEmbeddingRepository,
            embeddingGenerator: EmbeddingGenerator,
            logger: Logger,
        ): GenerateFaceEmbeddingsUseCase = GenerateFaceEmbeddingsUseCase(repository, embeddingGenerator, logger)

        @Provides
        fun provideClusterFacesUseCase(repository: PersonRepository, logger: Logger): ClusterFacesUseCase =
            ClusterFacesUseCase(repository, logger)
    }
}
