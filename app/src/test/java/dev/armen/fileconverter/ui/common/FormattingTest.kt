package dev.armen.fileconverter.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test fun `formats byte sizes`() {
        assertEquals("", Formatting.bytes(null))
        assertEquals("0 B", Formatting.bytes(0))
        assertEquals("1023 B", Formatting.bytes(1023))
        assertEquals("1.5 KB", Formatting.bytes(1536))
        assertEquals("1.0 MB", Formatting.bytes(1_048_576))
        assertEquals("5.0 GB", Formatting.bytes(5_368_709_120))
    }
}
