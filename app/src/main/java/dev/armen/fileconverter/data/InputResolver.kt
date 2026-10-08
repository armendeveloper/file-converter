package dev.armen.fileconverter.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FormatRegistry
import java.io.File
import java.io.IOException

data class InputInfo(val uri: Uri, val displayName: String, val sizeBytes: Long?, val mimeType: String?) {
    val format: FileFormat? get() = FormatRegistry.detect(displayName, mimeType)
}

class InputResolver(private val resolver: ContentResolver) {

    fun resolve(uri: Uri): InputInfo {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = if (c.isNull(0)) null else c.getString(0)
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return InputInfo(uri, displayName, size, runCatching { resolver.getType(uri) }.getOrNull())
    }

    fun copyToFile(uri: Uri, dest: File) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { source -> dest.outputStream().use { source.copyTo(it) } }
    }
}
