package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "organization_operations",
    foreignKeys = [
        ForeignKey(entity = OrganizationPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = CASCADE),
    ],
    indices = [Index("planId")],
)
data class OrganizationOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val opType: String,
    val source: String?,
    val destination: String,
    val reason: String,
    val confidence: Float?,
    val memberPhotoIdsCsv: String?,
    val reviewStatus: String,
    val executionResult: String?,
    val executionError: String?,
)
