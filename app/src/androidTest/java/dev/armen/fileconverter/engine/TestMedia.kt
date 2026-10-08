package dev.armen.fileconverter.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** Generates small media fixtures on the device so the repo needs no binary test files. */
object TestMedia {
    fun wav(dir: File, name: String = "tone.wav", seconds: Int = 2, rate: Int = 44_100): File {
        val samples = seconds * rate
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) pcm.putShort((sin(2 * PI * 440 * i / rate) * 12_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + samples * 2); put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(samples * 2)
        }
        return File(dir, name).apply { writeBytes(header.array() + pcm.array()) }
    }

    fun png(
        dir: File,
        name: String,
        width: Int = 320,
        height: Int = 240,
        color: Int = Color.rgb(30, 120, 220),
        alpha: Boolean = false,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (alpha) {
            bitmap.eraseColor(Color.TRANSPARENT)
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { this.color = color })
        } else {
            canvas.drawColor(color)
        }
        return File(dir, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun mp4(dir: File, withAudio: Boolean = true): File {
        val frames = File(dir, "frames").apply { mkdirs() }
        repeat(30) { i -> png(frames, String.format(Locale.US, "f%03d.png", i), color = Color.rgb(i * 8, 100, 255 - i * 8)) }
        val out = File(dir, if (withAudio) "clip.mp4" else "silent.mp4")
        val args = mutableListOf("-y", "-framerate", "15", "-i", File(frames, "f%03d.png").absolutePath)
        if (withAudio) args += listOf("-i", wav(dir, "clip-audio.wav").absolutePath)
        args += listOf("-c:v", "libx264", "-pix_fmt", "yuv420p")
        if (withAudio) args += listOf("-c:a", "aac", "-shortest")
        args += out.absolutePath
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        check(ReturnCode.isSuccess(session.returnCode)) { session.allLogsAsString }
        return out
    }

    fun header(file: File, count: Int): ByteArray {
        val buffer = ByteArray(count)
        file.inputStream().use { input ->
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n < 0) break
                read += n
            }
        }
        return buffer
    }
}
