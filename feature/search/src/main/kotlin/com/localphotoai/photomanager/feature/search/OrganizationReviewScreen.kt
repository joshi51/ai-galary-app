package com.localphotoai.photomanager.feature.search

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import com.localphotoai.photomanager.fsops.OperationExecutionResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationReviewScreen(
    plan: OrganizationPlan,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrganizationReviewViewModel = hiltViewModel(),
) {
    LaunchedEffect(plan.id) { viewModel.loadPlan(plan) }

    val operations by viewModel.operations.collectAsState()
    val execution by viewModel.execution.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val awaiting = execution as? ExecutionUiState.AwaitingConsent ?: return@rememberLauncherForActivityResult
        viewModel.onConsentResult(awaiting.operation, result.resultCode == Activity.RESULT_OK)
    }

    // One IntentSender launch per AwaitingConsent state — the ViewModel advances to the next
    // operation (or a new AwaitingConsent) only after onConsentResult runs, so this never
    // launches more than one dialog at a time.
    LaunchedEffect(execution) {
        val awaiting = execution as? ExecutionUiState.AwaitingConsent ?: return@LaunchedEffect
        consentLauncher.launch(IntentSenderRequest.Builder(awaiting.intentSender).build())
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Review Organization Plan") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = execution) {
                is ExecutionUiState.Done -> ExecutionSummary(state.results, onBack)
                is ExecutionUiState.Running -> Text("Executing…", modifier = Modifier.padding(16.dp))
                is ExecutionUiState.AwaitingConsent -> Text("Waiting for permission…", modifier = Modifier.padding(16.dp))
                is ExecutionUiState.NotStarted -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::onApproveAll) { Text("Approve all") }
                        OutlinedButton(onClick = viewModel::onRejectAll) { Text("Reject all") }
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(operations, key = { it.operation.id }) { state ->
                            OperationRow(
                                state = state,
                                onToggled = { approved -> viewModel.onOperationToggled(state.operation.id, approved) },
                                onDestinationEdited = { viewModel.onDestinationEdited(state.operation.id, it) },
                                onMemberToggled = { photoId, included -> viewModel.onMemberToggled(state.operation.id, photoId, included) },
                            )
                        }
                    }
                    val anyApproved = operations.any { it.status == ReviewStatus.APPROVED || it.status == ReviewStatus.EDITED }
                    Button(
                        enabled = anyApproved,
                        onClick = viewModel::onExecuteConfirmed,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Text("Execute")
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(
    state: ReviewOperationState,
    onToggled: (Boolean) -> Unit,
    onDestinationEdited: (String) -> Unit,
    onMemberToggled: (Long, Boolean) -> Unit,
) {
    var destinationText by remember(state.operation.id) { mutableStateOf(state.editedDestination ?: state.operation.destination) }

    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = state.status == ReviewStatus.APPROVED || state.status == ReviewStatus.EDITED,
                    onCheckedChange = onToggled,
                )
                Column {
                    Text("${state.operation.opType}: ${state.operation.reason}", style = MaterialTheme.typography.bodyMedium)
                    state.operation.confidence?.let {
                        Text("Confidence: ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            OutlinedTextField(
                value = destinationText,
                onValueChange = {
                    destinationText = it
                    onDestinationEdited(it)
                },
                label = { Text(if (state.operation.opType.name == "CREATE_ALBUM") "Album name" else "Destination") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (state.operation.memberPhotoIds.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("${state.operation.memberPhotoIds.size - state.excludedMemberIds.size} of ${state.operation.memberPhotoIds.size} photos included")
                }
                for (photoId in state.operation.memberPhotoIds) {
                    Row {
                        Checkbox(
                            checked = photoId !in state.excludedMemberIds,
                            onCheckedChange = { included -> onMemberToggled(photoId, included) },
                        )
                        Text("Photo $photoId")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionSummary(results: List<OperationExecutionResult>, onBack: () -> Unit) {
    val succeeded = results.count { it.success }
    val failed = results.size - succeeded
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("$succeeded succeeded, $failed failed", style = MaterialTheme.typography.titleMedium)
        for (result in results.filter { !it.success }) {
            Text("Operation ${result.operationId}: ${result.error}", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
    }
}
