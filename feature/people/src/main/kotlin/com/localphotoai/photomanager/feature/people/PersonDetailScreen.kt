package com.localphotoai.photomanager.feature.people

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.person.PersonMember
import com.localphotoai.photomanager.domain.person.PersonWithStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: Long,
    allPeople: List<PersonWithStats>,
    onBack: () -> Unit,
    viewModel: PeopleViewModel,
    modifier: Modifier = Modifier,
) {
    val person = allPeople.find { it.id == personId }
    val members by viewModel.observeMembers(personId).collectAsState(initial = emptyList())
    var nameInput by remember(personId) { mutableStateOf(person?.name ?: "") }
    var showMergeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(person?.name) {
        if (nameInput.isEmpty()) nameInput = person?.name ?: ""
    }

    // The current person was merged away (became a source and no longer exists) — leave the screen.
    LaunchedEffect(allPeople) {
        if (allPeople.isNotEmpty() && allPeople.none { it.id == personId }) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(person?.name ?: "Unnamed person") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name this person") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.onNamePerson(personId, nameInput) },
                        enabled = nameInput != (person?.name ?: ""),
                    ) {
                        Text("Save")
                    }
                }
                Button(
                    onClick = { showMergeDialog = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Merge with another person")
                }
                if (person != null) {
                    Text(
                        text = "${person.photoCount} photo(s) · ${person.faceCount} face(s) · " +
                            "avg. match confidence ${(person.averageConfidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
            ) {
                items(members, key = { it.faceId }) { member ->
                    MemberThumbnail(
                        member = member,
                        onSplit = { viewModel.onSplitFace(member.faceId) },
                        onMarkIncorrect = { viewModel.onMarkFaceIncorrect(member.faceId) },
                    )
                }
            }
        }
    }

    if (showMergeDialog) {
        MergePersonDialog(
            candidates = allPeople.filter { it.id != personId },
            onDismiss = { showMergeDialog = false },
            onMergeInto = { targetId ->
                viewModel.onMergePersons(sourcePersonId = personId, targetPersonId = targetId)
                showMergeDialog = false
            },
        )
    }
}

@Composable
private fun MemberThumbnail(
    member: PersonMember,
    onSplit: () -> Unit,
    onMarkIncorrect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier.padding(1.dp).aspectRatio(1f)) {
        AsyncImage(
            model = member.photoUri,
            contentDescription = member.photoFilename,
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true }),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Split into new person") },
                onClick = { showMenu = false; onSplit() },
            )
            DropdownMenuItem(
                text = { Text("Mark face as incorrect") },
                onClick = { showMenu = false; onMarkIncorrect() },
            )
        }
    }
}

@Composable
private fun MergePersonDialog(
    candidates: List<PersonWithStats>,
    onDismiss: () -> Unit,
    onMergeInto: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge with another person") },
        text = {
            if (candidates.isEmpty()) {
                Text("No other people to merge with yet.")
            } else {
                Column {
                    candidates.forEach { candidate ->
                        ListItem(
                            headlineContent = { Text(candidate.name ?: "Unnamed") },
                            supportingContent = { Text("${candidate.faceCount} face(s)") },
                            modifier = Modifier.combinedClickable(
                                onClick = { onMergeInto(candidate.id) },
                                onLongClick = {},
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
