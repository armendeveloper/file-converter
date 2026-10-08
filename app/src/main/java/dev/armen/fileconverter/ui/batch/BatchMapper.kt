package dev.armen.fileconverter.ui.batch

import androidx.work.WorkInfo
import dev.armen.fileconverter.domain.ErrorKind
import dev.armen.fileconverter.work.ConversionScheduler
import dev.armen.fileconverter.work.ConversionWorker
import java.util.UUID

data class SavedOutput(val uri: String, val name: String, val sizeBytes: Long)

data class ErrorInfo(val kind: ErrorKind, val message: String, val details: String?)

enum class ItemState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class BatchItem(
    val id: UUID,
    val index: Int,
    val label: String,
    val state: ItemState,
    val progress: Float,
    val stage: String?,
    val outputs: List<SavedOutput>,
    val totalOutputs: Int,
    val error: ErrorInfo?,
    val basic: Boolean,
) {
    val finished: Boolean get() = state == ItemState.DONE || state == ItemState.FAILED || state == ItemState.CANCELLED
}

/** Turns WorkManager's view of a batch into what the screens show. */
object BatchMapper {

    fun map(infos: List<WorkInfo>): List<BatchItem> = infos.map(::toItem).sortedBy { it.index }

    fun toItem(info: WorkInfo): BatchItem {
        val out = info.outputData
        val errorKind = out.getString(ConversionWorker.KEY_ERROR_KIND)?.let { name -> ErrorKind.entries.firstOrNull { it.name == name } }
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ItemState.QUEUED
            WorkInfo.State.RUNNING -> ItemState.RUNNING
            WorkInfo.State.SUCCEEDED -> if (errorKind != null) ItemState.FAILED else ItemState.DONE
            WorkInfo.State.FAILED -> ItemState.FAILED
            WorkInfo.State.CANCELLED -> ItemState.CANCELLED
        }
        val uris = out.getStringArray(ConversionWorker.KEY_OUTPUT_URIS).orEmpty()
        val names = out.getStringArray(ConversionWorker.KEY_OUTPUT_NAMES).orEmpty()
        val sizes = out.getLongArray(ConversionWorker.KEY_OUTPUT_SIZES) ?: LongArray(0)
        val outputs = if (state == ItemState.DONE) {
            uris.indices.map { SavedOutput(uris[it], names.getOrElse(it) { "file" }, sizes.getOrElse(it) { 0L }) }
        } else {
            emptyList()
        }
        val error = when {
            errorKind != null -> ErrorInfo(
                errorKind,
                out.getString(ConversionWorker.KEY_ERROR_MESSAGE) ?: "Conversion failed.",
                out.getString(ConversionWorker.KEY_ERROR_DETAILS),
            )
            info.state == WorkInfo.State.FAILED -> ErrorInfo(ErrorKind.ENGINE_FAILED, "Conversion failed.", null)
            else -> null
        }
        return BatchItem(
            id = info.id,
            index = tagValue(info, ConversionScheduler.INDEX_PREFIX)?.toIntOrNull() ?: 0,
            label = tagValue(info, ConversionScheduler.LABEL_PREFIX) ?: "File",
            state = state,
            progress = if (state == ItemState.DONE) 1f else info.progress.getFloat(ConversionWorker.KEY_PROGRESS, 0f),
            stage = info.progress.getString(ConversionWorker.KEY_STAGE),
            outputs = outputs,
            totalOutputs = out.getInt(ConversionWorker.KEY_OUTPUT_TOTAL, outputs.size),
            error = error,
            basic = out.getBoolean(ConversionWorker.KEY_BASIC, false),
        )
    }

    fun overallProgress(items: List<BatchItem>): Float =
        if (items.isEmpty()) 0f else items.sumOf { (if (it.finished) 1f else it.progress).toDouble() }.toFloat() / items.size

    fun allFinished(items: List<BatchItem>): Boolean = items.isNotEmpty() && items.all { it.finished }

    private fun tagValue(info: WorkInfo, prefix: String): String? =
        info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
}
