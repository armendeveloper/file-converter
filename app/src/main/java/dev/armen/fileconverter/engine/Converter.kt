package dev.armen.fileconverter.engine

import dev.armen.fileconverter.domain.ConversionOptions
import dev.armen.fileconverter.domain.FileFormat
import java.io.File

/**
 * One unit of work for an engine. [inputs] has one file, except images merged into one PDF.
 * Outputs must be written into [outputDir]; [baseName] is the sanitised name without extension.
 */
data class ConversionTask(
    val inputs: List<File>,
    val source: FileFormat,
    val target: FileFormat,
    val options: ConversionOptions,
    val outputDir: File,
    val baseName: String,
)

interface Converter {
    /** Returns the produced files. Reports progress in 0f..1f. Throws ConversionError on failure. */
    suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File>
}
