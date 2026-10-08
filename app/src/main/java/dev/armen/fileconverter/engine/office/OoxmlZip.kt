package dev.armen.fileconverter.engine.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object OoxmlZip {
    private const val MAX_PART_BYTES = 256L * 1024 * 1024

    /** Reads the parts whose names match [wanted]. A file that is not a zip yields no parts. */
    fun read(file: File, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (!entry.isDirectory && wanted(name)) parts[name] = readLimited(zip, name)
            }
        }
        return parts
    }

    private fun readLimited(input: InputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) throw IOException("$name is larger than ${MAX_PART_BYTES / (1024 * 1024)} MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

internal fun ZipOutputStream.putText(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}
