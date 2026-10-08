package dev.armen.fileconverter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegPdfWriterTest {
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun write(pages: Int): ByteArray {
        val out = ByteArrayOutputStream()
        JpegPdfWriter(out).use { writer ->
            repeat(pages) { writer.addPage(fakeJpeg, widthPx = 400, heightPx = 300, pageWidth = 842, pageHeight = 595, margin = 24) }
        }
        return out.toByteArray()
    }

    private fun ascii(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test fun `writes one JPEG image object and one page per photo`() {
        val pdf = ascii(write(3))
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))
        assertTrue(pdf.contains("/Type /Pages /Kids [") && pdf.contains("/Count 3"))
        assertEquals(3, Regex("/Subtype /Image").findAll(pdf).count())
        assertEquals(3, Regex("/Filter /DCTDecode").findAll(pdf).count())
        assertEquals(3, Regex("/Type /Page /Parent").findAll(pdf).count())
    }

    @Test fun `embeds the JPEG bytes unchanged`() {
        val pdf = write(1)
        val index = ascii(pdf).indexOf("stream\n") + "stream\n".length
        assertTrue(pdf.copyOfRange(index, index + fakeJpeg.size).contentEquals(fakeJpeg))
    }

    @Test fun `xref offsets point at their objects`() {
        val pdf = ascii(write(2))
        val xrefStart = Regex("startxref\n(\\d+)").find(pdf)!!.groupValues[1].toInt()
        assertTrue(pdf.startsWith("xref", xrefStart))
        val entries = Regex("(\\d{10}) 00000 n ").findAll(pdf.substring(xrefStart)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(1 + 1 + 2 * 3, entries.size) // catalog, pages, and image + content + page per photo
        entries.forEachIndexed { i, offset -> assertTrue("object ${i + 1}", pdf.startsWith("${i + 1} 0 obj", offset)) }
    }

    @Test fun `image is scaled to fit inside the margins and centred`() {
        val pdf = ascii(write(1))
        // 400x300 into 842x595 minus 24 pt margins: scale = min(794/400, 547/300) = 1.8233 -> 729.33 x 547
        assertTrue(pdf, pdf.contains("q 729.33 0 0 547.00 56.33 24.00 cm /Im0 Do Q"))
    }
}
