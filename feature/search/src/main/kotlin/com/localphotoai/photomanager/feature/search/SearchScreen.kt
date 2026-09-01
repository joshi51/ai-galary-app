package com.localphotoai.photomanager.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import java.time.Year

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val people by viewModel.people.collectAsState()
    val savedLocation by viewModel.savedLocation.collectAsState()
    val filterState by viewModel.filterUiState.collectAsState()
    val results = viewModel.results.collectAsLazyPagingItems()
    val llmModelDownloadState by viewModel.llmModelDownloadState.collectAsState()
    val nlSearchUiState by viewModel.nlSearchUiState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            NlSearchBar(
                downloadState = llmModelDownloadState,
                onQuerySubmitted = viewModel::onNlQuerySubmitted,
            )
            NlSearchResultSection(nlSearchUiState)

            if (people.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No people found yet. Search needs at least one person discovered " +
                            "in the People tab first.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Scaffold
            }

            PersonPickerRow(
                people = people,
                selectedPersonIds = filterState.selectedPersonIds,
                onPersonToggled = viewModel::onPersonToggled,
            )
            YearPickerRow(
                selectedYear = filterState.selectedYear,
                onYearSelected = viewModel::onYearSelected,
            )
            if (savedLocation != null) {
                LocationFilterRow(
                    enabled = filterState.locationFilterEnabled,
                    onToggled = viewModel::onLocationFilterToggled,
                )
            }

            if (filterState.selectedPersonIds.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Select at least one person above to search.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (results.itemCount == 0) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No photos match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(results.itemCount) { index ->
                        val photo = results[index]
                        if (photo != null) {
                            SearchResultThumbnail(photo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonPickerRow(
    people: List<PersonWithStats>,
    selectedPersonIds: Set<Long>,
    onPersonToggled: (Long) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(people, key = { it.id }) { person ->
            FilterChip(
                selected = person.id in selectedPersonIds,
                onClick = { onPersonToggled(person.id) },
                label = { Text(person.name ?: "Unnamed") },
            )
        }
    }
}

@Composable
private fun YearPickerRow(selectedYear: Int?, onYearSelected: (Int?) -> Unit) {
    val currentYear = Year.now().value
    val years = (currentYear downTo currentYear - 4).toList()

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(listOf<Int?>(null) + years) { year ->
            FilterChip(
                selected = selectedYear == year,
                onClick = { onYearSelected(year) },
                label = { Text(year?.toString() ?: "All time") },
            )
        }
    }
}

@Composable
private fun LocationFilterRow(enabled: Boolean, onToggled: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Near saved location", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onToggled)
    }
}

@Composable
private fun NlSearchBar(
    downloadState: LlmModelDownloadState,
    onQuerySubmitted: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    if (downloadState !is LlmModelDownloadState.Ready) {
        Text(
            "Natural-language search needs the search assistant model — download it in Settings.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Ask in plain English…") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(onClick = { onQuerySubmitted(query) }) {
            Icon(Icons.Default.Send, contentDescription = "Search")
        }
    }
}

@Composable
private fun NlSearchResultSection(state: NlSearchUiState) {
    when (state) {
        is NlSearchUiState.Idle -> Unit
        is NlSearchUiState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is NlSearchUiState.Message -> Text(state.text, modifier = Modifier.padding(8.dp))
        is NlSearchUiState.Results -> Column {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                gridItems(state.photos, key = { it.mediaStoreId }) { photo ->
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = photo.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        is NlSearchUiState.Plan -> {
            var showReview by remember(state.plan.id) { mutableStateOf(true) }
            if (showReview) {
                OrganizationReviewScreen(plan = state.plan, onBack = { showReview = false })
            } else {
                Text(state.message, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun SearchResultThumbnail(photo: Photo) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.filename,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
