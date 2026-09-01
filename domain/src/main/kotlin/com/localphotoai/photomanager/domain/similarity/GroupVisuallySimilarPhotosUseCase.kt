package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.clustering.ClusterAssignment

private const val TAG = "GroupVisuallySimilarPhotosUseCase"

/** Untuned heuristic, separate constant from face clustering's — different embedding space, no
 *  reason to assume the same numeric threshold transfers. See ARCHITECTURE.md's Phase 7 notes. */
const val VISUALLY_SIMILAR_THRESHOLD = 0.75f

class GroupVisuallySimilarPhotosUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val embeddings = repository.fetchAllSimilarityEmbeddings()
        val existing = repository.fetchExistingSimilarClusters()
        val result = clusterBySimilarity(embeddings, existing, VISUALLY_SIMILAR_THRESHOLD)
        val dtos = result.assignments.map { assignment ->
            when (assignment) {
                is ClusterAssignment.ToExisting -> ClusterAssignmentDto(assignment.id, assignment.groupId, null, assignment.confidence)
                is ClusterAssignment.ToNew -> ClusterAssignmentDto(assignment.id, null, assignment.newClusterIndex, assignment.confidence)
            }
        }
        repository.applyVisuallySimilarGroupingResult(embeddings, dtos, result.newClusterCount)
        logger.info(TAG, "Visually-similar grouping complete: ${result.newClusterCount} new group(s)")
        AppResult.Success(result.newClusterCount)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Visually-similar grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Visually-similar grouping failed: $message", cause = t))
    }
}
