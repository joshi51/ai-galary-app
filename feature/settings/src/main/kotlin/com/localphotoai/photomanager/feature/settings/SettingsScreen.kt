package com.localphotoai.photomanager.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.ThemeMode
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false }, modifier = modifier)
        return
    }

    val themeMode by viewModel.themeMode.collectAsState()
    val modelDownloadState by viewModel.modelDownloadState.collectAsState()
    val llmModelDownloadState by viewModel.llmModelDownloadState.collectAsState()
    val savedSearchLocation by viewModel.savedSearchLocation.collectAsState()
    SettingsScreenContent(
        modifier = modifier,
        themeMode = themeMode,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        modelDownloadState = modelDownloadState,
        onDownloadModelClicked = viewModel::onDownloadModelClicked,
        llmModelDownloadState = llmModelDownloadState,
        onDownloadLlmModelClicked = viewModel::onDownloadLlmModelClicked,
        savedSearchLocation = savedSearchLocation,
        onSaveSearchLocation = viewModel::onSaveSearchLocation,
        onClearSearchLocation = viewModel::onClearSearchLocation,
        onOpenDiagnostics = { showDiagnostics = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    modelDownloadState: ModelDownloadState,
    onDownloadModelClicked: () -> Unit,
    llmModelDownloadState: LlmModelDownloadState,
    onDownloadLlmModelClicked: () -> Unit,
    savedSearchLocation: SavedSearchLocation?,
    onSaveSearchLocation: (latitude: Double, longitude: Double, radiusKm: Double) -> Unit,
    onClearSearchLocation: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(text = "Appearance", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        selected = mode == themeMode,
                        onClick = { onThemeModeSelected(mode) },
                    ) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Text(
                text = "AI Models",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = "The face-embedding model is downloaded once, over your own connection, " +
                    "and never leaves your device again — no photos or embeddings are ever uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            ModelDownloadRow(state = modelDownloadState, onDownloadClicked = onDownloadModelClicked)

            Text(
                text = "Search assistant model",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Enables natural-language photo search (e.g. \"Show me photos of Rahul from 2025\"). " +
                    "Runs fully on-device, same as the face-embedding model above.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            LlmModelDownloadRow(state = llmModelDownloadState, onDownloadClicked = onDownloadLlmModelClicked)
            Text(
                text = "Built with Llama.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            SearchLocationSection(
                savedLocation = savedSearchLocation,
                onSave = onSaveSearchLocation,
                onClear = onClearSearchLocation,
            )

            PrivacySection(onOpenDiagnostics = onOpenDiagnostics)
        }
    }
}

@Composable
private fun PrivacySection(onOpenDiagnostics: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(text = "Privacy", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Verified by this app's Phase 11 privacy audit:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        val facts = listOf(
            "Your photos are never uploaded — indexing, face detection, and search read them " +
                "only through Android's MediaStore, in place, on this device.",
            "Face embeddings (the numbers used to recognize people) are generated on-device " +
                "and stored only in this app's local database — never transmitted anywhere.",
            "Natural-language search and photo organization run on a local LLM (llama.cpp), " +
                "entirely on this device — no query or photo content is ever sent to a server.",
            "This app has no analytics or telemetry SDK of any kind, so none is running, by " +
                "default or otherwise.",
            "The INTERNET permission exists only for the explicit \"Download\" buttons above — " +
                "every other feature (indexing, faces, search, organization) works with the " +
                "device fully offline.",
            "This app's database lives in Android's app-private storage, readable only by this " +
                "app on a non-rooted device, and is deleted automatically if the app is " +
                "uninstalled. Automatic cloud/system backup of that storage is disabled for this " +
                "app. Photo thumbnails in the grid are decoded on demand and held only in " +
                "memory while visible — nothing is written to a persistent thumbnail cache.",
            "Error logs never include a photo's filename, path, or content — only counts and " +
                "non-identifying status codes.",
        )
        for (fact in facts) {
            Text(
                text = "•  $fact",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 8.dp)) {
            Text("View diagnostics")
        }
    }
}

@Composable
private fun ModelDownloadRow(state: ModelDownloadState, onDownloadClicked: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is ModelDownloadState.NotDownloaded -> Button(onClick = onDownloadClicked, modifier = modifier) {
            Text("Download face-embedding model")
        }
        is ModelDownloadState.Downloading -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Downloading… ${state.progressPercent}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        is ModelDownloadState.Ready -> Text(
            text = "Face-embedding model ready.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        is ModelDownloadState.Failed -> Column(modifier = modifier) {
            Text(
                text = "Download failed: ${state.error}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onDownloadClicked, modifier = Modifier.padding(top = 8.dp)) {
                Text("Retry download")
            }
        }
    }
}

@Composable
private fun LlmModelDownloadRow(state: LlmModelDownloadState, onDownloadClicked: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is LlmModelDownloadState.NotDownloaded -> Button(onClick = onDownloadClicked, modifier = modifier) {
            Text("Download search assistant model")
        }
        is LlmModelDownloadState.Downloading -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Downloading… ${state.percent}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        is LlmModelDownloadState.Ready -> Text(
            text = "Search assistant model ready.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        is LlmModelDownloadState.Failed -> Column(modifier = modifier) {
            Text(
                text = "Download failed: ${state.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onDownloadClicked, modifier = Modifier.padding(top = 8.dp)) {
                Text("Retry download")
            }
        }
    }
}

@Composable
private fun SearchLocationSection(
    savedLocation: SavedSearchLocation?,
    onSave: (latitude: Double, longitude: Double, radiusKm: Double) -> Unit,
    onClear: () -> Unit,
) {
    var latitudeText by remember(savedLocation) {
        mutableStateOf(savedLocation?.latitude?.toString() ?: "")
    }
    var longitudeText by remember(savedLocation) {
        mutableStateOf(savedLocation?.longitude?.toString() ?: "")
    }
    var radiusText by remember(savedLocation) {
        mutableStateOf(savedLocation?.radiusKm?.toString() ?: "10")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(text = "Search location", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Used by Search's \"near a saved location\" filter. Enter the coordinates " +
                "of a place you search near often (e.g. home).",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = latitudeText,
            onValueChange = { latitudeText = it },
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = longitudeText,
            onValueChange = { longitudeText = it },
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = radiusText,
            onValueChange = { radiusText = it },
            label = { Text("Radius (km)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val lat = latitudeText.toDoubleOrNull()
                    val lon = longitudeText.toDoubleOrNull()
                    val radius = radiusText.toDoubleOrNull()
                    if (lat != null && lon != null && radius != null) {
                        onSave(lat, lon, radius)
                    }
                },
            ) {
                Text("Save")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }
    }
}
