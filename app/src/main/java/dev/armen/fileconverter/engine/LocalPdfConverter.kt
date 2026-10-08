package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.FormatFamily
import dev.armen.fileconverter.domain.FormatRegistry
import dev.armen.fileconverter.domain.TextDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Images -> one PDF, PDF -> PNG/JPG pages, TXT -> PDF. All on device. */
class LocalPdfConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            when {
                task.target == FileFormat.PDF && task.source.family == FormatFamily.IMAGE -> listOf(imagesToPdf(task, onProgress))
                task.source == FileFormat.PDF && (task.target == FileFormat.PNG || task.target == FileFormat.JPG) ->
                    pdfToImages(task, onProgress)
                task.source == FileFormat.TXT && task.target == FileFormat.PDF -> {
                    val out = File(task.outputDir, FileNames.withExtension(task.baseName, FileFormat.PDF))
                    TextPdfWriter.write(TextDecoding.decode(task.inputs.single().readBytes()), out)
                    onProgress(1f)
                    listOf(out)
                }
                else -> throw ConversionError.UnsupportedPair(task.source, task.target)
            }
        }

    private fun imagesToPdf(task: ConversionTask, onProgress: (Float) -> Unit): File {
        val out = File(task.outputDir, FileNames.withExtension(task.baseName, FileFormat.PDF))
        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            task.inputs.forEachIndexed { index, file ->
                val bitmap = BitmapLoader.load(file, PDF_IMAGE_MAX_DIMENSION)
                    ?: throw ConversionError.UnreadableInput(FormatRegistry.detect(file.name, null))
                val landscape = bitmap.width > bitmap.height
                val pageWidth = if (landscape) TextPdfWriter.PAGE_HEIGHT else TextPdfWriter.PAGE_WIDTH
                val pageHeight = if (landscape) TextPdfWriter.PAGE_WIDTH else TextPdfWriter.PAGE_HEIGHT
                val scale = minOf(
                    (pageWidth - 2 * IMAGE_MARGIN).toFloat() / bitmap.width,
                    (pageHeight - 2 * IMAGE_MARGIN).toFloat() / bitmap.height,
                )
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (pageWidth - width) / 2f
                val top = (pageHeight - height) / 2f
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
                document.finishPage(page)
                onProgress((index + 1f) / task.inputs.size * 0.95f)
            }
            out.outputStream().buffered().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        onProgress(1f)
        return out
    }

    private fun pdfToImages(task: ConversionTask, onProgress: (Float) -> Unit): List<File> {
        val descriptor = ParcelFileDescriptor.open(task.inputs.single(), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            throw ConversionError.EngineFailed("This PDF is password-protected.", e)
        } catch (e: IOException) {
            descriptor.close()
            throw ConversionError.UnreadableInput(FileFormat.PDF, e)
        }
        return renderer.use { pdf ->
            val count = pdf.pageCount
            val digits = count.toString().length
            (0 until count).map { index ->
                pdf.openPage(index).use { page ->
                    val scale = RENDER_DPI / 72f
                    var width = (page.width * scale).roundToInt()
                    var height = (page.height * scale).roundToInt()
                    val longest = max(width, height)
                    if (longest > MAX_RENDER_DIMENSION) {
                        val shrink = MAX_RENDER_DIMENSION.toFloat() / longest
                        width = max(1, (width * shrink).roundToInt())
                        height = max(1, (height * shrink).roundToInt())
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val name = if (count == 1) {
                        FileNames.withExtension(task.baseName, task.target)
                    } else {
                        "${task.baseName}-${(index + 1).toString().padStart(digits, '0')}.${task.target.extension}"
                    }
                    val out = File(task.outputDir, name)
                    val format = if (task.target == FileFormat.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    out.outputStream().buffered().use { bitmap.compress(format, task.options.imageQuality.coerceIn(1, 100), it) }
                    bitmap.recycle()
                    onProgress((index + 1f) / count)
                    out
                }
            }
        }
    }

    private companion object {
        const val PDF_IMAGE_MAX_DIMENSION = 2000
        const val IMAGE_MARGIN = 24
        const val RENDER_DPI = 150
        const val MAX_RENDER_DIMENSION = 4096
    }
}
