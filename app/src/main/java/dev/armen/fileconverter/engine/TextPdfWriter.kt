package dev.armen.fileconverter.engine

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Renders plain text onto A4 pages (monospace 10 pt, word-wrapped). */
object TextPdfWriter {
    const val PAGE_WIDTH = 595 // A4 in PostScript points
    const val PAGE_HEIGHT = 842
    private const val MARGIN = 40

    fun write(text: String, out: File) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            color = Color.BLACK
        }
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN
        val source = text.replace("\r\n", "\n").replace("\t", "    ")
        val layout = StaticLayout.Builder.obtain(source, 0, source.length, paint, contentWidth)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        val document = PdfDocument()
        try {
            var line = 0
            var pageNumber = 1
            do {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentHeight) end++
                if (end == line) end = line + 1 // a single line taller than a page still gets placed
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create())
                val canvas = page.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                canvas.clipRect(0, top, contentWidth, layout.getLineBottom(end - 1))
                layout.draw(canvas)
                canvas.restore()
                document.finishPage(page)
                line = end
            } while (line < layout.lineCount)
            out.outputStream().buffered().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
