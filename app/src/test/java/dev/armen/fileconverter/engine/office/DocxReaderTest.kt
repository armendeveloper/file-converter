package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DocxReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reader = DocxReader(kxmlFactory)

    private val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
<w:body>
<w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>world</w:t></w:r><w:r><w:delText>gone</w:delText></w:r></w:p>
<w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>r1c1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>r1c2</w:t></w:r></w:p><w:p><w:r><w:t>second</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>r2c1</w:t></w:r></w:p></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>
<w:p><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wps:txbx><w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></wps:txbx></w:drawing></mc:Choice><mc:Fallback><w:pict><w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>
<w:p><w:r><w:t>End &amp; done</w:t></w:r></w:p>
<w:sectPr/>
</w:body></w:document>"""

    @Test fun `extracts paragraphs, tabs, breaks, tables and text boxes once`() {
        val file = tmp.newFile("doc.docx")
        writeZip(file, mapOf("word/document.xml" to document))
        assertEquals("Hello world\nA\tB\nC\nr1c1\tr1c2 second\nr2c1\t\nBox\nEnd & done", reader.read(file))
    }

    @Test fun `ignores text outside the wordprocessing namespace`() {
        val file = tmp.newFile("chart.docx")
        writeZip(
            file,
            mapOf(
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Keep</w:t></w:r><a:t xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">chart</a:t></w:p></w:body></w:document>""",
            ),
        )
        assertEquals("Keep", reader.read(file))
    }

    @Test fun `zip without a document part is an IOException`() {
        val file = tmp.newFile("other.docx")
        writeZip(file, mapOf("hello.txt" to "hi"))
        assertThrows(IOException::class.java) { reader.read(file) }
    }
}
