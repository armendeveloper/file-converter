package dev.armen.fileconverter.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.ui.common.FileActions
import dev.armen.fileconverter.ui.common.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(viewModel: BatchViewModel, onOpenSettings: () -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()
    val allOutputs = items.flatMap { it.outputs }

    Scaffold(
        topBar = { TopAppBar(title = { Text(resultTitle(items)) }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (allOutputs.size > 1) {
                        OutlinedButton(
                            onClick = { FileActions.share(context, allOutputs.map { it.uri.toUri() }, "*/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Share all (${allOutputs.size})") }
                    }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Convert another") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item -> ResultCard(item, onOpenSettings, onRetry) }
        }
    }
}

private fun resultTitle(items: List<BatchItem>): String {
    val failed = items.count { it.state == ItemState.FAILED }
    return when {
        items.isEmpty() -> "Results"
        failed == 0 -> "Done"
        failed == items.size -> "Conversion failed"
        else -> "Done with $failed error(s)"
    }
}

@Composable
private fun ResultCard(item: BatchItem, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var showDetails by rememberSaveable(item.id.toString()) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleSmall)
            when (item.state) {
                ItemState.DONE -> {
                    if (item.basic) {
                        Text(
                            "Offline basic conversion: text only, layout not kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    item.outputs.forEach { output ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(output.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Formatting.bytes(output.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { FileActions.open(context, output.uri.toUri(), FileActions.mimeTypeFor(output.name)) }) { Text("Open") }
                            TextButton(onClick = { FileActions.share(context, listOf(output.uri.toUri()), FileActions.mimeTypeFor(output.name)) }) { Text("Share") }
                        }
                    }
                    if (item.totalOutputs > item.outputs.size) {
                        Text("+ ${item.totalOutputs - item.outputs.size} more in Download/FileConverter", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ItemState.FAILED -> {
                    val error = item.error
                    Text(error?.message ?: "Conversion failed.", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (error?.kind) {
                            ErrorKind.MISSING_API_KEY, ErrorKind.INVALID_API_KEY, ErrorKind.QUOTA_EXCEEDED ->
                                OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                            ErrorKind.NO_NETWORK -> OutlinedButton(onClick = onRetry) { Text("Retry") }
                            else -> Unit
                        }
                        if (error?.details != null) {
                            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
                        }
                    }
                    if (showDetails && error?.details != null) {
                        Text(error.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                ItemState.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
                ItemState.QUEUED, ItemState.RUNNING -> Text("Still converting…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
