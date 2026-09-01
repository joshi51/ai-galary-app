package com.localphotoai.photomanager.data.database

import android.content.Context
import androidx.room.Room
import com.localphotoai.photomanager.data.database.dao.ClusteringStatusDao
import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingStatusDao
import com.localphotoai.photomanager.data.database.dao.FaceDao
import com.localphotoai.photomanager.data.database.dao.FaceDetectionStatusDao
import com.localphotoai.photomanager.data.database.dao.IndexingStatusDao
import com.localphotoai.photomanager.data.database.dao.PersonDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SearchDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.dao.SimilarityEmbeddingDao
import com.localphotoai.photomanager.data.database.dao.AlbumDao
import com.localphotoai.photomanager.data.database.dao.OrganizationDao
import com.localphotoai.photomanager.data.database.dao.StatisticsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private const val DATABASE_NAME = "photo-manager.db"

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides
    fun providePhotoDao(database: AppDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideIndexingStatusDao(database: AppDatabase): IndexingStatusDao = database.indexingStatusDao()

    @Provides
    fun provideFaceDao(database: AppDatabase): FaceDao = database.faceDao()

    @Provides
    fun provideFaceDetectionStatusDao(database: AppDatabase): FaceDetectionStatusDao =
        database.faceDetectionStatusDao()

    @Provides
    fun provideEmbeddingDao(database: AppDatabase): EmbeddingDao = database.embeddingDao()

    @Provides
    fun provideEmbeddingStatusDao(database: AppDatabase): EmbeddingStatusDao = database.embeddingStatusDao()

    @Provides
    fun providePersonDao(database: AppDatabase): PersonDao = database.personDao()

    @Provides
    fun provideClusteringStatusDao(database: AppDatabase): ClusteringStatusDao = database.clusteringStatusDao()

    @Provides
    fun provideSearchDao(database: AppDatabase): SearchDao = database.searchDao()

    @Provides
    fun provideDuplicateGroupDao(database: AppDatabase): DuplicateGroupDao = database.duplicateGroupDao()

    @Provides
    fun provideSimilarGroupDao(database: AppDatabase): SimilarGroupDao = database.similarGroupDao()

    @Provides
    fun provideSimilarityEmbeddingDao(database: AppDatabase): SimilarityEmbeddingDao = database.similarityEmbeddingDao()

    @Provides
    fun provideStatisticsDao(database: AppDatabase): StatisticsDao = database.statisticsDao()

    @Provides
    fun provideAlbumDao(database: AppDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideOrganizationDao(database: AppDatabase): OrganizationDao = database.organizationDao()
}
