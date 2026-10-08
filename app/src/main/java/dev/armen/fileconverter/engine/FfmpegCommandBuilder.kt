package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.VideoQuality
import java.util.Locale

/** What FFprobe found out about the input. All fields are null when probing failed. */
data class MediaProbe(
    val durationMs: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** [expectedDurationMs] is the output duration used to turn FFmpeg's time statistics into progress. */
data class FfmpegCommand(val args: List<String>, val expectedDurationMs: Long?)

/** Pure function from a conversion description to FFmpeg arguments. */
object FfmpegCommandBuilder {
    const val DEFAULT_GIF_MS = 5_000L

    private data class TrimRange(val startMs: Long, val endMs: Long?)

    fun build(
        input: String,
        output: String,
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions,
        probe: MediaProbe,
    ): FfmpegCommand {
        if (source.family == FormatFamily.VIDEO && target.family == FormatFamily.AUDIO &&
            probe.videoCodec != null && probe.audioCodec == null
        ) {
            throw ConversionError.EngineFailed("This video has no audio track.")
        }
        val range = trimRange(source, target, options, probe)
        val args = mutableListOf("-y", "-hide_banner", "-nostdin")
        if (range != null && range.startMs > 0) args += listOf("-ss", seconds(range.startMs))
        args += listOf("-i", input)
        if (range?.endMs != null) args += listOf("-t", seconds(range.endMs - range.startMs))
        args += when {
            target.family == FormatFamily.AUDIO &&
                (source.family == FormatFamily.AUDIO || source.family == FormatFamily.VIDEO) -> audioArgs(target, options)
            source.family == FormatFamily.VIDEO && target == FileFormat.GIF -> gifArgs(options, probe)
            source.family == FormatFamily.VIDEO && target.family == FormatFamily.VIDEO ->
                videoArgs(target, options, probe, trimmed = range != null)
            source.family == FormatFamily.IMAGE && target.family == FormatFamily.IMAGE -> imageArgs(target, options, probe)
            else -> throw ConversionError.UnsupportedPair(source, target)
        }
        args += output
        val expected = when {
            source.family == FormatFamily.IMAGE -> null
            range?.endMs != null -> range.endMs - range.startMs
            range != null -> probe.durationMs?.let { it - range.startMs }
            else -> probe.durationMs
        }
        return FfmpegCommand(args, expected)
    }

    private fun trimRange(source: FileFormat, target: FileFormat, o: ConversionOptions, probe: MediaProbe): TrimRange? {
        if (source.family == FormatFamily.VIDEO && target == FileFormat.GIF) {
            val start = (o.trimStartMs ?: 0L).coerceAtLeast(0L)
            var end = o.trimEndMs ?: (start + DEFAULT_GIF_MS)
            end = minOf(end, start + ConversionOptions.MAX_GIF_RANGE_MS)
            probe.durationMs?.takeIf { it > 0 }?.let { end = minOf(end, it) }
            if (end <= start) throw ConversionError.EngineFailed("The GIF range is empty. Pick an end time after the start time.")
            return TrimRange(start, end)
        }
        if (source.family != FormatFamily.AUDIO && source.family != FormatFamily.VIDEO) return null
        if (o.trimStartMs == null && o.trimEndMs == null) return null
        val start = (o.trimStartMs ?: 0L).coerceAtLeast(0L)
        val end = o.trimEndMs
        if (end != null && end <= start) throw ConversionError.EngineFailed("The trim end must be after the trim start.")
        return TrimRange(start, end)
    }

    private fun audioArgs(target: FileFormat, o: ConversionOptions): List<String> = buildList {
        add("-vn")
        val bitrate = "${o.audioBitrateKbps}k"
        addAll(
            when (target) {
                FileFormat.MP3 -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
                FileFormat.M4A, FileFormat.AAC -> listOf("-c:a", "aac", "-b:a", bitrate)
                FileFormat.WAV -> listOf("-c:a", "pcm_s16le")
                FileFormat.FLAC -> listOf("-c:a", "flac")
                FileFormat.OGG -> listOf("-c:a", "libvorbis", "-b:a", bitrate)
                FileFormat.OPUS -> listOf("-c:a", "libopus", "-b:a", bitrate)
                FileFormat.AIFF -> listOf("-c:a", "pcm_s16be")
                FileFormat.WMA -> listOf("-c:a", "wmav2", "-b:a", bitrate)
                else -> error("$target is not an audio format")
            },
        )
        // libopus only accepts 8/12/16/24/48 kHz.
        val rate = if (target == FileFormat.OPUS) 48_000 else o.sampleRateHz
        rate?.let { addAll(listOf("-ar", it.toString())) }
        o.channels?.let { addAll(listOf("-ac", it.toString())) }
        if (target == FileFormat.M4A) addAll(listOf("-movflags", "+faststart"))
    }

    private fun gifArgs(o: ConversionOptions, probe: MediaProbe): List<String> {
        // fps= needs a timeline: a zero-length clip (e.g. a one-frame screen recording) would produce no frames.
        val fps = if ((probe.durationMs ?: 0L) > 0L) "fps=${o.gifFps}," else ""
        return listOf(
            "-vf", "${fps}scale=${o.gifWidth}:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
            "-loop", "0",
            "-an",
        )
    }

    private val COPY_VIDEO_CODECS = mapOf(
        FileFormat.MP4 to setOf("h264", "hevc", "mpeg4", "av1"),
        FileFormat.MOV to setOf("h264", "hevc", "mpeg4", "prores"),
        FileFormat.MKV to setOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg4"),
        FileFormat.WEBM to setOf("vp8", "vp9", "av1"),
        FileFormat.AVI to setOf("mpeg4", "h264", "mjpeg"),
    )

    private val COPY_AUDIO_CODECS = mapOf(
        FileFormat.MP4 to setOf("aac", "mp3", "alac", "ac3"),
        FileFormat.MOV to setOf("aac", "mp3", "alac", "pcm_s16le"),
        FileFormat.MKV to setOf("aac", "mp3", "opus", "vorbis", "flac", "ac3", "pcm_s16le"),
        FileFormat.WEBM to setOf("opus", "vorbis"),
        FileFormat.AVI to setOf("mp3", "ac3", "pcm_s16le"),
    )

    private fun canStreamCopy(target: FileFormat, probe: MediaProbe): Boolean {
        val video = probe.videoCodec ?: return false
        if (video !in COPY_VIDEO_CODECS[target].orEmpty()) return false
        val audio = probe.audioCodec ?: return true
        return audio in COPY_AUDIO_CODECS[target].orEmpty()
    }

    private fun videoArgs(target: FileFormat, o: ConversionOptions, probe: MediaProbe, trimmed: Boolean): List<String> {
        val maxHeight = o.videoHeight
        val sourceHeight = probe.height
        val downscale = maxHeight != null && sourceHeight != null && sourceHeight > maxHeight
        val maps = listOf("-map", "0:v:0", "-map", "0:a:0?")
        val reencode = target == FileFormat.THREE_GP || trimmed || downscale || o.videoQuality != null ||
            !canStreamCopy(target, probe)
        if (!reencode) return maps + listOf("-c", "copy") + muxerFlags(target)

        val q = o.videoQuality ?: VideoQuality.MEDIUM
        val filter = when {
            target == FileFormat.THREE_GP -> "scale=w=352:h=288:force_original_aspect_ratio=decrease:force_divisible_by=2"
            downscale -> "scale=-2:$maxHeight"
            else -> "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        }
        val codecs = when (target) {
            FileFormat.MP4, FileFormat.MOV, FileFormat.MKV -> listOf(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", q.x264Crf.toString(), "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k",
            )
            FileFormat.WEBM -> listOf(
                "-c:v", "libvpx-vp9", "-b:v", "0", "-crf", q.vp9Crf.toString(), "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
                "-c:a", "libopus", "-b:a", "128k",
            )
            FileFormat.AVI -> listOf("-c:v", "libxvid", "-q:v", q.xvidQ.toString(), "-c:a", "libmp3lame", "-b:a", "192k")
            FileFormat.THREE_GP -> listOf(
                "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0", "-crf", q.x264Crf.toString(), "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
            )
            else -> error("$target is not a video format")
        }
        return maps + listOf("-vf", filter) + codecs + muxerFlags(target)
    }

    private fun muxerFlags(target: FileFormat): List<String> =
        if (target == FileFormat.MP4 || target == FileFormat.MOV) listOf("-movflags", "+faststart") else emptyList()

    private fun imageArgs(target: FileFormat, o: ConversionOptions, probe: MediaProbe): List<String> = buildList {
        val longest = maxOf(probe.width ?: 0, probe.height ?: 0)
        val limit = if (target == FileFormat.ICO) minOf(256, o.maxDimension ?: 256) else o.maxDimension
        if (limit != null && (longest > limit || (target == FileFormat.ICO && longest == 0))) {
            addAll(listOf("-vf", "scale=w=$limit:h=$limit:force_original_aspect_ratio=decrease"))
        }
        addAll(listOf("-frames:v", "1"))
        when (target) {
            FileFormat.JPG -> addAll(listOf("-q:v", jpegQScale(o.imageQuality).toString()))
            FileFormat.WEBP -> addAll(listOf("-c:v", "libwebp", "-quality", o.imageQuality.toString()))
            else -> Unit
        }
    }

    /** Maps quality 1..100 to FFmpeg's MJPEG qscale 31..2 (lower is better). */
    private fun jpegQScale(quality: Int): Int = (2 + (100 - quality.coerceIn(1, 100)) * 29 / 99).coerceIn(2, 31)

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
