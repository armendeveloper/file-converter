package dev.armen.fileconverter.engine.cloud

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Upload -> convert -> export job. Progress: upload 0–40 %, conversion 40–80 %, download 80–100 %.
 * The remote job is deleted afterwards on every outcome, including cancellation.
 */
class CloudConvertConverter(
    private val client: CloudConvertClient,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 30 * 60_000,
    private val maxInputBytes: Long = MAX_INPUT_BYTES,
) : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
        val input = task.inputs.single()
        if (input.length() > maxInputBytes) throw ConversionError.EngineFailed("CloudConvert accepts files up to 1 GB.")
        val job = client.createJob(task.source.extension, task.target.extension)
        try {
            val form = job.tasks.firstOrNull { it.operation == "import/upload" }?.result?.form
                ?: throw ConversionError.EngineFailed("CloudConvert did not return an upload form.")
            // ASCII name: the output is renamed locally anyway.
            client.upload(form, input, "input.${task.source.extension}") { onProgress(it * 0.4f) }
            onProgress(0.4f)
            val finished = awaitFinished(job.id, onProgress)
            val files = finished.tasks.firstOrNull { it.operation == "export/url" }?.result?.files.orEmpty()
            if (files.isEmpty()) throw ConversionError.EngineFailed("CloudConvert returned no files.")
            val outputs = files.mapIndexed { index, file ->
                val name = if (files.size == 1) FileNames.withExtension(task.baseName, task.target) else FileNames.sanitize(file.filename)
                val dest = File(task.outputDir, name)
                client.download(file.url, dest) { p -> onProgress(0.8f + 0.2f * (index + p) / files.size) }
                dest
            }
            onProgress(1f)
            return outputs
        } finally {
            withContext(NonCancellable) { runCatching { client.deleteJob(job.id) } }
        }
    }

    private suspend fun awaitFinished(id: String, onProgress: (Float) -> Unit): JobDto {
        val maxPolls = (timeoutMs / pollIntervalMs).coerceAtLeast(1)
        for (attempt in 0 until maxPolls) {
            val job = client.getJob(id)
            when (job.status) {
                "finished" -> return job
                "error" -> throw taskError(job)
            }
            onProgress(0.4f + 0.4f * (1f - 1f / (attempt + 2))) // creeps toward 80 %
            delay(pollIntervalMs)
        }
        throw ConversionError.EngineFailed("CloudConvert took too long.")
    }

    private fun taskError(job: JobDto): ConversionError {
        val failed = job.tasks.firstOrNull { it.status == "error" }
        return if (failed?.code?.contains("CREDIT", ignoreCase = true) == true) {
            ConversionError.QuotaExceeded()
        } else {
            ConversionError.EngineFailed("CloudConvert: ${failed?.message ?: "conversion failed"}")
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 1L shl 30
    }
}
