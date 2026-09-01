package com.localphotoai.photomanager.feature.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.person.PersonWithStats

@Composable
fun PeopleScreen(
    modifier: Modifier = Modifier,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val people by viewModel.people.collectAsState()
    var selectedPersonId by remember { mutableStateOf<Long?>(null) }

    val currentSelection = selectedPersonId
    if (currentSelection != null) {
        PersonDetailScreen(
            personId = currentSelection,
            allPeople = people,
            onBack = { selectedPersonId = null },
            viewModel = viewModel,
            modifier = modifier,
        )
    } else {
        PeopleScreenContent(
            people = people,
            onPersonClick = { selectedPersonId = it.id },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleScreenContent(
    people: List<PersonWithStats>,
    onPersonClick: (PersonWithStats) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("People") }) },
    ) { innerPadding ->
        if (people.isEmpty()) {
            Column(
                modifier = Modifier.padding(innerPadding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No people found yet. People are discovered automatically as photos " +
                        "are indexed and faces are detected — this can take a little while for a " +
                        "large library, and requires the face-embedding model to be downloaded " +
                        "in Settings.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(people, key = { it.id }) { person ->
                    PersonCard(person = person, onClick = { onPersonClick(person) })
                }
            }
        }
    }
}

@Composable
private fun PersonCard(person: PersonWithStats, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (person.representativePhotoUri != null) {
                    AsyncImage(
                        model = person.representativePhotoUri,
                        contentDescription = person.name ?: "Unnamed person",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = person.name ?: "Unnamed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${person.photoCount} photo(s) · ${person.faceCount} face(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Avg. match confidence: ${(person.averageConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
