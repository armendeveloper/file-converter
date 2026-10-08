package dev.armen.fileconverter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.VideoQuality
import dev.armen.fileconverter.ui.common.ChoiceChips
import dev.armen.fileconverter.ui.common.FileActions
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val keyTest by viewModel.keyTest.collectAsStateWithLifecycle()
    var keyInput by rememberSaveable { mutableStateOf("") }
    var quality by remember(settings.defaultImageQuality) { mutableFloatStateOf(settings.defaultImageQuality.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CloudConvert", style = MaterialTheme.typography.titleMedium)
            Text(
                "Office and e-book documents are converted by CloudConvert. Your key is stored encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (settings.hasApiKey) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.hasApiKey) Text("A key is saved.", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = viewModel::testKey, enabled = settings.hasApiKey && keyTest != KeyTestState.Testing) { Text("Test") }
                TextButton(onClick = viewModel::removeKey, enabled = settings.hasApiKey) { Text("Remove") }
            }
            when (val test = keyTest) {
                KeyTestState.Idle -> Unit
                KeyTestState.Testing -> Text("Testing…")
                is KeyTestState.Ok -> Text(
                    "The key works${test.username?.let { " for $it" }.orEmpty()}. Credits left: ${test.credits ?: "unknown"}.",
                    color = MaterialTheme.colorScheme.primary,
                )
                is KeyTestState.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { FileActions.openWebPage(context, "https://cloudconvert.com/dashboard/api/v2/keys") }) {
                Text("Get a free API key")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sandbox mode")
                    Text("Use CloudConvert's test environment (needs a sandbox key).", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.sandbox, onCheckedChange = viewModel::setSandbox)
            }

            HorizontalDivider()
            Text("Defaults", style = MaterialTheme.typography.titleMedium)
            Text("Image quality: ${quality.roundToInt()}")
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 1f..100f,
                onValueChangeFinished = { viewModel.setImageQuality(quality.roundToInt()) },
            )
            ChoiceChips("Audio bitrate", ConversionOptions.AUDIO_BITRATES.map { it to "$it kbps" }, settings.defaultAudioBitrateKbps) {
                viewModel.setAudioBitrate(it)
            }
            ChoiceChips(
                "Video quality",
                listOf<Pair<VideoQuality?, String>>(
                    null to "Keep",
                    VideoQuality.HIGH to "High",
                    VideoQuality.MEDIUM to "Medium",
                    VideoQuality.SMALL to "Small file",
                ),
                settings.defaultVideoQuality,
            ) { viewModel.setVideoQuality(it) }

            HorizontalDivider()
            OutlinedButton(onClick = viewModel::clearHistory) { Text("Clear history") }
        }
    }
}
