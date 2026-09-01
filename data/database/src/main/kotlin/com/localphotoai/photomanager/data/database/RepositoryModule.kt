package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.diagnostics.DatabaseDiagnosticsRepository
import com.localphotoai.photomanager.domain.face.FaceEmbeddingRepository
import com.localphotoai.photomanager.domain.face.FaceRepository
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.ConfirmOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.GetOperationHistoryUseCase
import com.localphotoai.photomanager.domain.organization.OperationHistoryRepository
import com.localphotoai.photomanager.domain.organization.OrganizationPlanRepository
import com.localphotoai.photomanager.domain.organization.RecordOrganizationExecutionUseCase
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.search.SearchRepository
import com.localphotoai.photomanager.domain.similarity.DetectDuplicatesUseCase
import com.localphotoai.photomanager.domain.similarity.GenerateImageSimilarityEmbeddingsUseCase
import com.localphotoai.photomanager.domain.similarity.GroupNearDuplicatesAndBurstsUseCase
import com.localphotoai.photomanager.domain.similarity.GroupVisuallySimilarPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.HashPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.ImageSimilarityEmbeddingGenerator
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.PhotoHasher
import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.statistics.StorageStatisticsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: FaceRepositoryImpl): FaceRepository

    @Binds
    @Singleton
    abstract fun bindFaceEmbeddingRepository(impl: FaceEmbeddingRepositoryImpl): FaceEmbeddingRepository

    @Binds
    @Singleton
    abstract fun bindPersonRepository(impl: PersonRepositoryImpl): PersonRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindPhotoGroupRepository(impl: PhotoGroupRepositoryImpl): PhotoGroupRepository

    @Binds
    @Singleton
    abstract fun bindStorageStatisticsRepository(impl: StorageStatisticsRepositoryImpl): StorageStatisticsRepository

    @Binds
    @Singleton
    abstract fun bindOrganizationPlanRepository(impl: OrganizationRepositoryImpl): OrganizationPlanRepository

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(impl: AlbumRepositoryImpl): AlbumRepository

    @Binds
    @Singleton
    abstract fun bindOperationHistoryRepository(impl: OperationHistoryRepositoryImpl): OperationHistoryRepository

    @Binds
    @Singleton
    abstract fun bindDatabaseDiagnosticsRepository(impl: DatabaseDiagnosticsRepositoryImpl): DatabaseDiagnosticsRepository

    companion object {

        @Provides
        fun provideGetStorageStatisticsUseCase(repository: StorageStatisticsRepository): GetStorageStatisticsUseCase =
            GetStorageStatisticsUseCase(repository)

        @Provides
        fun provideBuildOrganizationPlanUseCase(
            photoRepository: PhotoRepository,
            photoGroupRepository: PhotoGroupRepository,
            organizationPlanRepository: OrganizationPlanRepository,
        ): BuildOrganizationPlanUseCase = BuildOrganizationPlanUseCase(photoRepository, photoGroupRepository, organizationPlanRepository)

        @Provides
        fun provideConfirmOrganizationPlanUseCase(
            organizationPlanRepository: OrganizationPlanRepository,
            albumRepository: AlbumRepository,
        ): ConfirmOrganizationPlanUseCase = ConfirmOrganizationPlanUseCase(organizationPlanRepository, albumRepository)

        @Provides
        fun provideGetPhotoMetadataUseCase(repository: PhotoRepository): GetPhotoMetadataUseCase =
            GetPhotoMetadataUseCase(repository)

        @Provides
        fun provideSearchPhotosUseCase(repository: SearchRepository): SearchPhotosUseCase =
            SearchPhotosUseCase(repository)

        @Provides
        fun provideHashPhotosUseCase(
            repository: PhotoGroupRepository,
            hasher: PhotoHasher,
            logger: Logger,
        ): HashPhotosUseCase = HashPhotosUseCase(repository, hasher, logger)

        @Provides
        fun provideDetectDuplicatesUseCase(repository: PhotoGroupRepository, logger: Logger): DetectDuplicatesUseCase =
            DetectDuplicatesUseCase(repository, logger)

        @Provides
        fun provideGroupNearDuplicatesAndBurstsUseCase(
            repository: PhotoGroupRepository,
            logger: Logger,
        ): GroupNearDuplicatesAndBurstsUseCase = GroupNearDuplicatesAndBurstsUseCase(repository, logger)

        @Provides
        fun provideGenerateImageSimilarityEmbeddingsUseCase(
            repository: PhotoGroupRepository,
            generator: ImageSimilarityEmbeddingGenerator,
            logger: Logger,
        ): GenerateImageSimilarityEmbeddingsUseCase = GenerateImageSimilarityEmbeddingsUseCase(repository, generator, logger)

        @Provides
        fun provideGroupVisuallySimilarPhotosUseCase(
            repository: PhotoGroupRepository,
            logger: Logger,
        ): GroupVisuallySimilarPhotosUseCase = GroupVisuallySimilarPhotosUseCase(repository, logger)

        @Provides
        fun provideRecordOrganizationExecutionUseCase(
            repository: OperationHistoryRepository,
        ): RecordOrganizationExecutionUseCase = RecordOrganizationExecutionUseCase(repository)

        @Provides
        fun provideGetOperationHistoryUseCase(repository: OperationHistoryRepository): GetOperationHistoryUseCase =
            GetOperationHistoryUseCase(repository)
    }
}
