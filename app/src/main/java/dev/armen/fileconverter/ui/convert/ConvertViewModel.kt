package dev.armen.fileconverter.ui.convert

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.data.InputResolver
import dev.armen.fileconverter.data.SelectionHolder
import dev.armen.fileconverter.data.SettingsRepository
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class ConvertUiState(
    val files: List<SelectedFile> = emptyList(),
    val check: SelectionCheck? = null,
    val target: FileFormat? = null,
    val options: ConversionOptions = ConversionOptions(),
    val trimStart: String = "",
    val trimEnd: String = "",
    val hasApiKey: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null,
) {
    val sources: List<FileFormat> get() = files.mapNotNull { it.format }
}

class ConvertViewModel(
    private val selection: SelectionHolder,
    private val settings: SettingsRepository,
    private val inputResolver: InputResolver,
    private val scheduler: ConversionScheduler,
    private val queueDir: File,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    private val _state = MutableStateFlow(ConvertUiState())
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    private val _batchStarted = Channel<String>(Channel.BUFFERED)
    /** Emits the batch tag once inputs are copied and the work is enqueued. */
    val batchStarted: Flow<String> = _batchStarted.receiveAsFlow()

    init {
        viewModelScope.launch {
            val defaults = settings.defaultOptions()
            _state.update { it.copy(options = defaults) }
        }
        viewModelScope.launch {
            selection.inputs.collect { inputs ->
                val files = inputs.map { SelectedFile(it.displayName, it.sizeBytes, it.format) }
                _state.update { it.copy(files = files, check = ConvertPlanner.check(files, sdkInt), target = null, error = null) }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(hasApiKey = s.hasApiKey) } }
        }
    }

    fun badge(state: ConvertUiState): RouteBadge? =
        state.target?.let { ConvertPlanner.badge(state.sources, it, sdkInt, state.hasApiKey) }

    fun sections(state: ConvertUiState): Set<OptionSection> =
        state.target?.let { ConvertPlanner.sections(state.sources, it) }.orEmpty()

    fun selectTarget(target: FileFormat) = _state.update { it.copy(target = target, error = null) }

    fun updateOptions(transform: (ConversionOptions) -> ConversionOptions) = _state.update { it.copy(options = transform(it.options)) }

    fun setTrimStart(text: String) = _state.update { it.copy(trimStart = text, error = null) }

    fun setTrimEnd(text: String) = _state.update { it.copy(trimEnd = text, error = null) }

    fun convert() {
        val current = _state.value
        val target = current.target ?: return
        if (current.preparing) return
        val usesTrim = OptionSection.TRIM in sections(current)
        if (usesTrim) {
            val forGif = target == FileFormat.GIF && current.sources.firstOrNull()?.family == FormatFamily.VIDEO
            ConvertPlanner.trimError(current.trimStart, current.trimEnd, forGif)?.let { message ->
                _state.update { it.copy(error = message) }
                return
            }
        }
        val options = if (usesTrim) {
            current.options.copy(trimStartMs = ConvertPlanner.parseSeconds(current.trimStart), trimEndMs = ConvertPlanner.parseSeconds(current.trimEnd))
        } else {
            current.options.copy(trimStartMs = null, trimEndMs = null)
        }
        val inputs = selection.inputs.value.filter { it.format != null && it.format != target }
        viewModelScope.launch {
            _state.update { it.copy(preparing = true, error = null) }
            val batchId = UUID.randomUUID().toString()
            val batchDir = File(queueDir, batchId)
            try {
                val tag = withContext(Dispatchers.IO) {
                    batchDir.mkdirs()
                    // Copy now: picker and share URI grants may expire before a queued worker runs.
                    val prepared = inputs.mapIndexed { index, input ->
                        val dest = File(batchDir, "$index-${FileNames.sanitize(input.displayName)}")
                        inputResolver.copyToFile(input.uri, dest)
                        PreparedInput(dest.absolutePath, input.displayName, requireNotNull(input.format))
                    }
                    scheduler.enqueue(batchId, batchDir, ConvertPlanner.buildRequests(prepared, target, options))
                }
                _batchStarted.send(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { batchDir.deleteRecursively() }
                _state.update { it.copy(error = "Couldn't read the selected files: ${e.message ?: e::class.java.simpleName}") }
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }
}
