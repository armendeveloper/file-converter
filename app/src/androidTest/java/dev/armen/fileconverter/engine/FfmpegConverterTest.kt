package dev.armen.fileconverter.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FfmpegConverterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    @Before fun setUp() { dir = File(context.cacheDir, "ffmpeg-test-${System.nanoTime()}").apply { mkdirs() } }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun convert(input: File, source: FileFormat, target: FileFormat, options: ConversionOptions = ConversionOptions()): File =
        runBlocking {
            val out = File(dir, "out").apply { mkdirs() }
            FfmpegConverter().convert(ConversionTask(listOf(input), source, target, options, out, "result")) {}.single()
        }

    private fun ascii(file: File, from: Int, count: Int) =
        String(TestMedia.header(file, from + count), from, count, Charsets.US_ASCII)

    @Test fun wavToMp3() {
        val out = convert(TestMedia.wav(dir), FileFormat.WAV, FileFormat.MP3)
        assertEquals("result.mp3", out.name)
        val head = TestMedia.header(out, 3)
        assertTrue(String(head, Charsets.US_ASCII) == "ID3" || (head[0].toInt() and 0xFF) == 0xFF)
    }

    @Test fun mp3ToOgg() {
        val mp3 = convert(TestMedia.wav(dir), FileFormat.WAV, FileFormat.MP3)
        assertEquals("OggS", ascii(convert(mp3, FileFormat.MP3, FileFormat.OGG), 0, 4))
    }

    @Test fun mp4ToWebm() =
        assertArrayEquals(ebml, TestMedia.header(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.WEBM), 4))

    @Test fun mp4ToMkvStreamCopies() =
        assertArrayEquals(ebml, TestMedia.header(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.MKV), 4))

    @Test fun mp4ToGif() =
        assertEquals("GIF89a", ascii(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.GIF, ConversionOptions(trimEndMs = 1_000)), 0, 6))

    @Test fun mp4ToM4a() =
        assertEquals("ftyp", ascii(convert(TestMedia.mp4(dir), FileFormat.MP4, FileFormat.M4A), 4, 4))

    @Test fun pngToBmp() =
        assertEquals("BM", ascii(convert(TestMedia.png(dir, "a.png"), FileFormat.PNG, FileFormat.BMP), 0, 2))

    @Test fun pngToIcoIsAtMost256px() {
        val ico = convert(TestMedia.png(dir, "big.png", width = 1024, height = 512), FileFormat.PNG, FileFormat.ICO)
        val head = TestMedia.header(ico, 8)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), head.copyOfRange(0, 4))
        assertEquals(0, head[6].toInt() and 0xFF) // 0 means 256 px wide
        assertEquals(128, head[7].toInt() and 0xFF)
    }

    @Test fun silentVideoToMp3FailsClearly() {
        val silent = TestMedia.mp4(dir, withAudio = false)
        val error = assertThrows(ConversionError.EngineFailed::class.java) { convert(silent, FileFormat.MP4, FileFormat.MP3) }
        assertEquals("This video has no audio track.", error.details)
    }
}
