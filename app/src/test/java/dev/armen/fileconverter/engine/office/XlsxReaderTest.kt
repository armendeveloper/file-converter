package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class XlsxReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reader = XlsxReader(kxmlFactory)

    @Test fun `reads sheets the way Excel writes them`() {
        val file = tmp.newFile("book.xlsx").also(::writeExcelFixture)
        val sheets = reader.read(file)
        assertEquals(listOf("People", "Q&A"), sheets.map { it.name })
        assertEquals(
            listOf(
                listOf("Name", "Joined", "Score"),
                listOf("Ann Lee", "2024-01-15", "98.5"),
                listOf("TRUE", "", "42"),
                emptyList(),
                listOf("#DIV/0!", "", "", "2024-01-15T10:30:00"),
            ),
            sheets[0].rows,
        )
        assertEquals(listOf(listOf("x", "7")), sheets[1].rows)
    }

    @Test fun `uses the 1904 date system when the workbook says so`() {
        val file = tmp.newFile("mac.xlsx")
        writeMinimalWorkbook(file, """<row r="1"><c r="A1" s="1"><v>0</v></c></row>""", numFmtIds = listOf(0, 14), date1904 = true)
        assertEquals(listOf(listOf("1904-01-01")), reader.read(file).single().rows)
    }

    @Test fun `empty sheet has no rows`() {
        val file = tmp.newFile("empty.xlsx")
        writeMinimalWorkbook(file, "")
        assertEquals(emptyList<List<String>>(), reader.read(file).single().rows)
    }

    @Test fun `zip without a workbook is an IOException`() {
        val file = tmp.newFile("other.xlsx")
        writeZip(file, mapOf("hello.txt" to "hi"))
        assertThrows(IOException::class.java) { reader.read(file) }
    }

    @Test fun `a text file is not a workbook`() {
        val file = tmp.newFile("fake.xlsx").apply { writeText("name,age") }
        assertThrows(IOException::class.java) { reader.read(file) }
    }

    @Test fun `recognises date number formats`() {
        assertTrue(XlsxReader.isDateFormat(14, emptyMap()))
        assertFalse(XlsxReader.isDateFormat(0, emptyMap()))
        assertTrue(XlsxReader.isDateFormat(164, mapOf(164 to "yyyy\\-mm\\-dd")))
        assertFalse(XlsxReader.isDateFormat(165, mapOf(165 to "0.00")))
        assertFalse(XlsxReader.isDateFormat(166, mapOf(166 to "\"Day\" 0")))
        assertFalse(XlsxReader.isDateFormat(167, mapOf(167 to "[Red]0.00")))
    }

    @Test fun `converts serial numbers to ISO dates`() {
        assertEquals("2024-01-15", XlsxReader.serialToIso(45306.0, date1904 = false))
        assertEquals("2024-01-15T10:30:00", XlsxReader.serialToIso(45306.4375, date1904 = false))
        assertEquals("1904-01-01", XlsxReader.serialToIso(0.0, date1904 = true))
    }

    @Test fun `parses column letters`() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(25, XlsxReader.columnIndex("Z9"))
        assertEquals(26, XlsxReader.columnIndex("AA10"))
        assertEquals(701, XlsxReader.columnIndex("ZZ1"))
        assertNull(XlsxReader.columnIndex("12"))
    }
}
