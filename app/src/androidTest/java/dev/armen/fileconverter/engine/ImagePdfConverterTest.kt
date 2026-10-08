package dev.armen.fileconverter.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePdfConverterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File

    @Before fun setUp() { dir = File(context.cacheDir, "image-test-${System.nanoTime()}").apply { mkdirs() } }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun run(
        converter: Converter,
        inputs: List<File>,
        source: FileFormat,
        target: FileFormat,
        options: ConversionOptions = ConversionOptions(),
    ): List<File> = runBlocking {
        val out = File(dir, "out-${System.nanoTime()}").apply { mkdirs() }
        converter.convert(ConversionTask(inputs, source, target, options, out, "result")) {}
    }

    private fun ascii(file: File, from: Int, count: Int) =
        String(TestMedia.header(file, from + count), from, count, Charsets.US_ASCII)

    private fun pageCount(pdf: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    private fun makePdf(pages: Int): File {
        val document = PdfDocument()
        repeat(pages) { i ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", 20f, 40f, Paint())
            document.finishPage(page)
        }
        return File(dir, "doc.pdf").apply {
            outputStream().use { document.writeTo(it) }
            document.close()
        }
    }

    @Test fun transparentPngToJpgIsFlattenedOnWhite() {
        val jpg = run(ImageConverter(), listOf(TestMedia.png(dir, "a.png", width = 200, height = 100, alpha = true)), FileFormat.PNG, FileFormat.JPG).single()
        val head = TestMedia.header(jpg, 2)
        assertEquals(0xFF, head[0].toInt() and 0xFF)
        assertEquals(0xD8, head[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeFile(jpg.path)
        assertEquals(200, decoded.width)
        val pixel = decoded.getPixel(150, 50) // right half was transparent
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test fun resizesToTheMaxDimension() {
        val png = run(ImageConverter(), listOf(TestMedia.png(dir, "big.png", width = 400, height = 200)), FileFormat.PNG, FileFormat.PNG, ConversionOptions(maxDimension = 100)).single()
        val decoded = BitmapFactory.decodeFile(png.path)
        assertEquals(100, decoded.width)
        assertEquals(50, decoded.height)
    }

    @Test fun pngToWebp() {
        val webp = run(ImageConverter(), listOf(TestMedia.png(dir, "a.png")), FileFormat.PNG, FileFormat.WEBP).single()
        assertEquals("RIFF", ascii(webp, 0, 4))
        assertEquals("WEBP", ascii(webp, 8, 4))
    }

    @Test fun corruptImageIsUnreadableInput() {
        val bad = File(dir, "bad.png").apply { writeText("not an image") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(ImageConverter(), listOf(bad), FileFormat.PNG, FileFormat.JPG) }
    }

    @Test fun imagesMergeIntoOnePdf() {
        val inputs = listOf(TestMedia.png(dir, "0-a.png"), TestMedia.png(dir, "1-b.png", width = 640, height = 200))
        val pdf = run(LocalPdfConverter(), inputs, FileFormat.PNG, FileFormat.PDF).single()
        assertEquals("result.pdf", pdf.name)
        assertEquals(2, pageCount(pdf))
    }

    @Test fun mergesManyLargePhotosIntoOneCompactPdf() {
        val pixels = IntArray(3000 * 2000) { kotlin.random.Random(it).nextInt() or (0xFF shl 24) }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, 3000, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        val photo = File(dir, "photo.jpg").apply { outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }
        bitmap.recycle()
        val pages = 40
        val pdf = run(LocalPdfConverter(), List(pages) { photo }, FileFormat.JPG, FileFormat.PDF).single()
        assertEquals(pages, pageCount(pdf))
        // Pages must be stored as JPEG, not as raw pixels: no bigger than the photos themselves.
        assertTrue("PDF is ${pdf.length()} bytes for $pages photos of ${photo.length()} bytes", pdf.length() < pages * photo.length())
    }

    @Test fun pdfPagesBecomeNumberedImages() {
        val outputs = run(LocalPdfConverter(), listOf(makePdf(3)), FileFormat.PDF, FileFormat.PNG)
        assertEquals(listOf("result-1.png", "result-2.png", "result-3.png"), outputs.map { it.name })
        outputs.forEach { assertEquals("PNG", ascii(it, 1, 3)) }
    }

    @Test fun singlePagePdfKeepsTheBaseName() =
        assertEquals("result.jpg", run(LocalPdfConverter(), listOf(makePdf(1)), FileFormat.PDF, FileFormat.JPG).single().name)

    @Test fun longTextSpansSeveralPdfPages() {
        val txt = File(dir, "long.txt").apply { writeText((1..300).joinToString("\n") { "Line $it\twith a tab" }) }
        val pdf = run(LocalPdfConverter(), listOf(txt), FileFormat.TXT, FileFormat.PDF).single()
        assertEquals("%PDF", ascii(pdf, 0, 4))
        assertTrue(pageCount(pdf) > 1)
    }

    @Test fun emptyTextGivesOnePage() {
        val txt = File(dir, "empty.txt").apply { writeText("") }
        assertEquals(1, pageCount(run(LocalPdfConverter(), listOf(txt), FileFormat.TXT, FileFormat.PDF).single()))
    }

    @Test fun damagedPdfIsUnreadableInput() {
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        assertThrows(ConversionError.UnreadableInput::class.java) { run(LocalPdfConverter(), listOf(bad), FileFormat.PDF, FileFormat.PNG) }
    }
}
