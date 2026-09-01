package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "organization_plans")
data class OrganizationPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestText: String,
    val category: String,
    val createdAtMs: Long,
    val status: String,
)
