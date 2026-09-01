package com.localphotoai.photomanager.feature.photos

import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import kotlinx.coroutines.launch

private enum class DuplicatesTab { EXACT, BURSTS, SIMILAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DuplicatesTab.EXACT) }
    var pendingDeletePhotoId by remember { mutableStateOf<Long?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val photoId = pendingDeletePhotoId
        pendingDeletePhotoId = null
        if (result.resultCode == android.app.Activity.RESULT_OK && photoId != null) {
            viewModel.onPhotoDeleted(photoId)
        }
    }

    fun deletePhoto(photoId: Long) {
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId.toString())
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingDeletePhotoId = photoId
                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
            else -> scope.launch {
                try {
                    context.contentResolver.delete(uri, null, null)
                    viewModel.onPhotoDeleted(photoId)
                } catch (e: RecoverableSecurityException) {
                    pendingDeletePhotoId = photoId
                    val intentSender: IntentSender = e.userAction.actionIntent.intentSender
                    deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            }
        }
    }

    val exactDuplicates by viewModel.exactDuplicates.collectAsState()
    val bursts by viewModel.bursts.collectAsState()
    val nearDuplicates by viewModel.nearDuplicates.collectAsState()
    val visuallySimilar by viewModel.visuallySimilar.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Duplicates & Similar Photos") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                DuplicatesTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, DuplicatesTab.entries.size),
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                    ) {
                        Text(
                            when (tab) {
                                DuplicatesTab.EXACT -> "Exact"
                                DuplicatesTab.BURSTS -> "Bursts & Near-dupes"
                                DuplicatesTab.SIMILAR -> "Similar"
                            },
                        )
                    }
                }
            }

            when (selectedTab) {
                DuplicatesTab.EXACT -> ExactDuplicateGroupList(exactDuplicates, onDeletePhotos = { it.forEach(::deletePhoto) })
                DuplicatesTab.BURSTS -> SimilarGroupList(bursts + nearDuplicates, onDeletePhotos = { it.forEach(::deletePhoto) })
                DuplicatesTab.SIMILAR -> SimilarGroupList(visuallySimilar, onDeletePhotos = { it.forEach(::deletePhoto) })
            }
        }
    }
}

@Composable
private fun ExactDuplicateGroupList(groups: List<DuplicateGroupSummary>, onDeletePhotos: (List<Long>) -> Unit) {
    if (groups.isEmpty()) {
        EmptyGroupsMessage("No exact duplicates found.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(groups, key = { it.groupId }) { group ->
            GroupCard(photoIds = group.photoIds, similarityLabel = null, onDeletePhotos = onDeletePhotos)
        }
    }
}

@Composable
private fun SimilarGroupList(groups: List<SimilarGroupSummary>, onDeletePhotos: (List<Long>) -> Unit) {
    if (groups.isEmpty()) {
        EmptyGroupsMessage("No groups found.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(groups, key = { it.groupId }) { group ->
            GroupCard(
                photoIds = group.photoIds,
                similarityLabel = "${(group.avgSimilarity * 100).toInt()}% similar",
                onDeletePhotos = onDeletePhotos,
            )
        }
    }
}

@Composable
private fun GroupCard(photoIds: List<Long>, similarityLabel: String?, onDeletePhotos: (List<Long>) -> Unit) {
    var selected by remember(photoIds) { mutableStateOf<Set<Long>>(emptySet()) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (similarityLabel != null) {
                Text(similarityLabel, style = MaterialTheme.typography.labelMedium)
            }
            LazyRow {
                items(photoIds) { photoId ->
                    val isSelected = photoId in selected
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .size(80.dp)
                            .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary)
                            .clickable {
                                selected = if (isSelected) selected - photoId else selected + photoId
                            },
                    ) {
                        AsyncImage(
                            model = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                photoId.toString(),
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onDeletePhotos(selected.toList())
                        selected = emptySet()
                    },
                ) {
                    Text("Delete selected")
                }
            }
        }
    }
}

@Composable
private fun EmptyGroupsMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
