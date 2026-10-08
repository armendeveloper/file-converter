package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes images upright (EXIF applied) and never larger than a limit, so memory stays bounded. */
object BitmapLoader {
    /** Hard cap so a 100-megapixel photo cannot exhaust memory. */
    const val MAX_DECODE_DIMENSION = 8192

    /** Returns null when the file is not a decodable image. */
    fun load(file: File, maxDimension: Int?): Bitmap? {
        val limit = minOf(maxDimension ?: MAX_DECODE_DIMENSION, MAX_DECODE_DIMENSION)
        return if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(file, limit) else decodeLegacy(file, limit)
    }

    fun scaleDown(bitmap: Bitmap, limit: Int): Bitmap {
        val scale = limit.toFloat() / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    @RequiresApi(28)
    private fun decodeWithImageDecoder(file: File, limit: Int): Bitmap? = try {
        // ImageDecoder applies EXIF orientation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > limit) {
                val scale = limit.toFloat() / longest
                decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            }
        }
    } catch (e: ImageDecoder.DecodeException) {
        null
    }

    private fun decodeLegacy(file: File, limit: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val upright = applyExifOrientation(file, decoded)
        return if (max(upright.width, upright.height) > limit) scaleDown(upright, limit) else upright
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
