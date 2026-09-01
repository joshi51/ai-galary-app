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

            SearchLocationSection(
                savedLocation = savedSearchLocation,
                onSave = onSaveSearchLocation,
                onClear = onClearSearchLocation,
            )

            Text(
                text = "Privacy",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = "All photo analysis and AI processing runs on this device. " +
                    "A detailed privacy breakdown arrives in Phase 11.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
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
