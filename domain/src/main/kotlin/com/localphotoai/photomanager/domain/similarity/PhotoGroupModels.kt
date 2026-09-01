package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.domain.clustering.ClusterAssignment
import com.localphotoai.photomanager.domain.clustering.EmbeddingForClustering as ClusteringItem
import com.localphotoai.photomanager.domain.clustering.ExistingCentroid as ClusteringCentroid
import com.localphotoai.photomanager.domain.clustering.NearestCentroidClusterer
import com.localphotoai.photomanager.domain.clustering.NearestCentroidResult
import com.localphotoai.photomanager.domain.face.l2Normalize

data class PhotoForHashing(val photoId: Long, val uri: String)

data class PhotoForSimilarityEmbedding(
    val photoId: Long,
    val uri: String,
    val widthPx: Int,
    val heightPx: Int,
    val orientationDegrees: Int,
)

data class PhotoEmbeddingForSimilarity(val photoId: Long, val vector: FloatArray)

data class ExistingSimilarCentroid(val groupId: Long, val centroidSum: FloatArray)

data class DuplicateGroupSummary(val groupId: Long, val photoIds: List<Long>, val totalSizeBytes: Long)

data class SimilarGroupSummary(val groupId: Long, val avgSimilarity: Float, val photoIds: List<Long>)

enum class SimilarGroupKind { NEAR_DUPLICATE, BURST, VISUALLY_SIMILAR }

/** [ClusterAssignment] is Room/Android-free but this DTO keeps the repository interface from
 *  depending on the clustering package's sealed type directly, so callers pass plain data
 *  instead of re-importing clustering internals. */
data class ClusterAssignmentDto(val photoId: Long, val groupId: Long?, val newClusterIndex: Int?, val confidence: Float)

/** Pure helper: converts embeddings + existing centroids into a [NearestCentroidResult] for visual similarity. */
fun clusterBySimilarity(
    embeddings: List<PhotoEmbeddingForSimilarity>,
    existingClusters: List<ExistingSimilarCentroid>,
    similarityThreshold: Float,
): NearestCentroidResult {
    val items = embeddings.map { ClusteringItem(it.photoId, l2Normalize(it.vector)) }
    val centroids = existingClusters.map { ClusteringCentroid(it.groupId, it.centroidSum) }
    return NearestCentroidClusterer.cluster(items, centroids, similarityThreshold)
}
