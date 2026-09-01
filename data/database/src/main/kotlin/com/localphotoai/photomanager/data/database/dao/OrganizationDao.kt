package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.localphotoai.photomanager.data.database.entity.OrganizationOperationEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizationDao {
    @Insert
    suspend fun insertPlan(plan: OrganizationPlanEntity): Long

    @Insert
    suspend fun insertOperations(operations: List<OrganizationOperationEntity>): List<Long>

    @Query("SELECT * FROM organization_plans WHERE id = :planId")
    suspend fun getPlan(planId: Long): OrganizationPlanEntity?

    @Query("SELECT * FROM organization_operations WHERE planId = :planId")
    suspend fun getOperations(planId: Long): List<OrganizationOperationEntity>

    @Query("SELECT * FROM organization_plans WHERE id = :planId")
    fun observePlan(planId: Long): Flow<OrganizationPlanEntity?>

    @Query("SELECT * FROM organization_operations WHERE planId = :planId")
    fun observeOperations(planId: Long): Flow<List<OrganizationOperationEntity>>

    @Update
    suspend fun updateOperation(operation: OrganizationOperationEntity)

    /** [OrganizationOperation] (the domain model) has no `planId` field — `updateOperation` in
     * [com.localphotoai.photomanager.data.database.OrganizationRepositoryImpl] needs this to
     * reconstruct the entity's required `planId` from just the operation's own id. */
    @Query("SELECT planId FROM organization_operations WHERE id = :operationId")
    suspend fun getPlanIdForOperation(operationId: Long): Long?
}
