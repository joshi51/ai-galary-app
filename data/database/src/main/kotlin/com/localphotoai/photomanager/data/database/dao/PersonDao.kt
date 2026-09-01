package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.PersonEntity
import com.localphotoai.photomanager.data.database.entity.PersonFaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Query(
        """
        SELECT
            p.id AS id, p.name AS name, p.createdAt AS createdAt, p.clusterAlgoVersion AS clusterAlgoVersion,
            (SELECT photos.uri FROM faces JOIN photos ON faces.photoId = photos.mediaStoreId
                WHERE faces.id = p.representativeFaceId) AS representativePhotoUri,
            COUNT(DISTINCT f.photoId) AS photoCount,
            COUNT(pf.faceId) AS faceCount,
            AVG(pf.clusterConfidence) AS averageConfidence
        FROM people p
        LEFT JOIN person_faces pf ON pf.personId = p.id
        LEFT JOIN faces f ON f.id = pf.faceId
        GROUP BY p.id
        ORDER BY p.createdAt DESC
        """,
    )
    fun observeAllWithStats(): Flow<List<PersonWithStatsRow>>

    @Query(
        """
        SELECT faces.id AS faceId, photos.mediaStoreId AS photoMediaStoreId, photos.uri AS photoUri,
               photos.filename AS photoFilename
        FROM person_faces
        JOIN faces ON faces.id = person_faces.faceId
        JOIN photos ON photos.mediaStoreId = faces.photoId
        WHERE person_faces.personId = :personId
        """,
    )
    fun observeMembers(personId: Long): Flow<List<PersonMemberRow>>

    @Query(
        """
        SELECT faces.id AS faceId, embeddings.vector AS vector
        FROM faces
        JOIN embeddings ON embeddings.faceId = faces.id
        WHERE faces.markedIncorrect = 0 AND faces.id NOT IN (SELECT faceId FROM person_faces)
        """,
    )
    suspend fun getFacesNeedingClustering(): List<FaceForClusteringRow>

    @Query("SELECT id AS personId, centroidSum FROM people")
    suspend fun getExistingClusters(): List<ExistingClusterRow>

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Update
    suspend fun updatePerson(person: PersonEntity)

    @Query("SELECT * FROM people WHERE id = :personId")
    suspend fun getPerson(personId: Long): PersonEntity?

    @Delete
    suspend fun deletePerson(person: PersonEntity)

    @Upsert
    suspend fun upsertPersonFace(personFace: PersonFaceEntity)

    @Query("SELECT * FROM person_faces WHERE faceId = :faceId")
    suspend fun getPersonFace(faceId: Long): PersonFaceEntity?

    @Query("DELETE FROM person_faces WHERE faceId = :faceId")
    suspend fun deletePersonFace(faceId: Long)

    @Query("UPDATE person_faces SET personId = :targetPersonId WHERE personId = :sourcePersonId")
    suspend fun reassignAllFaces(sourcePersonId: Long, targetPersonId: Long)

    @Query("SELECT COUNT(*) FROM person_faces WHERE personId = :personId")
    suspend fun countFacesForPerson(personId: Long): Int

    @Query("SELECT faceId FROM person_faces WHERE personId = :personId LIMIT 1")
    suspend fun anyFaceIdForPerson(personId: Long): Long?

    @Query("UPDATE people SET name = :name WHERE id = :personId")
    suspend fun setName(personId: Long, name: String?)

    @Query("UPDATE people SET representativeFaceId = :faceId WHERE id = :personId")
    suspend fun setRepresentativeFace(personId: Long, faceId: Long?)

    data class PersonWithStatsRow(
        val id: Long,
        val name: String?,
        val representativePhotoUri: String?,
        val createdAt: Long,
        val clusterAlgoVersion: Int,
        val photoCount: Int,
        val faceCount: Int,
        val averageConfidence: Float?,
    )

    data class PersonMemberRow(
        val faceId: Long,
        val photoMediaStoreId: Long,
        val photoUri: String,
        val photoFilename: String,
    )

    data class FaceForClusteringRow(val faceId: Long, val vector: ByteArray)

    data class ExistingClusterRow(val personId: Long, val centroidSum: ByteArray)
}
