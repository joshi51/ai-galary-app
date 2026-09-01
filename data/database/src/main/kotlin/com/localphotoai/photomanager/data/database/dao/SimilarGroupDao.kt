package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.SimilarGroupEntity
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind
import com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SimilarGroupDao {

    @Query(
        """
        SELECT sgm.groupId AS groupId, sg.avgSimilarity AS avgSimilarity,
               GROUP_CONCAT(sgm.photoId) AS photoIdsCsv
        FROM similar_group_members sgm
        JOIN similar_groups sg ON sg.id = sgm.groupId
        WHERE sg.kind = :kind
        GROUP BY sgm.groupId
        """,
    )
    fun observeGroupsByKind(kind: SimilarGroupKind): Flow<List<SimilarGroupRow>>

    @Query(
        """
        SELECT sgm.groupId AS groupId, sg.avgSimilarity AS avgSimilarity,
               GROUP_CONCAT(sgm.photoId) AS photoIdsCsv
        FROM similar_group_members sgm
        JOIN similar_groups sg ON sg.id = sgm.groupId
        WHERE sg.kind = :kind
        GROUP BY sgm.groupId
        """,
    )
    suspend fun getGroupsByKind(kind: SimilarGroupKind): List<SimilarGroupRow>

    @Query("DELETE FROM similar_groups WHERE kind = :kind")
    suspend fun deleteGroupsByKind(kind: SimilarGroupKind)

    @Insert
    suspend fun insertGroup(group: SimilarGroupEntity): Long

    @Upsert
    suspend fun upsertMember(member: SimilarGroupMemberEntity)

    @Query("DELETE FROM similar_group_members WHERE photoId = :photoId")
    suspend fun removeMember(photoId: Long)

    /** Photo ids already assigned to a group of [kind] — used to exclude already-clustered
     *  photos from a fresh clustering pass, the same "not yet assigned" filter Phase 5's
     *  `fetchFacesNeedingClustering` applies for faces. */
    @Query(
        "SELECT sgm.photoId FROM similar_group_members sgm " +
            "JOIN similar_groups sg ON sg.id = sgm.groupId WHERE sg.kind = :kind",
    )
    suspend fun getPhotoIdsInGroupsOfKind(kind: SimilarGroupKind): List<Long>

    /** Deletes any group of [kind] left with fewer than 2 members — a "group" of one photo isn't
     *  a group, per the design spec §7. Call after any single-member removal. */
    @Query(
        "DELETE FROM similar_groups WHERE kind = :kind AND id NOT IN " +
            "(SELECT groupId FROM similar_group_members GROUP BY groupId HAVING COUNT(*) >= 2)",
    )
    suspend fun deleteUndersizedGroupsOfKind(kind: SimilarGroupKind)

    /** Replaces every group of [kind] in one transaction, keyed by an opaque cluster index (0, 1, 2, ...). */
    @Transaction
    suspend fun replaceGroupsOfKind(kind: SimilarGroupKind, groups: Map<Int, List<Pair<Long, Float>>>) {
        deleteGroupsByKind(kind)
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val avg = members.map { it.second }.average().toFloat()
            val groupId = insertGroup(SimilarGroupEntity(kind = kind, avgSimilarity = avg))
            for ((photoId, similarity) in members) {
                upsertMember(SimilarGroupMemberEntity(photoId, groupId, similarity))
            }
        }
    }

    data class SimilarGroupRow(
        val groupId: Long,
        val avgSimilarity: Float,
        val photoIdsCsv: String,
    )
}
