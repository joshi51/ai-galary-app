package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupEntity
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DuplicateGroupDao {

    @Query(
        """
        SELECT dgm.groupId AS groupId, dg.contentHash AS contentHash,
               GROUP_CONCAT(dgm.photoId) AS photoIdsCsv, SUM(p.sizeBytes) AS totalSizeBytes
        FROM duplicate_group_members dgm
        JOIN duplicate_groups dg ON dg.id = dgm.groupId
        JOIN photos p ON p.mediaStoreId = dgm.photoId
        GROUP BY dgm.groupId
        """,
    )
    fun observeGroups(): Flow<List<DuplicateGroupRow>>

    @Query("DELETE FROM duplicate_groups")
    suspend fun deleteAllGroups()

    @Insert
    suspend fun insertGroup(group: DuplicateGroupEntity): Long

    @Upsert
    suspend fun upsertMember(member: DuplicateGroupMemberEntity)

    @Query("DELETE FROM duplicate_group_members WHERE photoId = :photoId")
    suspend fun removeMember(photoId: Long)

    /** Deletes any duplicate group left with fewer than 2 members — a "group" of one photo isn't
     *  a group, per the design spec §7. Call after any single-member removal. */
    @Query(
        "DELETE FROM duplicate_groups WHERE id NOT IN " +
            "(SELECT groupId FROM duplicate_group_members GROUP BY groupId HAVING COUNT(*) >= 2)",
    )
    suspend fun deleteUndersizedGroups()

    /** Replaces every duplicate group in one transaction — a full re-run supersedes prior groupings. */
    @Transaction
    suspend fun replaceAllGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
        deleteAllGroups()
        for ((hash, photoIds) in photoIdGroupsByHash) {
            if (photoIds.size < 2) continue
            val groupId = insertGroup(DuplicateGroupEntity(contentHash = hash))
            for (photoId in photoIds) upsertMember(DuplicateGroupMemberEntity(photoId, groupId))
        }
    }

    data class DuplicateGroupRow(
        val groupId: Long,
        val contentHash: String,
        val photoIdsCsv: String,
        val totalSizeBytes: Long,
    )
}
