package com.localphotoai.photomanager.ml.embeddings

import com.localphotoai.photomanager.domain.face.EmbeddingGenerator
import com.localphotoai.photomanager.domain.face.EmbeddingModelDownloader
import com.localphotoai.photomanager.domain.similarity.ImageSimilarityEmbeddingGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingsModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingGenerator(impl: FaceNetEmbeddingGenerator): EmbeddingGenerator

    @Binds
    @Singleton
    abstract fun bindEmbeddingModelDownloader(impl: HttpModelDownloader): EmbeddingModelDownloader

    @Binds
    @Singleton
    abstract fun bindImageSimilarityEmbeddingGenerator(
        impl: MobileNetV3EmbeddingGenerator,
    ): ImageSimilarityEmbeddingGenerator
}
