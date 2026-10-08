package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {
    private val prefix = listOf("-y", "-hide_banner", "-nostdin")
    private val audioProbe = MediaProbe(durationMs = 2_000, audioCodec = "pcm_s16le")
    private val h264Probe = MediaProbe(durationMs = 60_000, videoCodec = "h264", audioCodec = "aac", width = 1920, height = 1080)
    private val gifFilter = "fps=10,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse"

    private fun build(
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions = ConversionOptions(),
        probe: MediaProbe = MediaProbe(),
    ) = FfmpegCommandBuilder.build("in.${source.extension}", "out.${target.extension}", source, target, options, probe)

    private fun assertContainsSequence(args: List<String>, sequence: List<String>) =
        assertTrue("$sequence not in $args", args.windowed(sequence.size).any { it == sequence })

    @Test fun `wav to mp3 at 320 kbps`() {
        val command = build(FileFormat.WAV, FileFormat.MP3, ConversionOptions(audioBitrateKbps = 320), audioProbe)
        assertEquals(prefix + listOf("-i", "in.wav", "-vn", "-c:a", "libmp3lame", "-b:a", "320k", "out.mp3"), command.args)
        assertEquals(2_000L, command.expectedDurationMs)
    }

    @Test fun `opus always uses 48 kHz`() {
        val args = build(FileFormat.MP3, FileFormat.OPUS, ConversionOptions(sampleRateHz = 44_100), audioProbe).args
        assertContainsSequence(args, listOf("-c:a", "libopus"))
        assertContainsSequence(args, listOf("-ar", "48000"))
        assertFalse("44100" in args)
    }

    @Test fun `m4a with sample rate, mono and faststart`() {
        val args = build(FileFormat.FLAC, FileFormat.M4A, ConversionOptions(sampleRateHz = 44_100, channels = 1), audioProbe).args
        assertEquals(
            prefix + listOf("-i", "in.flac", "-vn", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "1", "-movflags", "+faststart", "out.m4a"),
            args,
        )
    }

    @Test fun `lossless and pcm targets have no bitrate`() {
        assertContainsSequence(build(FileFormat.MP3, FileFormat.WAV, probe = audioProbe).args, listOf("-c:a", "pcm_s16le"))
        assertContainsSequence(build(FileFormat.MP3, FileFormat.FLAC, probe = audioProbe).args, listOf("-c:a", "flac"))
        assertContainsSequence(build(FileFormat.MP3, FileFormat.AIFF, probe = audioProbe).args, listOf("-c:a", "pcm_s16be"))
        assertFalse("-b:a" in build(FileFormat.MP3, FileFormat.WAV, probe = audioProbe).args)
    }

    @Test fun `video to m4a extracts the audio track`() {
        val args = build(FileFormat.MP4, FileFormat.M4A, probe = h264Probe).args
        assertContainsSequence(args, listOf("-vn", "-c:a", "aac"))
    }

    @Test fun `video without audio cannot become audio`() {
        val error = assertThrows(ConversionError.EngineFailed::class.java) {
            build(FileFormat.MP4, FileFormat.MP3, probe = h264Probe.copy(audioCodec = null))
        }
        assertEquals("This video has no audio track.", error.details)
    }

    @Test fun `compatible codecs are stream-copied`() {
        val command = build(FileFormat.MOV, FileFormat.MP4, probe = h264Probe)
        assertEquals(
            prefix + listOf("-i", "in.mov", "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", "out.mp4"),
            command.args,
        )
        assertEquals(60_000L, command.expectedDurationMs)
    }

    @Test fun `silent video is stream-copied with optional audio map`() {
        val args = build(FileFormat.MP4, FileFormat.MKV, probe = h264Probe.copy(audioCodec = null)).args
        assertContainsSequence(args, listOf("-map", "0:a:0?", "-c", "copy"))
    }

    @Test fun `incompatible codecs are re-encoded for webm`() {
        val args = build(FileFormat.MP4, FileFormat.WEBM, probe = h264Probe).args
        assertContainsSequence(args, listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "33"))
        assertContainsSequence(args, listOf("-c:a", "libopus"))
        assertFalse("copy" in args)
    }

    @Test fun `downscales when the source is taller than the chosen height`() {
        val args = build(FileFormat.MOV, FileFormat.MP4, ConversionOptions(videoHeight = 720), h264Probe).args
        assertContainsSequence(args, listOf("-vf", "scale=-2:720"))
        assertContainsSequence(args, listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23"))
    }

    @Test fun `never upscales and keeps stream copy`() {
        val args = build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(videoHeight = 720), h264Probe.copy(height = 480)).args
        assertContainsSequence(args, listOf("-c", "copy"))
        assertFalse("-movflags" in args)
    }

    @Test fun `chosen quality forces re-encoding`() {
        val args = build(FileFormat.MOV, FileFormat.MP4, ConversionOptions(videoQuality = VideoQuality.HIGH), h264Probe).args
        assertContainsSequence(args, listOf("-crf", "20"))
        assertContainsSequence(args, listOf("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"))
    }

    @Test fun `avi uses xvid and mp3`() {
        val args = build(FileFormat.MP4, FileFormat.AVI, probe = h264Probe).args
        assertContainsSequence(args, listOf("-c:v", "libxvid", "-q:v", "5", "-c:a", "libmp3lame"))
    }

    @Test fun `3gp is always re-encoded to fit 352x288`() {
        val args = build(FileFormat.MP4, FileFormat.THREE_GP, probe = h264Probe).args
        assertContainsSequence(args, listOf("-vf", "scale=w=352:h=288:force_original_aspect_ratio=decrease:force_divisible_by=2"))
        assertContainsSequence(args, listOf("-c:v", "libx264", "-profile:v", "baseline"))
    }

    @Test fun `trimmed video seeks before input and limits duration`() {
        val command = build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(trimStartMs = 1_500, trimEndMs = 4_000), h264Probe)
        assertEquals(prefix + listOf("-ss", "1.500", "-i", "in.mp4", "-t", "2.500"), command.args.take(9))
        assertContainsSequence(command.args, listOf("-c:v", "libx264"))
        assertEquals(2_500L, command.expectedDurationMs)
    }

    @Test fun `audio trimmed from the start only`() {
        val command = build(FileFormat.WAV, FileFormat.MP3, ConversionOptions(trimStartMs = 1_000), audioProbe)
        assertEquals(prefix + listOf("-ss", "1.000", "-i", "in.wav"), command.args.take(7))
        assertFalse("-t" in command.args)
        assertEquals(1_000L, command.expectedDurationMs)
    }

    @Test fun `trim end before start is rejected`() {
        assertThrows(ConversionError.EngineFailed::class.java) {
            build(FileFormat.MP4, FileFormat.MKV, ConversionOptions(trimStartMs = 5_000, trimEndMs = 2_000), h264Probe)
        }
    }

    @Test fun `gif defaults to the first five seconds`() {
        val command = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe)
        assertEquals(prefix + listOf("-i", "in.mp4", "-t", "5.000", "-vf", gifFilter, "-loop", "0", "-an", "out.gif"), command.args)
        assertEquals(5_000L, command.expectedDurationMs)
    }

    @Test fun `gif range is clamped to 30 seconds`() {
        val args = build(FileFormat.MP4, FileFormat.GIF, ConversionOptions(trimStartMs = 10_000, trimEndMs = 60_000), h264Probe).args
        assertContainsSequence(args, listOf("-ss", "10.000", "-i", "in.mp4", "-t", "30.000"))
    }

    @Test fun `gif range is clamped to the video duration`() {
        val args = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe.copy(durationMs = 3_000)).args
        assertContainsSequence(args, listOf("-t", "3.000"))
    }

    @Test fun `gif from a clip without a usable duration skips the fps filter`() {
        // A screen recording of a static screen can be a single frame with zero duration; fps= emits nothing for it.
        val args = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe.copy(durationMs = null)).args
        assertContainsSequence(args, listOf("-vf", "scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse"))
        assertFalse(args.any { it.startsWith("fps=") })
    }

    @Test fun `gif from a clip reported as zero seconds still converts`() {
        val args = build(FileFormat.MP4, FileFormat.GIF, probe = h264Probe.copy(durationMs = 0)).args
        assertContainsSequence(args, listOf("-t", "5.000"))
        assertFalse(args.any { it.startsWith("fps=") })
    }

    @Test fun `tiff to jpg at best quality`() {
        val command = build(FileFormat.TIFF, FileFormat.JPG, ConversionOptions(imageQuality = 100), MediaProbe(width = 800, height = 600))
        assertEquals(prefix + listOf("-i", "in.tiff", "-frames:v", "1", "-q:v", "2", "out.jpg"), command.args)
        assertNull(command.expectedDurationMs)
    }

    @Test fun `ico is scaled to fit 256 pixels`() {
        val args = build(FileFormat.PNG, FileFormat.ICO, probe = MediaProbe(width = 1024, height = 512)).args
        assertEquals(
            prefix + listOf("-i", "in.png", "-vf", "scale=w=256:h=256:force_original_aspect_ratio=decrease", "-frames:v", "1", "out.ico"),
            args,
        )
    }

    @Test fun `webp quality and resize`() {
        val args = build(FileFormat.TIFF, FileFormat.WEBP, ConversionOptions(imageQuality = 80, maxDimension = 2048), MediaProbe(width = 4000, height = 3000)).args
        assertContainsSequence(args, listOf("-vf", "scale=w=2048:h=2048:force_original_aspect_ratio=decrease"))
        assertContainsSequence(args, listOf("-c:v", "libwebp", "-quality", "80"))
    }

    @Test fun `small images are not upscaled`() {
        val args = build(FileFormat.TIFF, FileFormat.PNG, ConversionOptions(maxDimension = 4096), MediaProbe(width = 800, height = 600)).args
        assertFalse("-vf" in args)
    }

    @Test fun `audio cannot become video`() {
        assertThrows(ConversionError.UnsupportedPair::class.java) { build(FileFormat.MP3, FileFormat.MP4, probe = audioProbe) }
    }
}
