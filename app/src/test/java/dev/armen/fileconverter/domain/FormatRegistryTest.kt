package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FileFormat.AVI
import dev.armen.fileconverter.domain.FileFormat.BMP
import dev.armen.fileconverter.domain.FileFormat.CSV
import dev.armen.fileconverter.domain.FileFormat.DOCX
import dev.armen.fileconverter.domain.FileFormat.GIF
import dev.armen.fileconverter.domain.FileFormat.HEIC
import dev.armen.fileconverter.domain.FileFormat.ICO
import dev.armen.fileconverter.domain.FileFormat.JPG
import dev.armen.fileconverter.domain.FileFormat.MKV
import dev.armen.fileconverter.domain.FileFormat.MOV
import dev.armen.fileconverter.domain.FileFormat.MP3
import dev.armen.fileconverter.domain.FileFormat.MP4
import dev.armen.fileconverter.domain.FileFormat.ODT
import dev.armen.fileconverter.domain.FileFormat.PDF
import dev.armen.fileconverter.domain.FileFormat.PNG
import dev.armen.fileconverter.domain.FileFormat.PPTX
import dev.armen.fileconverter.domain.FileFormat.THREE_GP
import dev.armen.fileconverter.domain.FileFormat.TIFF
import dev.armen.fileconverter.domain.FileFormat.TXT
import dev.armen.fileconverter.domain.FileFormat.WAV
import dev.armen.fileconverter.domain.FileFormat.WEBM
import dev.armen.fileconverter.domain.FileFormat.WEBP
import dev.armen.fileconverter.domain.FileFormat.XLSX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatRegistryTest {
    private val sdk = 34

    @Test fun `detects by extension case-insensitively`() =
        assertEquals(JPG, FormatRegistry.detect("Photo.JPEG", null))

    @Test fun `extension wins over a conflicting mime type`() =
        assertEquals(MP3, FormatRegistry.detect("song.mp3", "video/mp4"))

    @Test fun `falls back to mime when the name has no extension`() =
        assertEquals(PDF, FormatRegistry.detect("IMG-20261008-WA0001", "application/pdf"))

    @Test fun `falls back to mime when the extension is unknown`() =
        assertEquals(MP3, FormatRegistry.detect("track.xyz", "audio/mpeg"))

    @Test fun `ignores mime parameters`() =
        assertEquals(TXT, FormatRegistry.detect(null, "text/plain; charset=utf-8"))

    @Test fun `generic or missing mime without extension is unknown`() {
        assertNull(FormatRegistry.detect("blob", "application/octet-stream"))
        assertNull(FormatRegistry.detect(null, null))
    }

    @Test fun `3gp has a readable label`() = assertEquals("3GP", THREE_GP.label)

    @Test fun `extensions are unique across formats`() {
        val all = FileFormat.entries.flatMap { it.extensions }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun `every format has at least one target`() {
        FileFormat.entries.forEach { assertTrue("$it has no targets", FormatRegistry.targetsFor(it, sdk).isNotEmpty()) }
    }

    @Test fun `a format is never its own target`() {
        FileFormat.entries.forEach { assertFalse(it in FormatRegistry.targetsFor(it, sdk)) }
    }

    @Test fun `audio converts to every other audio format and nothing else`() {
        val expected = FileFormat.entries.filter { it.family == FormatFamily.AUDIO && it != WAV }
        assertEquals(expected, FormatRegistry.targetsFor(WAV, sdk))
    }

    @Test fun `video converts to video, audio and gif`() {
        val targets = FormatRegistry.targetsFor(MP4, sdk)
        assertTrue(targets.containsAll(listOf(MKV, WEBM, MOV, AVI, THREE_GP, MP3, WAV, GIF)))
        assertFalse(PDF in targets)
        assertFalse(PNG in targets)
    }

    @Test fun `png targets`() =
        assertEquals(listOf(JPG, WEBP, BMP, GIF, TIFF, ICO, PDF), FormatRegistry.targetsFor(PNG, sdk))

    @Test fun `heic decodes natively from API 28`() {
        assertEquals(Route(Engine.IMAGE_NATIVE), FormatRegistry.chooseRoute(HEIC, JPG, 28, cloudAvailable = false))
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(HEIC, JPG, 27, cloudAvailable = false))
        assertTrue(PDF in FormatRegistry.targetsFor(HEIC, 28))
        assertFalse(PDF in FormatRegistry.targetsFor(HEIC, 27))
    }

    @Test fun `tiff uses ffmpeg and cannot become pdf`() {
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(TIFF, PNG, sdk, cloudAvailable = false))
        assertFalse(PDF in FormatRegistry.targetsFor(TIFF, sdk))
    }

    @Test fun `bmp output always uses ffmpeg`() =
        assertEquals(Route(Engine.FFMPEG), FormatRegistry.chooseRoute(JPG, BMP, sdk, cloudAvailable = false))

    @Test fun `txt to pdf stays on device even when cloud is available`() =
        assertEquals(Route(Engine.LOCAL_PDF), FormatRegistry.chooseRoute(TXT, PDF, sdk, cloudAvailable = true))

    @Test fun `docx to pdf prefers cloud and falls back to basic offline`() {
        assertEquals(Route(Engine.CLOUD), FormatRegistry.chooseRoute(DOCX, PDF, sdk, cloudAvailable = true))
        assertEquals(Route(Engine.OFFICE_LITE, Fidelity.BASIC), FormatRegistry.chooseRoute(DOCX, PDF, sdk, cloudAvailable = false))
    }

    @Test fun `cloud-only pair has no route without cloud`() {
        assertEquals(listOf(Route(Engine.CLOUD)), FormatRegistry.routesFor(DOCX, ODT, sdk))
        assertNull(FormatRegistry.chooseRoute(DOCX, ODT, sdk, cloudAvailable = false))
    }

    @Test fun `office lite pairs are always offline`() {
        listOf(XLSX to CSV, CSV to XLSX, DOCX to TXT, TXT to DOCX).forEach { (s, t) ->
            assertEquals("$s->$t", Route(Engine.OFFICE_LITE), FormatRegistry.chooseRoute(s, t, sdk, cloudAvailable = true))
        }
    }

    @Test fun `pdf to images is on device`() =
        assertEquals(Route(Engine.LOCAL_PDF), FormatRegistry.chooseRoute(PDF, PNG, sdk, cloudAvailable = true))

    @Test fun `no route across document groups`() =
        assertTrue(FormatRegistry.routesFor(PPTX, DOCX, sdk).isEmpty())

    @Test fun `audio cannot become a document`() =
        assertTrue(FormatRegistry.routesFor(MP3, PDF, sdk).isEmpty())
}
