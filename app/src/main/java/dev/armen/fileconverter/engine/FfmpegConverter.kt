package dev.armen.fileconverter.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio, video, GIF and FFmpeg-only image conversions. Cancelling the coroutine cancels FFmpeg. */
class FfmpegConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val output = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
            val probe = probe(input)
            val command = FfmpegCommandBuilder.build(
                input.absolutePath, output.absolutePath, task.source, task.target, task.options, probe,
            )
            execute(command, onProgress)
            if (!output.exists() || output.length() == 0L) throw ConversionError.EngineFailed("FFmpeg produced no output.")
            onProgress(1f)
            listOf(output)
        }

    private fun probe(file: File): MediaProbe {
        val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation ?: return MediaProbe()
        val streams = info.streams.orEmpty()
        val video = streams.firstOrNull { it.type == "video" }
        val audio = streams.firstOrNull { it.type == "audio" }
        return MediaProbe(
            durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() },
            videoCodec = video?.codec,
            audioCodec = audio?.codec,
            width = video?.width?.toInt(),
            height = video?.height?.toInt(),
        )
    }

    private suspend fun execute(command: FfmpegCommand, onProgress: (Float) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val expected = command.expectedDurationMs
            val session = FFmpegKit.executeWithArgumentsAsync(
                command.args.toTypedArray(),
                FFmpegSessionCompleteCallback { finished ->
                    val code = finished.returnCode
                    when {
                        ReturnCode.isSuccess(code) -> cont.resume(Unit)
                        ReturnCode.isCancel(code) -> cont.cancel()
                        else -> cont.resumeWithException(ConversionError.EngineFailed(lastLines(finished.allLogsAsString)))
                    }
                },
                null,
                StatisticsCallback { stats ->
                    if (expected != null && expected > 0) onProgress((stats.time / expected).toFloat().coerceIn(0f, 0.99f))
                },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    private fun lastLines(log: String?): String =
        log.orEmpty().lines().filter { it.isNotBlank() }.takeLast(20).joinToString("\n")
}
