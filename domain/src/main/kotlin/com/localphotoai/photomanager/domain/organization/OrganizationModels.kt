package com.localphotoai.photomanager.domain.organization

import kotlinx.coroutines.flow.Flow

enum class OrganizationCategory {
    SCREENSHOTS, BY_DATE, TRIP, ARCHIVE
}

enum class OperationType {
    MOVE, COPY, RENAME, CREATE_FOLDER, CREATE_ALBUM
}

enum class ReviewStatus {
    PENDING, APPROVED, REJECTED, EDITED
}

/**
 * One proposed filesystem/album action. [source] is a single photo's current URI for
 * MOVE/COPY/RENAME, null for CREATE_FOLDER/CREATE_ALBUM. [destination] is a target path for
 * file operations, or the album name for CREATE_ALBUM. [memberPhotoIds] is populated only for
 * CREATE_ALBUM — one CREATE_ALBUM operation covers every member photo as plan-level detail,
 * not one operation per photo (see the Phase 9 design spec §2).
 */
data class OrganizationOperation(
    val id: Long = 0,
    val opType: OperationType,
    val source: String?,
    val destination: String,
    val reason: String,
    val confidence: Float?,
    val memberPhotoIds: List<Long> = emptyList(),
    val reviewStatus: ReviewStatus = ReviewStatus.PENDING,
    val executionResult: Boolean? = null,
    val executionError: String? = null,
)

data class OrganizationPlan(
    val id: Long = 0,
    val requestText: String,
    val category: OrganizationCategory,
    val createdAtMs: Long,
    val operations: List<OrganizationOperation>,
)

/** Access to persisted organization plans. Implemented in `:data:database` (Room only). */
interface OrganizationPlanRepository {
    suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan
    suspend fun fetchPlan(planId: Long): OrganizationPlan?
    fun observePlan(planId: Long): Flow<OrganizationPlan?>
    suspend fun updateOperation(operation: OrganizationOperation)
}

/** Access to the virtual, in-app-only album collection. Implemented in `:data:database` (Room only). */
interface AlbumRepository {
    suspend fun createAlbum(name: String, photoIds: List<Long>): Long
}
