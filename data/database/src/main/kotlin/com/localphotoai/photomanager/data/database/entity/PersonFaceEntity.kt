package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Which person a face currently belongs to. [faceId] is the primary key rather than a composite
 * (personId, faceId) key — a face belongs to at most one person at any instant (clustering
 * assigns it to exactly one cluster; merge/split reassign this single row), even though
 * ARCHITECTURE.md's ER diagram models the relationship as many-to-many for future flexibility.
 */
@Entity(
    tableName = "person_faces",
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FaceEntity::class, parentColumns = ["id"], childColumns = ["faceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("personId")],
)
data class PersonFaceEntity(
    @PrimaryKey val faceId: Long,
    val personId: Long,
    val clusterConfidence: Float,
)
