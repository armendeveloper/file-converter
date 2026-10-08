package dev.armen.fileconverter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun `strips only the last extension`() = assertEquals("My Report.final", FileNames.baseName("My Report.final.docx"))
    @Test fun `replaces characters illegal in file names`() = assertEquals("a_b_c_", FileNames.baseName("a/b:c?.mp3"))
    @Test fun `keeps accents and emoji`() = assertEquals("Résumé 📄", FileNames.baseName("Résumé 📄.docx"))
    @Test fun `removes control characters`() = assertEquals("ab", FileNames.sanitize("a\u0001b"))
    @Test fun `hidden or empty names fall back`() {
        assertEquals("converted", FileNames.baseName(".hidden"))
        assertEquals("converted", FileNames.sanitize("   "))
    }
    @Test fun `long names are truncated to 100 characters`() = assertEquals(100, FileNames.sanitize("x".repeat(300)).length)
    @Test fun `name without extension is kept`() = assertEquals("IMG-20261008-WA0001", FileNames.baseName("IMG-20261008-WA0001"))
    @Test fun `adds the target extension`() = assertEquals("clip.3gp", FileNames.withExtension("clip", FileFormat.THREE_GP))
}
