package com.localphotoai.photomanager.feature.search

import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.organization.GetOperationHistoryUseCase
import com.localphotoai.photomanager.domain.organization.OperationHistoryRepository
import com.localphotoai.photomanager.domain.organization.OperationRecord
import com.localphotoai.photomanager.domain.organization.OperationRecordResult
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.UndoResult
import com.localphotoai.photomanager.fsops.MediaStoreWriter
import com.localphotoai.photomanager.fsops.PlanExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UndoUiState {
    data object Idle : UndoUiState()
    data object Running : UndoUiState()
    /** Mirrors [com.localphotoai.photomanager.fsops.PlanExecutor]'s forward-execution consent
     * flow (API 30+) — see `OrganizationReviewViewModel`. Reversing a MOVE/RENAME touches the
     * same MediaStore row the forward operation did, so it needs the same per-operation consent. */
    data class AwaitingConsent(val record: OperationRecord, val intentSender: IntentSender) : UndoUiState()
    data class Done(val results: List<UndoResult>) : UndoUiState()
}

/**
 * Drives both the operation-history inspection list and "Undo last organization." Undo is
 * orchestrated here (not via [com.localphotoai.photomanager.domain.organization.UndoLastOrganizationUseCase]
 * directly) because it needs to pause for a per-operation write-consent dialog on API 30+, the
 * same real Android constraint that splits `OrganizationReviewViewModel`'s interactive execution
 * from `ConfirmOrganizationPlanUseCase`'s pure confirm step.
 */
@HiltViewModel
class OrganizationHistoryViewModel @Inject constructor(
    getOperationHistoryUseCase: GetOperationHistoryUseCase,
    private val operationHistoryRepository: OperationHistoryRepository,
    private val planExecutor: PlanExecutor,
    private val mediaStoreWriter: MediaStoreWriter,
) : ViewModel() {

    val history: StateFlow<List<OperationRecord>> = getOperationHistoryUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val undoUiState = MutableStateFlow<UndoUiState>(UndoUiState.Idle)
    val undoState: StateFlow<UndoUiState> = undoUiState.asStateFlow()

    private val pendingUndoQueue = ArrayDeque<OperationRecord>()
    private val collectedUndoResults = mutableListOf<UndoResult>()

    fun onUndoLastOrganizationClicked() {
        undoUiState.value = UndoUiState.Running
        viewModelScope.launch {
            val batchId = operationHistoryRepository.fetchLatestUndoableBatchId()
            val undoable = batchId?.let { operationHistoryRepository.fetchBatch(it) }
                ?.filter { it.result == OperationRecordResult.SUCCESS && it.reversible && !it.undone }
                .orEmpty()

            pendingUndoQueue.clear()
            pendingUndoQueue.addAll(undoable)
            collectedUndoResults.clear()
            processNextUndo()
        }
    }

    /** Called by the Composable once the user has responded to a write-consent dialog launched
     * for [UndoUiState.AwaitingConsent]. */
    fun onUndoConsentResult(record: OperationRecord, granted: Boolean) {
        viewModelScope.launch {
            collectedUndoResults += if (granted) {
                planExecutor.undo(record)
            } else {
                UndoResult(record.id, success = false, error = "Write consent denied")
            }
            processNextUndo()
        }
    }

    fun dismissUndoResult() {
        undoUiState.value = UndoUiState.Idle
    }

    private fun processNextUndo() {
        val record = pendingUndoQueue.removeFirstOrNull()
        if (record == null) {
            finishUndo()
            return
        }
        val needsConsent = record.opType in setOf(OperationType.MOVE, OperationType.RENAME) && !mediaStoreWriter.isPreApi30()
        if (needsConsent) {
            undoUiState.value = UndoUiState.AwaitingConsent(record, mediaStoreWriter.writeRequestIntentSender(uriForRecord(record)))
        } else {
            viewModelScope.launch {
                collectedUndoResults += planExecutor.undo(record)
                processNextUndo()
            }
        }
    }

    private fun finishUndo() {
        val results = collectedUndoResults.toList()
        undoUiState.value = UndoUiState.Done(results)
        val undoneIds = results.filter { it.success }.map { it.recordId }
        if (undoneIds.isNotEmpty()) {
            viewModelScope.launch { operationHistoryRepository.markUndone(undoneIds) }
        }
    }

    private fun uriForRecord(record: OperationRecord): Uri {
        val photoId = requireNotNull(record.source).substringAfterLast("/").toLong()
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
    }
}
