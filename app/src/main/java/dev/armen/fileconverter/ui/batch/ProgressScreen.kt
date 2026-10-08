package dev.armen.fileconverter.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.work.ConversionWorker
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(viewModel: BatchViewModel, onFinished: () -> Unit, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finished = BatchMapper.allFinished(items)
    LaunchedEffect(finished) { if (finished) onFinished() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Converting") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(progress = { BatchMapper.overallProgress(items) }, modifier = Modifier.fillMaxWidth())
            Text(
                "You can leave this screen. Conversions continue in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item -> ProgressRow(item) }
            }
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    onBack()
                },
                enabled = !finished,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun ProgressRow(item: BatchItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (item.state) {
            ItemState.QUEUED -> Text("Waiting…", style = MaterialTheme.typography.bodySmall)
            ItemState.RUNNING -> when (item.stage) {
                ConversionWorker.STAGE_WAITING -> {
                    Text("Waiting for the previous file…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ConversionWorker.STAGE_SAVING -> {
                    Text("Saving…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> {
                    Text("${(item.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            ItemState.DONE -> Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            ItemState.FAILED -> Text(item.error?.message ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            ItemState.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.bodySmall)
        }
    }
}
