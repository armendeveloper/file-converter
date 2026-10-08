package dev.armen.fileconverter.ui.convert

import dev.armen.fileconverter.domain.ConversionInput
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.ConversionRequest
import dev.armen.fileconverter.domain.Engine
import dev.armen.fileconverter.domain.Fidelity
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.FormatRegistry
import kotlin.math.roundToLong

data class SelectedFile(val displayName: String, val sizeBytes: Long?, val format: FileFormat?)

/** An input already copied into app storage. */
data class PreparedInput(val path: String, val displayName: String, val format: FileFormat)

sealed interface SelectionCheck {
    data class Ok(val targets: List<FileFormat>) : SelectionCheck
    data class Problem(val message: String) : SelectionCheck
}

enum class RouteBadge { OFFLINE, OFFLINE_BASIC, CLOUD, CLOUD_NEEDS_KEY }

enum class OptionSection { IMAGE_QUALITY, MAX_DIMENSION, AUDIO_BITRATE, SAMPLE_RATE, CHANNELS, VIDEO_RESOLUTION, VIDEO_QUALITY, TRIM, GIF }

/** Pure decisions behind the Convert screen. */
object ConvertPlanner {
    private val LOSSY_AUDIO = setOf(FileFormat.MP3, FileFormat.M4A, FileFormat.AAC, FileFormat.OGG, FileFormat.OPUS, FileFormat.WMA)

    fun check(files: List<SelectedFile>, sdkInt: Int): SelectionCheck {
        if (files.isEmpty()) return SelectionCheck.Problem("No files selected.")
        files.firstOrNull { it.format == null }?.let {
            return SelectionCheck.Problem("${it.displayName} isn't a file type this app supports.")
        }
        val formats = files.mapNotNull { it.format }
        if (formats.map { it.family }.toSet().size > 1) {
            return SelectionCheck.Problem("Pick files of one kind at a time, for example only images or only videos.")
        }
        // A target is offered when every file can reach it (or already is it) and at least one file needs converting.
        val targets = FileFormat.entries.filter { target ->
            formats.any { it != target } &&
                formats.all { it == target || FormatRegistry.routesFor(it, target, sdkInt).isNotEmpty() }
        }
        return if (targets.isEmpty()) SelectionCheck.Problem("These files have no output format in common.") else SelectionCheck.Ok(targets)
    }

    fun badge(sources: List<FileFormat>, target: FileFormat, sdkInt: Int, hasApiKey: Boolean): RouteBadge {
        val source = sources.firstOrNull { it != target } ?: return RouteBadge.OFFLINE
        val route = FormatRegistry.chooseRoute(source, target, sdkInt, cloudAvailable = hasApiKey) ?: return RouteBadge.CLOUD_NEEDS_KEY
        return when {
            route.engine == Engine.CLOUD -> RouteBadge.CLOUD
            route.fidelity == Fidelity.BASIC -> RouteBadge.OFFLINE_BASIC
            else -> RouteBadge.OFFLINE
        }
    }

    fun sections(sources: List<FileFormat>, target: FileFormat): Set<OptionSection> {
        val family = sources.firstOrNull()?.family ?: return emptySet()
        return when {
            family == FormatFamily.VIDEO && target == FileFormat.GIF -> setOf(OptionSection.GIF, OptionSection.TRIM)
            target.family == FormatFamily.AUDIO -> buildSet {
                if (target in LOSSY_AUDIO) add(OptionSection.AUDIO_BITRATE)
                if (target != FileFormat.OPUS) add(OptionSection.SAMPLE_RATE)
                add(OptionSection.CHANNELS)
                add(OptionSection.TRIM)
            }
            target.family == FormatFamily.VIDEO -> setOf(OptionSection.VIDEO_RESOLUTION, OptionSection.VIDEO_QUALITY, OptionSection.TRIM)
            family == FormatFamily.IMAGE && target == FileFormat.PDF -> emptySet()
            family == FormatFamily.IMAGE -> buildSet {
                if (target == FileFormat.JPG || target == FileFormat.WEBP) add(OptionSection.IMAGE_QUALITY)
                add(OptionSection.MAX_DIMENSION)
            }
            sources.first() == FileFormat.PDF && target == FileFormat.JPG -> setOf(OptionSection.IMAGE_QUALITY)
            else -> emptySet()
        }
    }

    /** Several images -> PDF become one merged request; everything else is one request per file. */
    fun buildRequests(inputs: List<PreparedInput>, target: FileFormat, options: ConversionOptions): List<ConversionRequest> {
        val toConvert = inputs.filter { it.format != target }
        if (target == FileFormat.PDF && toConvert.size > 1 && toConvert.all { it.format.family == FormatFamily.IMAGE }) {
            return listOf(
                ConversionRequest(toConvert.map { ConversionInput(it.path, it.displayName) }, toConvert.first().format, target, options),
            )
        }
        return toConvert.map { ConversionRequest(listOf(ConversionInput(it.path, it.displayName)), it.format, target, options) }
    }

    fun parseSeconds(text: String): Long? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value < 0 || value.isNaN() || value.isInfinite()) return null
        return (value * 1000).roundToLong()
    }

    /** Null when valid. Blank start means 0; blank end means "to the end" (or the default GIF length). */
    fun trimError(startText: String, endText: String, forGif: Boolean): String? {
        val start = if (startText.isBlank()) 0L else parseSeconds(startText) ?: return "Start time must be a number of seconds."
        val end = if (endText.isBlank()) null else parseSeconds(endText) ?: return "End time must be a number of seconds."
        if (end != null && end <= start) return "End must be after start."
        if (forGif && end != null && end - start > ConversionOptions.MAX_GIF_RANGE_MS) return "GIFs can be at most 30 seconds long."
        return null
    }
}
