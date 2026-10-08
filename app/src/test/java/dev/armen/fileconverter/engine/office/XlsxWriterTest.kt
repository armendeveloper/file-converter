package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class XlsxWriterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writer = XlsxWriter()

    @Test fun `round trips through the reader`() {
        val file = tmp.newFile("out.xlsx")
        writer.write(
            listOf(
                listOf("id", "name", "amount"),
                listOf("007", "Ann, \"A\"", "12.50"),
                listOf("1234567890123456", "<tag> & more", "-3"),
                listOf("", "x"),
                listOf("a\u0001b"),
            ),
            file,
        )
        val sheet = XlsxReader(kxmlFactory).read(file).single()
        assertEquals("Sheet1", sheet.name)
        assertEquals(
            listOf(
                listOf("id", "name", "amount"),
                listOf("007", "Ann, \"A\"", "12.50"),
                listOf("1234567890123456", "<tag> & more", "-3"),
                listOf("", "x"),
                listOf("ab"),
            ),
            sheet.rows,
        )
    }

    @Test fun `keeps ids as text and real numbers as numbers`() {
        val file = tmp.newFile("types.xlsx")
        writer.write(listOf(listOf("007", "1234567890123456", "1e5", "-3", "12.50")), file)
        val xml = readZipEntry(file, "xl/worksheets/sheet1.xml")
        assertTrue(xml, xml.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">007</t></is></c>"""))
        assertTrue(xml, xml.contains("""<c r="B1" t="inlineStr">"""))
        assertTrue(xml, xml.contains("""<c r="C1" t="inlineStr">"""))
        assertTrue(xml, xml.contains("""<c r="D1"><v>-3</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="E1"><v>12.50</v></c>"""))
    }

    @Test fun `writes every required package part`() {
        val file = tmp.newFile("parts.xlsx")
        writer.write(listOf(listOf("a")), file)
        ZipFile(file).use { zip ->
            listOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml",
            ).forEach { assertNotNull(it, zip.getEntry(it)) }
        }
    }

    @Test fun `column names`() =
        assertEquals(listOf("A", "Z", "AA", "ZZ", "AAA"), listOf(0, 25, 26, 701, 702).map { XlsxWriter.columnName(it) })

    @Test fun `sheet names are made valid`() {
        assertEquals("a_b_1_", XlsxWriter.safeSheetName("a/b[1]"))
        assertEquals(31, XlsxWriter.safeSheetName("x".repeat(40)).length)
        assertEquals("Sheet1", XlsxWriter.safeSheetName("  "))
    }
}
