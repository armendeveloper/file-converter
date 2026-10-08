package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionRequestTest {
    @Test fun `round trips through json`() {
        val request = ConversionRequest(
            inputs = listOf(ConversionInput("/data/q/0-a.mp4", "a.mp4"), ConversionInput("/data/q/1-b.mp4", "b é.mp4")),
            source = FileFormat.MP4,
            target = FileFormat.GIF,
            options = ConversionOptions(trimStartMs = 1_000, trimEndMs = 4_000, videoQuality = VideoQuality.HIGH, gifFps = 15),
        )
        assertEquals(request, ConversionRequest.fromJson(request.toJson()))
    }

    @Test fun `ignores unknown keys`() {
        val json = """{"inputs":[{"path":"/p","displayName":"n.txt"}],"source":"TXT","target":"PDF","future":true}"""
        assertEquals(FileFormat.PDF, ConversionRequest.fromJson(json).target)
    }
}
