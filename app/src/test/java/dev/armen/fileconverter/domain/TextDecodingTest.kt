package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun `utf8 with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xEF, 0xBB, 0xBF) + "hé".toByteArray(Charsets.UTF_8)))
    @Test fun `utf16 little endian with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xFF, 0xFE) + "hé".toByteArray(Charsets.UTF_16LE)))
    @Test fun `utf16 big endian with bom`() = assertEquals("hé", TextDecoding.decode(bytes(0xFE, 0xFF) + "hé".toByteArray(Charsets.UTF_16BE)))
    @Test fun `plain utf8`() = assertEquals("naïve ✓", TextDecoding.decode("naïve ✓".toByteArray(Charsets.UTF_8)))
    @Test fun `invalid utf8 falls back to windows-1252`() = assertEquals("café", TextDecoding.decode(bytes(0x63, 0x61, 0x66, 0xE9)))
    @Test fun `empty input`() = assertEquals("", TextDecoding.decode(ByteArray(0)))
}
