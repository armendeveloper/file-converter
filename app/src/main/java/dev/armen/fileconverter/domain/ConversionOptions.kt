package dev.armen.fileconverter.domain

import kotlinx.serialization.Serializable

@Serializable
enum class VideoQuality(val x264Crf: Int, val vp9Crf: Int, val xvidQ: Int) {
    HIGH(20, 28, 3),
    MEDIUM(23, 33, 5),
    SMALL(28, 38, 8),
}

/**
 * Every option a conversion can use. Engines ignore options that do not apply to them.
 * `videoQuality == null` means "keep": stream-copy when the codecs allow it.
 */
@Serializable
data class ConversionOptions(
    val imageQuality: Int = 90,
    val maxDimension: Int? = null,
    val audioBitrateKbps: Int = 192,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val videoHeight: Int? = null,
    val videoQuality: VideoQuality? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val gifFps: Int = 10,
    val gifWidth: Int = 480,
) {
    companion object {
        val AUDIO_BITRATES = listOf(64, 128, 192, 256, 320)
        val SAMPLE_RATES = listOf(44_100, 48_000)
        val MAX_DIMENSIONS = listOf(4096, 2048, 1080, 720)
        val VIDEO_HEIGHTS = listOf(1080, 720, 480)
        val GIF_FPS = listOf(10, 15)
        val GIF_WIDTHS = listOf(320, 480, 720)
        const val MAX_GIF_RANGE_MS = 30_000L
    }
}
