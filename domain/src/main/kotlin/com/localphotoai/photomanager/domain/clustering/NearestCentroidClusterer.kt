package com.localphotoai.photomanager.domain.clustering

import com.localphotoai.photomanager.domain.face.l2Normalize
import com.localphotoai.photomanager.domain.person.addVector
import com.localphotoai.photomanager.domain.person.cosineSimilarity

/** An item awaiting clustering, with its (already L2-normalized) embedding vector. */
data class EmbeddingForClustering(val id: Long, val vector: FloatArray)

/** An existing cluster, identified by its running (unnormalized) centroid sum. */
data class ExistingCentroid(val groupId: Long, val centroidSum: FloatArray)

sealed class ClusterAssignment {
    abstract val id: Long
    abstract val confidence: Float

    data class ToExisting(override val id: Long, val groupId: Long, override val confidence: Float) : ClusterAssignment()
    data class ToNew(override val id: Long, val newClusterIndex: Int, override val confidence: Float) : ClusterAssignment()
}

data class NearestCentroidResult(val assignments: List<ClusterAssignment>, val newClusterCount: Int)

/**
 * Greedy nearest-centroid clustering, extracted from Phase 5's `FaceClusterer` so both face
 * clustering and Phase 7's image-similarity clustering share one tested implementation — the
 * algorithm itself has nothing face-specific about it (see `FaceClusterer`, which now delegates
 * here). Same greedy, single-pass, precision-over-recall behavior: an item joins the closest
 * current cluster (existing groups plus any new ones formed earlier in this run) above
 * [similarityThreshold], or seeds a new cluster otherwise.
 */
object NearestCentroidClusterer {

    fun cluster(
        items: List<EmbeddingForClustering>,
        existingClusters: List<ExistingCentroid>,
        similarityThreshold: Float,
    ): NearestCentroidResult {
        val working = existingClusters.map { WorkingCluster(groupId = it.groupId, sum = it.centroidSum.copyOf()) }
            .toMutableList()
        val assignments = mutableListOf<ClusterAssignment>()
        var nextNewClusterIndex = 0

        for (candidate in items) {
            var bestCluster: WorkingCluster? = null
            var bestSimilarity = Float.NEGATIVE_INFINITY
            for (cluster in working) {
                val similarity = cosineSimilarity(l2Normalize(cluster.sum), candidate.vector)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = cluster
                }
            }

            if (bestCluster != null && bestSimilarity >= similarityThreshold) {
                bestCluster.sum = addVector(bestCluster.sum, candidate.vector)
                assignments += if (bestCluster.groupId != null) {
                    ClusterAssignment.ToExisting(candidate.id, bestCluster.groupId, bestSimilarity)
                } else {
                    ClusterAssignment.ToNew(candidate.id, bestCluster.newClusterIndex!!, bestSimilarity)
                }
            } else {
                val index = nextNewClusterIndex++
                working += WorkingCluster(groupId = null, newClusterIndex = index, sum = candidate.vector.copyOf())
                assignments += ClusterAssignment.ToNew(candidate.id, index, confidence = 1f)
            }
        }

        return NearestCentroidResult(assignments, nextNewClusterIndex)
    }

    private class WorkingCluster(
        val groupId: Long?,
        val newClusterIndex: Int? = null,
        var sum: FloatArray,
    )
}
