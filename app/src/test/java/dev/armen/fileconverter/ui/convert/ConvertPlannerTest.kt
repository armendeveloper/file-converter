package dev.armen.fileconverter.ui.convert

import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertPlannerTest {
    private val sdk = 34

    private fun file(name: String, format: FileFormat?) = SelectedFile(name, 100, format)

    private fun targets(vararg files: SelectedFile) = (ConvertPlanner.check(files.toList(), sdk) as SelectionCheck.Ok).targets

    @Test fun `empty selection is a problem`() =
        assertTrue(ConvertPlanner.check(emptyList(), sdk) is SelectionCheck.Problem)

    @Test fun `unknown file type names the file`() {
        val check = ConvertPlanner.check(listOf(file("a.png", FileFormat.PNG), file("thing.xyz", null)), sdk)
        assertTrue((check as SelectionCheck.Problem).message.contains("thing.xyz"))
    }

    @Test fun `mixed families are a problem`() =
        assertTrue(ConvertPlanner.check(listOf(file("a.png", FileFormat.PNG), file("b.mp3", FileFormat.MP3)), sdk) is SelectionCheck.Problem)

    @Test fun `mixed image formats can target either of them`() {
        val targets = targets(file("a.jpg", FileFormat.JPG), file("b.png", FileFormat.PNG))
        assertTrue(targets.containsAll(listOf(FileFormat.JPG, FileFormat.PNG, FileFormat.WEBP, FileFormat.PDF)))
        assertFalse(FileFormat.MP3 in targets)
    }

    @Test fun `files never target their own single format`() =
        assertFalse(FileFormat.JPG in targets(file("a.jpg", FileFormat.JPG), file("b.jpg", FileFormat.JPG)))

    @Test fun `route badges`() {
        assertEquals(RouteBadge.OFFLINE_BASIC, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.PDF, sdk, hasApiKey = false))
        assertEquals(RouteBadge.CLOUD, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.PDF, sdk, hasApiKey = true))
        assertEquals(RouteBadge.CLOUD_NEEDS_KEY, ConvertPlanner.badge(listOf(FileFormat.DOCX), FileFormat.ODT, sdk, hasApiKey = false))
        assertEquals(RouteBadge.OFFLINE, ConvertPlanner.badge(listOf(FileFormat.MP4), FileFormat.MP3, sdk, hasApiKey = false))
    }

    @Test fun `option sections per target`() {
        assertEquals(setOf(OptionSection.GIF, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.MP4), FileFormat.GIF))
        assertEquals(setOf(OptionSection.AUDIO_BITRATE, OptionSection.CHANNELS, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.WAV), FileFormat.OPUS))
        assertEquals(setOf(OptionSection.SAMPLE_RATE, OptionSection.CHANNELS, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.WAV), FileFormat.FLAC))
        assertEquals(setOf(OptionSection.VIDEO_RESOLUTION, OptionSection.VIDEO_QUALITY, OptionSection.TRIM), ConvertPlanner.sections(listOf(FileFormat.MP4), FileFormat.MKV))
        assertEquals(setOf(OptionSection.IMAGE_QUALITY, OptionSection.MAX_DIMENSION), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.JPG))
        assertEquals(setOf(OptionSection.MAX_DIMENSION), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.ICO))
        assertEquals(emptySet<OptionSection>(), ConvertPlanner.sections(listOf(FileFormat.PNG), FileFormat.PDF))
        assertEquals(setOf(OptionSection.IMAGE_QUALITY), ConvertPlanner.sections(listOf(FileFormat.PDF), FileFormat.JPG))
        assertEquals(emptySet<OptionSection>(), ConvertPlanner.sections(listOf(FileFormat.DOCX), FileFormat.PDF))
    }

    @Test fun `150 photos to pdf become one request`() {
        val inputs = List(150) { PreparedInput("/q/$it.jpg", "photo-$it.jpg", FileFormat.JPG) }
        val requests = ConvertPlanner.buildRequests(inputs, FileFormat.PDF, ConversionOptions())
        assertEquals(1, requests.size)
        assertEquals(150, requests.single().inputs.size)
        assertEquals(FileFormat.JPG, requests.single().source)
    }

    @Test fun `other batches become one request per file and skip files already in the target format`() {
        val inputs = listOf(
            PreparedInput("/q/0.jpg", "a.jpg", FileFormat.JPG),
            PreparedInput("/q/1.png", "b.png", FileFormat.PNG),
            PreparedInput("/q/2.webp", "c.webp", FileFormat.WEBP),
        )
        val requests = ConvertPlanner.buildRequests(inputs, FileFormat.JPG, ConversionOptions())
        assertEquals(listOf("b.png", "c.webp"), requests.map { it.inputs.single().displayName })
        assertEquals(listOf(FileFormat.PNG, FileFormat.WEBP), requests.map { it.source })
    }

    @Test fun `parses seconds`() {
        assertEquals(1_500L, ConvertPlanner.parseSeconds("1.5"))
        assertEquals(1_500L, ConvertPlanner.parseSeconds(" 1,5 "))
        assertNull(ConvertPlanner.parseSeconds("-1"))
        assertNull(ConvertPlanner.parseSeconds("abc"))
        assertNull(ConvertPlanner.parseSeconds(""))
    }

    @Test fun `validates trim fields`() {
        assertNull(ConvertPlanner.trimError("", "", forGif = true))
        assertNull(ConvertPlanner.trimError("2", "10", forGif = false))
        assertEquals("End must be after start.", ConvertPlanner.trimError("5", "2", forGif = false))
        assertEquals("Start time must be a number of seconds.", ConvertPlanner.trimError("x", "", forGif = false))
        assertEquals("GIFs can be at most 30 seconds long.", ConvertPlanner.trimError("0", "45", forGif = true))
        assertNull(ConvertPlanner.trimError("0", "45", forGif = false))
    }
}
