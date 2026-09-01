package com.localphotoai.photomanager.feature.search

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.organization.OperationRecord
import com.localphotoai.photomanager.domain.organization.OperationRecordResult
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrganizationHistoryViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsState()
    val undoState by viewModel.undoState.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val awaiting = undoState as? UndoUiState.AwaitingConsent ?: return@rememberLauncherForActivityResult
        viewModel.onUndoConsentResult(awaiting.record, result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(undoState) {
        val awaiting = undoState as? UndoUiState.AwaitingConsent ?: return@LaunchedEffect
        consentLauncher.launch(IntentSenderRequest.Builder(awaiting.intentSender).build())
    }

    val canUndo = history.any { it.result == OperationRecordResult.SUCCESS && it.reversible && !it.undone }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Organization history") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = undoState) {
                is UndoUiState.Running -> Text("Undoing…", modifier = Modifier.padding(16.dp))
                is UndoUiState.AwaitingConsent -> Text("Waiting for permission…", modifier = Modifier.padding(16.dp))
                is UndoUiState.Done -> {
                    val succeeded = state.results.count { it.success }
                    val failed = state.results.size - succeeded
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (state.results.isEmpty()) "Nothing to undo." else "$succeeded undone, $failed failed",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        for (result in state.results.filter { !it.success }) {
                            Text("Record ${result.recordId}: ${result.error}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = viewModel::dismissUndoResult, modifier = Modifier.padding(top = 8.dp)) {
                            Text("OK")
                        }
                    }
                }
                is UndoUiState.Idle -> {
                    Button(
                        onClick = viewModel::onUndoLastOrganizationClicked,
                        enabled = canUndo,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text("Undo last organization")
                    }
                }
            }

            if (history.isEmpty()) {
                Text(
                    "No organization operations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(history, key = { it.id }) { record -> OperationRecordRow(record) }
                }
            }
        }
    }
}

@Composable
private fun OperationRecordRow(record: OperationRecord) {
    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "${record.opType}: ${record.destination}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                DateFormat.getDateTimeInstance().format(Date(record.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
            )
            Row {
                Text(
                    text = when {
                        record.result == OperationRecordResult.FAILURE -> "Failed: ${record.failureReason}"
                        record.undone -> "Succeeded — undone"
                        record.reversible -> "Succeeded — reversible"
                        else -> "Succeeded — not reversible"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

