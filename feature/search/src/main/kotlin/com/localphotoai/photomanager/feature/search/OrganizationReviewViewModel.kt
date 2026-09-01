package com.localphotoai.photomanager.feature.search

import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.organization.ConfirmOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.OperationDecision
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import com.localphotoai.photomanager.fsops.MediaStoreWriter
import com.localphotoai.photomanager.fsops.OperationExecutionResult
import com.localphotoai.photomanager.fsops.PlanExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewOperationState(
    val operation: OrganizationOperation,
    val status: ReviewStatus = ReviewStatus.PENDING,
    val editedDestination: String? = null,
    val excludedMemberIds: Set<Long> = emptySet(),
)

sealed class ExecutionUiState {
    object NotStarted : ExecutionUiState()
    object Running : ExecutionUiState()
    /** API 30+ only — see [MediaStoreWriter]. The Composable launches [intentSender] via
     * `rememberLauncherForActivityResult` and reports the result back via [onConsentResult]. */
    data class AwaitingConsent(val operation: OrganizationOperation, val intentSender: IntentSender) : ExecutionUiState()
    data class Done(val results: List<OperationExecutionResult>) : ExecutionUiState()
}

@HiltViewModel
class OrganizationReviewViewModel @Inject constructor(
    private val confirmOrganizationPlanUseCase: ConfirmOrganizationPlanUseCase,
    private val planExecutor: PlanExecutor,
    private val mediaStoreWriter: MediaStoreWriter,
) : ViewModel() {

    private val operationStates = MutableStateFlow<List<ReviewOperationState>>(emptyList())
    val operations: StateFlow<List<ReviewOperationState>> = operationStates.asStateFlow()

    private val executionState = MutableStateFlow<ExecutionUiState>(ExecutionUiState.NotStarted)
    val execution: StateFlow<ExecutionUiState> = executionState.asStateFlow()

    private var planId: Long = 0
    private val pendingQueue = ArrayDeque<OrganizationOperation>()
    private val collectedResults = mutableListOf<OperationExecutionResult>()

    fun loadPlan(plan: OrganizationPlan) {
        planId = plan.id
        operationStates.value = plan.operations.map { ReviewOperationState(it) }
    }

    fun onApproveAll() = operationStates.update { list -> list.map { it.copy(status = ReviewStatus.APPROVED) } }

    fun onRejectAll() = operationStates.update { list -> list.map { it.copy(status = ReviewStatus.REJECTED) } }

    fun onOperationToggled(operationId: Long, approved: Boolean) = operationStates.update { list ->
        list.map { if (it.operation.id == operationId) it.copy(status = if (approved) ReviewStatus.APPROVED else ReviewStatus.REJECTED) else it }
    }

    fun onDestinationEdited(operationId: Long, newDestination: String) = operationStates.update { list ->
        list.map { if (it.operation.id == operationId) it.copy(status = ReviewStatus.EDITED, editedDestination = newDestination) else it }
    }

    fun onMemberToggled(operationId: Long, photoId: Long, included: Boolean) = operationStates.update { list ->
        list.map { state ->
            if (state.operation.id != operationId) return@map state
            val excluded = if (included) state.excludedMemberIds - photoId else state.excludedMemberIds + photoId
            state.copy(excludedMemberIds = excluded)
        }
    }

    fun onExecuteConfirmed() {
        executionState.value = ExecutionUiState.Running
        viewModelScope.launch {
            val decisions = operationStates.value
                .filter { it.status == ReviewStatus.APPROVED || it.status == ReviewStatus.EDITED }
                .map { state ->
                    OperationDecision(
                        operationId = state.operation.id,
                        status = state.status,
                        editedDestination = state.editedDestination,
                        editedMemberPhotoIds = if (state.excludedMemberIds.isEmpty()) {
                            null
                        } else {
                            state.operation.memberPhotoIds - state.excludedMemberIds
                        },
                    )
                }

            when (val confirmed = confirmOrganizationPlanUseCase.confirm(planId, decisions)) {
                is AppResult.Success -> {
                    pendingQueue.clear()
                    pendingQueue.addAll(
                        confirmed.value.operations.filter {
                            it.reviewStatus == ReviewStatus.APPROVED || it.reviewStatus == ReviewStatus.EDITED
                        },
                    )
                    collectedResults.clear()
                    processNext()
                }
                is AppResult.Failure -> executionState.value = ExecutionUiState.Done(emptyList())
            }
        }
    }

    /** Called by the Composable once the user has responded to a write-consent dialog launched
     * for [ExecutionUiState.AwaitingConsent]. */
    fun onConsentResult(operation: OrganizationOperation, granted: Boolean) {
        viewModelScope.launch {
            collectedResults += if (granted) {
                planExecutor.executeFileOperation(operation)
            } else {
                OperationExecutionResult(operation.id, success = false, error = "Write consent denied")
            }
            processNext()
        }
    }

    /** Walks the approved-operation queue one at a time — never all at once — so a `MOVE`/
     * `RENAME` needing write consent (API 30+) can pause for exactly one [IntentSender] before
     * the next operation starts, mirroring `DuplicatesScreen`'s per-photo `createDeleteRequest`
     * pattern (Phase 7) rather than introducing a new batched-consent code path. */
    private fun processNext() {
        val operation = pendingQueue.removeFirstOrNull()
        if (operation == null) {
            executionState.value = ExecutionUiState.Done(collectedResults.toList())
            return
        }
        when {
            operation.opType == OperationType.CREATE_ALBUM -> {
                // ConfirmOrganizationPlanUseCase already created the album (Task 5) — record a
                // synthetic success rather than re-creating it here.
                collectedResults += OperationExecutionResult(operation.id, success = true)
                processNext()
            }
            operation.opType in setOf(OperationType.MOVE, OperationType.RENAME) && !mediaStoreWriter.isPreApi30() -> {
                val uri = uriForOperation(operation)
                executionState.value = ExecutionUiState.AwaitingConsent(operation, mediaStoreWriter.writeRequestIntentSender(uri))
            }
            else -> viewModelScope.launch {
                collectedResults += planExecutor.executeFileOperation(operation)
                processNext()
            }
        }
    }

    private fun uriForOperation(operation: OrganizationOperation): Uri {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
    }
}
