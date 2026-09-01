package com.localphotoai.photomanager.domain.person

/** A face awaiting clustering, with its (already L2-normalized) embedding vector. */
data class FaceEmbeddingForClustering(val faceId: Long, val vector: FloatArray)

/** An existing person's cluster, identified by its running (unnormalized) centroid sum. */
data class ExistingClusterCentroid(val personId: Long, val centroidSum: FloatArray)

sealed class ClusterOutcome {
    abstract val faceId: Long
    abstract val confidence: Float

    data class AssignedToExisting(
        override val faceId: Long,
        val personId: Long,
        override val confidence: Float,
    ) : ClusterOutcome()

    /** Faces sharing the same [newClusterIndex] should become one new (unnamed) Person together. */
    data class AssignedToNewCluster(
        override val faceId: Long,
        val newClusterIndex: Int,
        override val confidence: Float,
    ) : ClusterOutcome()
}

data class ClusteringResult(val outcomes: List<ClusterOutcome>, val newClusterCount: Int)

/**
 * Greedy nearest-centroid clustering: each face is compared against every current cluster
 * centroid (existing people plus any new clusters formed earlier in this same run) and joins
 * the closest one if its cosine similarity clears [similarityThreshold]; otherwise it seeds a
 * brand-new cluster. This deliberately favors precision over recall — per the product
 * requirement that one real person may end up as multiple separate clusters rather than risk
 * merging two different people, since merging is a supported, safe user action but an incorrect
 * automatic merge is not easily undoable without a manual split.
 *
 * [DEFAULT_SIMILARITY_THRESHOLD] is an untuned heuristic — no real face-photo dataset was
 * available to calibrate it against (see the Phase 4/5 notes in ARCHITECTURE.md). Revisit once
 * real photos are available to validate clustering quality.
 *
 * Deterministic given a fixed input order (callers should sort [faces] e.g. by `faceId`) — later
 * faces are compared against centroids as updated by earlier faces in the same call, so
 * processing order affects the exact clustering, by design (this is what lets faces "grow" a
 * cluster's centroid as more members are found within one run).
 */
object FaceClusterer {

    const val ALGORITHM_VERSION = 1
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.6f

    fun cluster(
        faces: List<FaceEmbeddingForClustering>,
        existingClusters: List<ExistingClusterCentroid>,
        similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    ): ClusteringResult {
        val result = com.localphotoai.photomanager.domain.clustering.NearestCentroidClusterer.cluster(
            items = faces.map { com.localphotoai.photomanager.domain.clustering.EmbeddingForClustering(it.faceId, it.vector) },
            existingClusters = existingClusters.map {
                com.localphotoai.photomanager.domain.clustering.ExistingCentroid(it.personId, it.centroidSum)
            },
            similarityThreshold = similarityThreshold,
        )
        val outcomes = result.assignments.map { assignment ->
            when (assignment) {
                is com.localphotoai.photomanager.domain.clustering.ClusterAssignment.ToExisting ->
                    ClusterOutcome.AssignedToExisting(assignment.id, assignment.groupId, assignment.confidence)
                is com.localphotoai.photomanager.domain.clustering.ClusterAssignment.ToNew ->
                    ClusterOutcome.AssignedToNewCluster(assignment.id, assignment.newClusterIndex, assignment.confidence)
            }
        }
        return ClusteringResult(outcomes, result.newClusterCount)
    }
}
