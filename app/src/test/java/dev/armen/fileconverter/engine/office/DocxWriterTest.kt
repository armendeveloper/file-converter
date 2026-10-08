package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class DocxWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writer = DocxWriter()
    private val reader = DocxReader(kxmlFactory)

    @Test fun `round trips through the reader`() {
        val text = "Line 1\n\tIndented & <ok>\n\nLast"
        val file = tmp.newFile("out.docx")
        writer.write(text, file)
        assertEquals(text, reader.read(file))
    }

    @Test fun `normalises windows line endings`() {
        val file = tmp.newFile("crlf.docx")
        writer.write("a\r\nb", file)
        assertEquals("a\nb", reader.read(file))
    }

    @Test fun `paragraph markup`() {
        assertEquals(
            "<w:p><w:r><w:t xml:space=\"preserve\">a</w:t><w:tab/><w:t xml:space=\"preserve\">b &amp; c</w:t></w:r></w:p>",
            writer.paragraph("a\tb & c"),
        )
        assertEquals("<w:p/>", writer.paragraph(""))
    }

    @Test fun `writes every required package part`() {
        val file = tmp.newFile("parts.docx")
        writer.write("x", file)
        ZipFile(file).use { zip ->
            listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml")
                .forEach { assertNotNull(it, zip.getEntry(it)) }
        }
    }
}
