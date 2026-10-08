package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** JPG, PNG and WEBP output from any image Android can decode. */
class ImageConverter : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val bitmap = BitmapLoader.load(input, task.options.maxDimension)
                ?: throw ConversionError.UnreadableInput(task.source)
            val output = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
            val quality = task.options.imageQuality.coerceIn(1, 100)
            val toWrite = if (task.target == FileFormat.JPG && bitmap.hasAlpha()) flattenOnWhite(bitmap) else bitmap
            val written = output.outputStream().buffered().use { toWrite.compress(compressFormat(task, quality), quality, it) }
            if (!written) throw ConversionError.EngineFailed("The ${task.target.label} encoder failed.")
            onProgress(1f)
            listOf(output)
        }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(task: ConversionTask, quality: Int): Bitmap.CompressFormat = when (task.target) {
        FileFormat.JPG -> Bitmap.CompressFormat.JPEG
        FileFormat.PNG -> Bitmap.CompressFormat.PNG
        FileFormat.WEBP -> when {
            Build.VERSION.SDK_INT >= 30 && quality >= 100 -> Bitmap.CompressFormat.WEBP_LOSSLESS
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
        else -> throw ConversionError.UnsupportedPair(task.source, task.target)
    }
}
