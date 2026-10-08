package dev.armen.fileconverter.engine.office

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.engine.ConversionTask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfficeLiteConverterTest {
    @get:Rule val tmp = TemporaryFolder()
    private var pdfText: String? = null
    private val progress = mutableListOf<Float>()
    private val converter = OfficeLiteConverter(XlsxReader(kxmlFactory), XlsxWriter(), DocxReader(kxmlFactory), DocxWriter()) { text, out ->
        pdfText = text
        out.writeText("%PDF-fake")
    }

    private fun run(input: File, source: FileFormat, target: FileFormat): List<File> = runBlocking {
        converter.convert(ConversionTask(listOf(input), source, target, ConversionOptions(), tmp.newFolder(), "book")) { progress += it }
    }

    @Test fun `xlsx with two sheets gives one csv per sheet`() {
        val outputs = run(tmp.newFile("in.xlsx").also(::writeExcelFixture), FileFormat.XLSX, FileFormat.CSV)
        assertEquals(listOf("book-People.csv", "book-Q&A.csv"), outputs.map { it.name })
        val first = outputs[0].readText(Charsets.UTF_8)
        assertTrue(first.startsWith("﻿Name,Joined,Score\r\nAnn Lee,2024-01-15,98.5\r\n"))
        assertEquals("﻿x,7\r\n", outputs[1].readText(Charsets.UTF_8))
        assertEquals(1f, progress.last())
    }

    @Test fun `single sheet xlsx keeps the base name`() {
        val xlsx = tmp.newFile("in.xlsx").also { XlsxWriter().write(listOf(listOf("a", "b"), listOf("1", "2")), it) }
        val output = run(xlsx, FileFormat.XLSX, FileFormat.CSV).single()
        assertEquals("book.csv", output.name)
        assertEquals("﻿a,b\r\n1,2\r\n", output.readText(Charsets.UTF_8))
    }

    @Test fun `utf-16 semicolon csv becomes xlsx`() {
        val csv = tmp.newFile("in.csv").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "name;city\nAnn;Zürich\n".toByteArray(Charsets.UTF_16LE))
        }
        val output = run(csv, FileFormat.CSV, FileFormat.XLSX).single()
        assertEquals("book.xlsx", output.name)
        assertEquals(listOf(listOf("name", "city"), listOf("Ann", "Zürich")), XlsxReader(kxmlFactory).read(output).single().rows)
    }

    @Test fun `docx to txt`() {
        val docx = tmp.newFile("in.docx").also { DocxWriter().write("Hello\n\tWorld", it) }
        val output = run(docx, FileFormat.DOCX, FileFormat.TXT).single()
        assertEquals("book.txt", output.name)
        assertEquals("Hello\n\tWorld", output.readText(Charsets.UTF_8))
    }

    @Test fun `windows-1252 txt to docx`() {
        val txt = tmp.newFile("in.txt").apply { writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())) }
        val output = run(txt, FileFormat.TXT, FileFormat.DOCX).single()
        assertEquals("café", DocxReader(kxmlFactory).read(output))
    }

    @Test fun `docx to pdf hands the text to the pdf writer`() {
        val docx = tmp.newFile("in.docx").also { DocxWriter().write("Report body", it) }
        val output = run(docx, FileFormat.DOCX, FileFormat.PDF).single()
        assertEquals("book.pdf", output.name)
        assertEquals("Report body", pdfText)
    }

    @Test fun `damaged docx is unreadable input`() {
        val bad = tmp.newFile("bad.docx").apply { writeText("not a zip") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(bad, FileFormat.DOCX, FileFormat.TXT) }
    }

    @Test fun `other pairs are unsupported`() {
        val pdf = tmp.newFile("in.pdf").apply { writeText("%PDF") }
        assertThrows(ConversionError.UnsupportedPair::class.java) { run(pdf, FileFormat.PDF, FileFormat.DOCX) }
    }

    @Test fun `csv names are unique per sheet`() {
        assertEquals(listOf("book.csv"), OfficeLiteConverter.csvNames("book", listOf("Only")))
        assertEquals(listOf("book-a_b.csv", "book-a_b-2.csv"), OfficeLiteConverter.csvNames("book", listOf("a/b", "a:b")))
    }
}
