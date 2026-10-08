package dev.armen.fileconverter.engine

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale

/**
 * Streams a PDF in which every page shows one JPEG (embedded as-is with DCTDecode).
 * Only the current page's JPEG is in memory, unlike android.graphics.pdf.PdfDocument,
 * which keeps every page's bitmap until writeTo() and stores images losslessly.
 */
class JpegPdfWriter(output: OutputStream) : Closeable {
    private val out = BufferedOutputStream(output)
    private var position = 0L
    private val offsets = HashMap<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = FIRST_FREE_ID

    init {
        writeAscii("%PDF-1.4\n")
        writeBytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    /** Adds a page of [pageWidth] x [pageHeight] points with the image scaled to fit inside [margin] and centred. */
    fun addPage(jpeg: ByteArray, widthPx: Int, heightPx: Int, pageWidth: Int, pageHeight: Int, margin: Int) {
        val scale = minOf((pageWidth - 2f * margin) / widthPx, (pageHeight - 2f * margin) / heightPx)
        val drawWidth = widthPx * scale
        val drawHeight = heightPx * scale
        val left = (pageWidth - drawWidth) / 2f
        val bottom = (pageHeight - drawHeight) / 2f

        val imageId = beginObject()
        writeAscii(
            "<< /Type /XObject /Subtype /Image /Width $widthPx /Height $heightPx /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        writeBytes(jpeg)
        writeAscii("\nendstream\nendobj\n")

        val content = String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", drawWidth, drawHeight, left, bottom)
        val contentId = beginObject()
        writeAscii("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

        val pageId = beginObject()
        writeAscii(
            "<< /Type /Page /Parent $PAGES_ID 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
                "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n",
        )
        pageIds += pageId
    }

    override fun close() {
        beginObject(PAGES_ID)
        writeAscii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        beginObject(CATALOG_ID)
        writeAscii("<< /Type /Catalog /Pages $PAGES_ID 0 R >>\nendobj\n")

        val xref = position
        val size = nextId
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) table.append(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(id)))
        writeAscii(table.toString())
        writeAscii("trailer\n<< /Size $size /Root $CATALOG_ID 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.close()
    }

    private fun beginObject(id: Int = nextId++): Int {
        offsets[id] = position
        writeAscii("$id 0 obj\n")
        return id
    }

    private fun writeAscii(text: String) = writeBytes(text.toByteArray(Charsets.ISO_8859_1))

    private fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    private companion object {
        const val CATALOG_ID = 1
        const val PAGES_ID = 2
        const val FIRST_FREE_ID = 3
    }
}
