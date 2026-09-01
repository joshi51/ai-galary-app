package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind as EntitySimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoForHashing
import com.localphotoai.photomanager.domain.similarity.PhotoForSimilarityEmbedding
import com.localphotoai.photomanager.domain.similarity.PhotoHashInput
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary

internal fun PhotoDao.HashPendingRow.toDomain() = PhotoForHashing(mediaStoreId, uri)

internal fun PhotoDao.PhotoHashRow.toDomain() = PhotoHashInput(mediaStoreId, contentHash, perceptualHash, dateTakenMs)

internal fun PhotoDao.PhotoForEmbeddingRow.toDomain() =
    PhotoForSimilarityEmbedding(mediaStoreId, uri, widthPx, heightPx, orientationDegrees)

internal fun DuplicateGroupDao.DuplicateGroupRow.toDomain() =
    DuplicateGroupSummary(groupId, photoIdsCsv.split(",").map { it.toLong() }, totalSizeBytes)

internal fun SimilarGroupDao.SimilarGroupRow.toDomain() =
    SimilarGroupSummary(groupId, avgSimilarity, photoIdsCsv.split(",").map { it.toLong() })

internal fun SimilarGroupKind.toEntity(): EntitySimilarGroupKind = when (this) {
    SimilarGroupKind.NEAR_DUPLICATE -> EntitySimilarGroupKind.NEAR_DUPLICATE
    SimilarGroupKind.BURST -> EntitySimilarGroupKind.BURST
    SimilarGroupKind.VISUALLY_SIMILAR -> EntitySimilarGroupKind.VISUALLY_SIMILAR
}

internal fun SimilarGroupKindResult.toEntity(): EntitySimilarGroupKind = when (this) {
    SimilarGroupKindResult.NEAR_DUPLICATE -> EntitySimilarGroupKind.NEAR_DUPLICATE
    SimilarGroupKindResult.BURST -> EntitySimilarGroupKind.BURST
}
