package dev.armen.fileconverter.ui.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.armen.fileconverter.work.ConversionScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.concurrent.thread

class BatchViewModel(
    private val tag: String,
    private val scheduler: ConversionScheduler,
    private val queueDir: File,
) : ViewModel() {

    val items: StateFlow<List<BatchItem>> =
        scheduler.observe(tag).map(BatchMapper::map).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel() {
        scheduler.cancel(tag)
        // Not viewModelScope: the screen closes right after cancelling, which would cancel the cleanup.
        thread(name = "batch-cleanup") { File(queueDir, ConversionScheduler.batchId(tag)).deleteRecursively() }
    }
}
