package com.localphotoai.photomanager.domain.person

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import kotlinx.coroutines.flow.Flow

/**
 * Access to people/clusters and the clustering pipeline. Implemented in `:data:database` (Room
 * only). Clustering is deliberately conservative and never assumed correct — see
 * [FaceClusterer] — so this interface exposes the corrective actions a user needs: naming,
 * merging, splitting a face out, and marking a face as not actually a face.
 */
interface PersonRepository {

    fun observePeopleWithStats(): Flow<List<PersonWithStats>>

    fun observeMembers(personId: Long): Flow<List<PersonMember>>

    /** Faces with a current-version embedding, not yet assigned to any person, not marked incorrect. */
    suspend fun fetchFacesNeedingClustering(): List<FaceEmbeddingForClustering>

    suspend fun fetchExistingClusters(): List<ExistingClusterCentroid>

    /** [faces] is the same list that produced [result] — needed to update each cluster's centroid sum. */
    suspend fun applyClusteringResult(faces: List<FaceEmbeddingForClustering>, result: ClusteringResult)

    suspend fun namePerson(personId: Long, name: String?)

    suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long)

    /** Moves [faceId] out of its current person into a brand-new, unnamed one. Returns the new person's id. */
    suspend fun splitFaceIntoNewPerson(faceId: Long): Long

    /** Flags [faceId] as not a real/usable face — detaches it from any person and excludes it from re-clustering. */
    suspend fun markFaceIncorrect(faceId: Long)

    fun observeClusteringProgress(): Flow<IndexingProgress>

    suspend fun updateClusteringProgress(progress: IndexingProgress)
}
