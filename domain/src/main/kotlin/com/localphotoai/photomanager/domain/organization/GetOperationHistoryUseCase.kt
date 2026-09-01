package com.localphotoai.photomanager.domain.organization

import kotlinx.coroutines.flow.Flow

class GetOperationHistoryUseCase(
    private val operationHistoryRepository: OperationHistoryRepository,
) {
    operator fun invoke(): Flow<List<OperationRecord>> = operationHistoryRepository.observeHistory()
}
