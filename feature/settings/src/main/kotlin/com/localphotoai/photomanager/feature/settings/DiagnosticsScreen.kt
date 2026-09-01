package com.localphotoai.photomanager.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import kotlin.math.ln
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.state.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        val current = snapshot
        if (current == null) {
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Text("AI models", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Face-embedding model", modelStateLabel(current.faceEmbeddingModelState))
            DiagnosticRow("Face-embedding model version", current.faceEmbeddingModelVersion.toString())
            DiagnosticRow("Similarity model version", current.similarityModelVersion.toString())
            DiagnosticRow("Search assistant model", llmModelStateLabel(current.llmModelState))
            DiagnosticRow("Search assistant model version", current.llmModelVersion.toString())

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Local processing", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Face detection", "On-device (ML Kit)")
            DiagnosticRow("Face embeddings", "On-device (TFLite)")
            DiagnosticRow("Natural-language search", "On-device (llama.cpp)")
            DiagnosticRow("Network use", "Only for the explicit model downloads above")

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Library", style = MaterialTheme.typography.titleMedium)
            DiagnosticRow("Indexed photos", current.statistics.photoCount.toString())
            DiagnosticRow("Detected faces", current.statistics.faceCount.toString())
            DiagnosticRow("People", current.statistics.peopleCount.toString())
            DiagnosticRow("Duplicate groups", current.statistics.duplicateGroupCount.toString())
            DiagnosticRow("Similar-photo groups", current.statistics.similarGroupCount.toString())
            DiagnosticRow("Total photo size", formatBytes(current.statistics.totalSizeBytes))
            DiagnosticRow("Database size", formatBytes(current.databaseSizeBytes))
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun modelStateLabel(state: ModelDownloadState): String = when (state) {
    is ModelDownloadState.NotDownloaded -> "Not downloaded"
    is ModelDownloadState.Downloading -> "Downloading (${state.progressPercent}%)"
    is ModelDownloadState.Ready -> "Ready — running locally"
    is ModelDownloadState.Failed -> "Download failed"
}

private fun llmModelStateLabel(state: LlmModelDownloadState): String = when (state) {
    is LlmModelDownloadState.NotDownloaded -> "Not downloaded"
    is LlmModelDownloadState.Downloading -> "Downloading (${state.percent}%)"
    is LlmModelDownloadState.Ready -> "Ready — running locally"
    is LlmModelDownloadState.Failed -> "Download failed"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, 4)
    val unit = "KMGT"[exponent - 1]
    val value = bytes / 1024.0.pow(exponent)
    return "%.1f %sB".format(value, unit)
}
