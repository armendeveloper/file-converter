package dev.armen.fileconverter.ui.convert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.ui.common.ChoiceChips
import dev.armen.fileconverter.ui.common.Formatting
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    viewModel: ConvertViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onBatchStarted: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.batchStarted.collect { onBatchStarted(it) } }
    val badge = viewModel.badge(state)
    val sections = viewModel.sections(state)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convert") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                    if (badge == RouteBadge.CLOUD_NEEDS_KEY) {
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Add CloudConvert API key") }
                    } else {
                        Button(
                            onClick = viewModel::convert,
                            enabled = state.target != null && !state.preparing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.preparing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Preparing…")
                            } else {
                                Text(state.target?.let { "Convert to ${it.label}" } ?: "Choose a format")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilesCard(state.files)
            when (val check = state.check) {
                null -> Unit
                is SelectionCheck.Problem -> Text(check.message, color = MaterialTheme.colorScheme.error)
                is SelectionCheck.Ok -> {
                    TargetPicker(check.targets, state.target, viewModel::selectTarget)
                    val target = state.target
                    if (target != null) {
                        badge?.let { BadgeText(it) }
                        Notes(state, target)
                        OptionsPanel(sections, state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesCard(files: List<SelectedFile>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (files.size == 1) "1 file" else "${files.size} files", style = MaterialTheme.typography.titleMedium)
            files.take(3).forEach { file ->
                Text(
                    listOf(file.displayName, file.format?.label ?: "unknown type", Formatting.bytes(file.sizeBytes))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (files.size > 3) Text("+ ${files.size - 3} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetPicker(targets: List<FileFormat>, selected: FileFormat?, onSelect: (FileFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Convert to", style = MaterialTheme.typography.titleMedium)
        targets.groupBy { it.family }.forEach { (family, formats) ->
            Text(familyLabel(family), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { format ->
                    FilterChip(selected = format == selected, onClick = { onSelect(format) }, label = { Text(format.label) })
                }
            }
        }
    }
}

private fun familyLabel(family: FormatFamily): String = when (family) {
    FormatFamily.IMAGE -> "Image"
    FormatFamily.AUDIO -> "Audio"
    FormatFamily.VIDEO -> "Video"
    FormatFamily.DOCUMENT -> "Document"
}

@Composable
private fun BadgeText(badge: RouteBadge) {
    val (text, color) = when (badge) {
        RouteBadge.OFFLINE -> "On this phone · works offline" to MaterialTheme.colorScheme.onSurfaceVariant
        RouteBadge.OFFLINE_BASIC ->
            "Offline · basic (text only). Add a CloudConvert key in Settings to keep the layout." to MaterialTheme.colorScheme.tertiary
        RouteBadge.CLOUD -> "☁ Uploads to CloudConvert" to MaterialTheme.colorScheme.primary
        RouteBadge.CLOUD_NEEDS_KEY -> "☁ Needs a CloudConvert API key" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Notes(state: ConvertUiState, target: FileFormat) {
    val skipped = state.sources.count { it == target }
    val converting = state.sources.size - skipped
    if (skipped > 0) {
        Text("$skipped file(s) already in ${target.label} will be skipped.", style = MaterialTheme.typography.bodySmall)
    }
    if (target == FileFormat.PDF && converting > 1 && state.sources.all { it.family == FormatFamily.IMAGE }) {
        Text("All $converting images will be combined into one PDF.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionsPanel(sections: Set<OptionSection>, state: ConvertUiState, viewModel: ConvertViewModel) {
    if (sections.isEmpty()) return
    val o = state.options
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Options", style = MaterialTheme.typography.titleMedium)
        if (OptionSection.IMAGE_QUALITY in sections) {
            Text("Quality: ${o.imageQuality}")
            Slider(
                value = o.imageQuality.toFloat(),
                onValueChange = { v -> viewModel.updateOptions { it.copy(imageQuality = v.roundToInt()) } },
                valueRange = 1f..100f,
            )
        }
        if (OptionSection.MAX_DIMENSION in sections) {
            ChoiceChips("Size", listOf<Pair<Int?, String>>(null to "Original") + ConversionOptions.MAX_DIMENSIONS.map { it to "$it px" }, o.maxDimension) { v ->
                viewModel.updateOptions { it.copy(maxDimension = v) }
            }
        }
        if (OptionSection.AUDIO_BITRATE in sections) {
            ChoiceChips("Bitrate", ConversionOptions.AUDIO_BITRATES.map { it to "$it kbps" }, o.audioBitrateKbps) { v ->
                viewModel.updateOptions { it.copy(audioBitrateKbps = v) }
            }
        }
        if (OptionSection.SAMPLE_RATE in sections) {
            ChoiceChips("Sample rate", listOf<Pair<Int?, String>>(null to "Keep", 44_100 to "44.1 kHz", 48_000 to "48 kHz"), o.sampleRateHz) { v ->
                viewModel.updateOptions { it.copy(sampleRateHz = v) }
            }
        }
        if (OptionSection.CHANNELS in sections) {
            ChoiceChips("Channels", listOf<Pair<Int?, String>>(null to "Keep", 1 to "Mono", 2 to "Stereo"), o.channels) { v ->
                viewModel.updateOptions { it.copy(channels = v) }
            }
        }
        if (OptionSection.VIDEO_RESOLUTION in sections) {
            ChoiceChips("Resolution", listOf<Pair<Int?, String>>(null to "Keep") + ConversionOptions.VIDEO_HEIGHTS.map { it to "${it}p" }, o.videoHeight) { v ->
                viewModel.updateOptions { it.copy(videoHeight = v) }
            }
        }
        if (OptionSection.VIDEO_QUALITY in sections) {
            ChoiceChips(
                "Quality",
                listOf<Pair<VideoQuality?, String>>(null to "Keep", VideoQuality.HIGH to "High", VideoQuality.MEDIUM to "Medium", VideoQuality.SMALL to "Small file"),
                o.videoQuality,
            ) { v -> viewModel.updateOptions { it.copy(videoQuality = v) } }
        }
        if (OptionSection.GIF in sections) {
            ChoiceChips("Frames per second", ConversionOptions.GIF_FPS.map { it to "$it fps" }, o.gifFps) { v ->
                viewModel.updateOptions { it.copy(gifFps = v) }
            }
            ChoiceChips("Width", ConversionOptions.GIF_WIDTHS.map { it to "$it px" }, o.gifWidth) { v ->
                viewModel.updateOptions { it.copy(gifWidth = v) }
            }
        }
        if (OptionSection.TRIM in sections) {
            val gif = OptionSection.GIF in sections
            Text(if (gif) "Clip (max 30 s; defaults to the first 5 s)" else "Trim (optional)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.trimStart,
                    onValueChange = viewModel::setTrimStart,
                    label = { Text("Start (s)") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.trimEnd,
                    onValueChange = viewModel::setTrimEnd,
                    label = { Text("End (s)") },
                    placeholder = { Text(if (gif) "5" else "end") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
