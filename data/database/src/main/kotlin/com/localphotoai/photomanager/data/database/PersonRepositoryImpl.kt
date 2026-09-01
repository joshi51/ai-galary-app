package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.ClusteringStatusDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingDao
import com.localphotoai.photomanager.data.database.dao.FaceDao
import com.localphotoai.photomanager.data.database.dao.PersonDao
import com.localphotoai.photomanager.data.database.entity.ClusteringStatusEntity
import com.localphotoai.photomanager.data.database.entity.PersonEntity
import com.localphotoai.photomanager.data.database.entity.PersonFaceEntity
import com.localphotoai.photomanager.domain.person.ClusterOutcome
import com.localphotoai.photomanager.domain.person.ClusteringResult
import com.localphotoai.photomanager.domain.person.ExistingClusterCentroid
import com.localphotoai.photomanager.domain.person.FaceClusterer
import com.localphotoai.photomanager.domain.person.FaceEmbeddingForClustering
import com.localphotoai.photomanager.domain.person.PersonMember
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.person.addVector
import com.localphotoai.photomanager.domain.person.planMerge
import com.localphotoai.photomanager.domain.person.shouldDeletePersonAfterRemoval
import com.localphotoai.photomanager.domain.person.subtractVector
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersonRepositoryImpl @Inject constructor(
    private val personDao: PersonDao,
    private val clusteringStatusDao: ClusteringStatusDao,
    private val embeddingDao: EmbeddingDao,
    private val faceDao: FaceDao,
) : PersonRepository {

    override fun observePeopleWithStats(): Flow<List<PersonWithStats>> =
        personDao.observeAllWithStats().map { rows -> rows.map { it.toDomain() } }

    override fun observeMembers(personId: Long): Flow<List<PersonMember>> =
        personDao.observeMembers(personId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun fetchFacesNeedingClustering(): List<FaceEmbeddingForClustering> =
        personDao.getFacesNeedingClustering().map { it.toDomain() }

    override suspend fun fetchExistingClusters(): List<ExistingClusterCentroid> =
        personDao.getExistingClusters().map { it.toDomain() }

    override suspend fun applyClusteringResult(faces: List<FaceEmbeddingForClustering>, result: ClusteringResult) {
        val vectorsByFaceId = faces.associate { it.faceId to it.vector }
        val newClusterFaceIds = mutableMapOf<Int, MutableList<Long>>()

        for (outcome in result.outcomes) {
            when (outcome) {
                is ClusterOutcome.AssignedToExisting -> assignToExistingPerson(outcome, vectorsByFaceId.getValue(outcome.faceId))
                is ClusterOutcome.AssignedToNewCluster ->
                    newClusterFaceIds.getOrPut(outcome.newClusterIndex) { mutableListOf() }.add(outcome.faceId)
            }
        }

        val confidenceByFaceId = result.outcomes.associate { it.faceId to it.confidence }
        for ((_, faceIds) in newClusterFaceIds) {
            createPersonFromCluster(faceIds, vectorsByFaceId, confidenceByFaceId)
        }
    }

    private suspend fun assignToExistingPerson(outcome: ClusterOutcome.AssignedToExisting, vector: FloatArray) {
        personDao.upsertPersonFace(PersonFaceEntity(outcome.faceId, outcome.personId, outcome.confidence))
        val person = personDao.getPerson(outcome.personId) ?: return
        val updatedSum = addVector(bytesToFloatArray(person.centroidSum), vector)
        personDao.updatePerson(
            person.copy(
                centroidSum = floatArrayToBytes(updatedSum),
                memberCount = person.memberCount + 1,
                representativeFaceId = person.representativeFaceId ?: outcome.faceId,
            ),
        )
    }

    private suspend fun createPersonFromCluster(
        faceIds: List<Long>,
        vectorsByFaceId: Map<Long, FloatArray>,
        confidenceByFaceId: Map<Long, Float>,
    ) {
        var sum = FloatArray(vectorsByFaceId.getValue(faceIds.first()).size)
        for (faceId in faceIds) sum = addVector(sum, vectorsByFaceId.getValue(faceId))

        val personId = personDao.insertPerson(
            PersonEntity(
                name = null,
                representativeFaceId = faceIds.first(),
                createdAt = System.currentTimeMillis(),
                clusterAlgoVersion = FaceClusterer.ALGORITHM_VERSION,
                centroidSum = floatArrayToBytes(sum),
                memberCount = faceIds.size,
            ),
        )
        for (faceId in faceIds) {
            personDao.upsertPersonFace(PersonFaceEntity(faceId, personId, confidenceByFaceId.getValue(faceId)))
        }
    }

    override suspend fun namePerson(personId: Long, name: String?) {
        personDao.setName(personId, name?.trim()?.ifBlank { null })
    }

    override suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long) {
        val source = personDao.getPerson(sourcePersonId) ?: return
        val target = personDao.getPerson(targetPersonId) ?: return
        val outcome = planMerge(sourcePersonId, source.name, targetPersonId, target.name)

        personDao.reassignAllFaces(sourcePersonId, targetPersonId)
        val mergedSum = addVector(bytesToFloatArray(target.centroidSum), bytesToFloatArray(source.centroidSum))
        personDao.updatePerson(
            target.copy(
                name = outcome.resultingName,
                centroidSum = floatArrayToBytes(mergedSum),
                memberCount = target.memberCount + source.memberCount,
                representativeFaceId = target.representativeFaceId ?: source.representativeFaceId,
            ),
        )
        personDao.deletePerson(source)
    }

    override suspend fun splitFaceIntoNewPerson(faceId: Long): Long {
        val personFace = personDao.getPersonFace(faceId) ?: error("Face $faceId is not currently assigned to a person")
        val originalPerson = personDao.getPerson(personFace.personId)
            ?: error("Person ${personFace.personId} not found")
        val vectorBytes = embeddingDao.getVector(faceId) ?: error("No embedding stored for face $faceId")
        val vector = bytesToFloatArray(vectorBytes)

        val newPersonId = personDao.insertPerson(
            PersonEntity(
                name = null,
                representativeFaceId = faceId,
                createdAt = System.currentTimeMillis(),
                clusterAlgoVersion = originalPerson.clusterAlgoVersion,
                centroidSum = floatArrayToBytes(vector),
                memberCount = 1,
            ),
        )
        personDao.upsertPersonFace(PersonFaceEntity(faceId, newPersonId, clusterConfidence = 1f))
        removeFaceFromPerson(originalPerson, faceId, vector)
        return newPersonId
    }

    override suspend fun markFaceIncorrect(faceId: Long) {
        faceDao.markIncorrect(faceId)
        val personFace = personDao.getPersonFace(faceId) ?: return
        val person = personDao.getPerson(personFace.personId) ?: return
        val vectorBytes = embeddingDao.getVector(faceId)
        personDao.deletePersonFace(faceId)
        if (vectorBytes != null) {
            removeFaceFromPerson(person, faceId, bytesToFloatArray(vectorBytes))
        }
    }

    /** Shared cleanup after a face leaves [person]: recompute its centroid, or delete it if now empty. */
    private suspend fun removeFaceFromPerson(person: PersonEntity, removedFaceId: Long, removedVector: FloatArray) {
        val remainingCount = personDao.countFacesForPerson(person.id)
        if (shouldDeletePersonAfterRemoval(remainingCount)) {
            personDao.deletePerson(person)
            return
        }
        val remainingSum = subtractVector(bytesToFloatArray(person.centroidSum), removedVector)
        val newRepresentative = if (person.representativeFaceId == removedFaceId) {
            personDao.anyFaceIdForPerson(person.id)
        } else {
            person.representativeFaceId
        }
        personDao.updatePerson(
            person.copy(
                centroidSum = floatArrayToBytes(remainingSum),
                memberCount = remainingCount,
                representativeFaceId = newRepresentative,
            ),
        )
    }

    override fun observeClusteringProgress(): Flow<IndexingProgress> =
        clusteringStatusDao.observe().map { it?.toDomain() ?: IndexingProgress.IDLE }

    override suspend fun updateClusteringProgress(progress: IndexingProgress) {
        clusteringStatusDao.upsert(
            ClusteringStatusEntity(
                state = progress.state.name,
                itemsProcessed = progress.itemsProcessed,
                itemsTotal = progress.itemsTotal,
                lastRunAtMs = progress.lastRunAtMs,
                lastError = progress.lastError,
            ),
        )
    }

    private fun ClusteringStatusEntity.toDomain(): IndexingProgress = IndexingProgress(
        state = runCatching { IndexingState.valueOf(state) }.getOrDefault(IndexingState.IDLE),
        itemsProcessed = itemsProcessed,
        itemsTotal = itemsTotal,
        lastRunAtMs = lastRunAtMs,
        lastError = lastError,
    )
}
