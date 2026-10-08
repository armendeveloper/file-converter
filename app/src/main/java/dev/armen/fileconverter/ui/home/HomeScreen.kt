package dev.armen.fileconverter.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.ui.common.FileActions
import dev.armen.fileconverter.ui.common.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onFilesPicked: (List<Uri>) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val history by viewModel.recent.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Converter") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick files", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Images, audio, video and documents. You can also share files to this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Text("Recent conversions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            }
            if (history.isEmpty()) {
                item { Text("Nothing converted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(history, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    onOpen = { FileActions.open(context, entry.outputUri.toUri(), entry.targetFormat.mimeType) },
                    onShare = { FileActions.share(context, listOf(entry.outputUri.toUri()), entry.targetFormat.mimeType) },
                    onDelete = { viewModel.remove(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val details = listOf(
        "${entry.sourceFormat.label} → ${entry.targetFormat.label}",
        Formatting.bytes(entry.outputSizeBytes),
        DateUtils.getRelativeTimeSpanString(entry.createdAt).toString(),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(entry.outputName) },
        supportingContent = { Text(details) },
        trailingContent = {
            Row {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share ${entry.outputName}") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove from history") }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
