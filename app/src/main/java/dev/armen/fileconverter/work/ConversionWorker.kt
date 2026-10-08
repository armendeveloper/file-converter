package dev.armen.fileconverter.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.armen.fileconverter.FileConverterApp
import dev.armen.fileconverter.data.OutputStore
import dev.armen.fileconverter.data.history.HistoryEntry
import dev.armen.fileconverter.di.AppContainer
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one ConversionRequest. Always returns Result.success: failures travel in KEY_ERROR_* so the
 * UI shows them and other queued items are unaffected. Inputs are deleted after success or failure,
 * but kept on cancellation/stop so WorkManager can retry a stopped worker.
 */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FileConverterApp).container
        val requestFile = inputData.getString(KEY_REQUEST_FILE)?.let(::File)
        val request = requestFile?.let { file -> runCatching { ConversionRequest.fromJson(file.readText()) }.getOrNull() }
            ?: return Result.success(errorData(ConversionError.EngineFailed("The conversion request is missing.")))
        val title = if (request.inputs.size > 1) {
            "${request.inputs.size} files → ${request.target.label}"
        } else {
            "${request.inputs.first().displayName} → ${request.target.label}"
        }
        val workDir = File(applicationContext.cacheDir, "conversions/$id")
        report(container, title, STAGE_WAITING, 0f)

        return ConversionLock.mutex.withLock {
            coroutineScope {
                val progress = MutableStateFlow(0f)
                val reporter = launch {
                    progress.collect { p ->
                        report(container, title, STAGE_CONVERTING, p)
                        delay(PROGRESS_INTERVAL_MS) // StateFlow conflates updates in the meantime
                    }
                }
                try {
                    val result = container.runner().run(request, request.inputs.map { File(it.path) }, workDir) { progress.value = it }
                    reporter.cancel()
                    report(container, title, STAGE_SAVING, 1f)
                    val subfolder = if (result.outputs.size > 1) FileNames.baseName(request.inputs.first().displayName) else null
                    val saved = result.outputs.map { container.outputStore.save(it, request.target.mimeType, subfolder) }
                    saved.forEach { recordHistory(container, request, it) }
                    deleteInputs(request, requestFile)
                    Result.success(successData(saved, basic = result.route.fidelity == Fidelity.BASIC))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    deleteInputs(request, requestFile)
                    Result.success(errorData(ErrorMapper.map(e)))
                } finally {
                    reporter.cancel()
                    workDir.deleteRecursively()
                }
            }
        }
    }

    private suspend fun report(container: AppContainer, title: String, stage: String, progress: Float) {
        setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STAGE to stage))
        try {
            setForeground(container.notifications.foregroundInfo(id, title, stage, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Foreground start can be refused (e.g. Android 12+ while in background); keep working without it.
        }
    }

    private suspend fun recordHistory(container: AppContainer, request: ConversionRequest, saved: OutputStore.Saved) {
        try {
            container.history.insert(
                HistoryEntry(
                    createdAt = System.currentTimeMillis(),
                    sourceName = request.inputs.first().displayName,
                    sourceFormat = request.source,
                    targetFormat = request.target,
                    outputUri = saved.uri.toString(),
                    outputName = saved.displayName,
                    outputSizeBytes = saved.sizeBytes,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // History is a convenience; the file is already saved.
        }
    }

    private fun deleteInputs(request: ConversionRequest, requestFile: File?) {
        request.inputs.forEach { File(it.path).delete() }
        requestFile?.delete()
        requestFile?.parentFile?.let { dir -> if (dir.list().isNullOrEmpty()) dir.delete() }
    }

    companion object {
        const val KEY_REQUEST_FILE = "request_file"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_OUTPUT_URIS = "output_uris"
        const val KEY_OUTPUT_NAMES = "output_names"
        const val KEY_OUTPUT_SIZES = "output_sizes"
        const val KEY_OUTPUT_TOTAL = "output_total"
        const val KEY_BASIC = "basic"
        const val KEY_ERROR_KIND = "error_kind"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ERROR_DETAILS = "error_details"
        const val STAGE_WAITING = "waiting"
        const val STAGE_CONVERTING = "converting"
        const val STAGE_SAVING = "saving"
        private const val PROGRESS_INTERVAL_MS = 300L

        /** WorkManager Data is limited to 10 KB, so only the first outputs are listed; the rest are counted. */
        private const val MAX_LISTED_OUTPUTS = 40

        fun successData(saved: List<OutputStore.Saved>, basic: Boolean): Data {
            val listed = saved.take(MAX_LISTED_OUTPUTS)
            return Data.Builder()
                .putStringArray(KEY_OUTPUT_URIS, listed.map { it.uri.toString() }.toTypedArray())
                .putStringArray(KEY_OUTPUT_NAMES, listed.map { it.displayName }.toTypedArray())
                .putLongArray(KEY_OUTPUT_SIZES, listed.map { it.sizeBytes }.toLongArray())
                .putInt(KEY_OUTPUT_TOTAL, saved.size)
                .putBoolean(KEY_BASIC, basic)
                .build()
        }

        fun errorData(error: ConversionError): Data = Data.Builder()
            .putString(KEY_ERROR_KIND, error.kind.name)
            .putString(KEY_ERROR_MESSAGE, error.message)
            .apply { error.details?.let { putString(KEY_ERROR_DETAILS, it.take(2_000)) } }
            .build()
    }
}
