package dev.armen.fileconverter.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dev.armen.fileconverter.domain.ErrorMapper
import java.io.File
import java.io.IOException

/** Saves results to Download/FileConverter[/subfolder]. Throws ConversionError. */
class OutputStore(private val context: Context) {

    data class Saved(val uri: Uri, val displayName: String, val sizeBytes: Long)

    fun save(file: File, mimeType: String, subfolder: String? = null): Saved = try {
        if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(file, mimeType, subfolder) else saveToAppDownloads(file, subfolder)
    } catch (e: IOException) {
        throw ErrorMapper.map(e)
    }

    @RequiresApi(29)
    private fun saveToMediaStore(file: File, mimeType: String, subfolder: String?): Saved {
        val resolver = context.contentResolver
        val relativePath = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, ROOT_FOLDER, subfolder).joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create ${file.name} in Downloads")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not write ${file.name}")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: file.name
        return Saved(uri, name, file.length())
    }

    private fun saveToAppDownloads(file: File, subfolder: String?): Saved {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("External storage is not available")
        val dir = File(root, listOfNotNull(ROOT_FOLDER, subfolder).joinToString("/")).apply { mkdirs() }
        val dest = uniqueFile(dir, file.name)
        file.copyTo(dest)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        return Saved(uri, dest.name, dest.length())
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }

    companion object {
        const val ROOT_FOLDER = "FileConverter"
    }
}
