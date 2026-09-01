package com.localphotoai.photomanager.fsops

import com.localphotoai.photomanager.domain.organization.OperationHistoryRepository
import com.localphotoai.photomanager.domain.organization.OperationUndoExecutor
import com.localphotoai.photomanager.domain.organization.UndoLastOrganizationUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object FsopsModule {

    /** [PlanExecutor] implements [OperationUndoExecutor] directly — see its class doc. */
    @Provides
    fun provideOperationUndoExecutor(planExecutor: PlanExecutor): OperationUndoExecutor = planExecutor

    @Provides
    fun provideUndoLastOrganizationUseCase(
        operationHistoryRepository: OperationHistoryRepository,
        operationUndoExecutor: OperationUndoExecutor,
    ): UndoLastOrganizationUseCase = UndoLastOrganizationUseCase(operationHistoryRepository, operationUndoExecutor)
}
