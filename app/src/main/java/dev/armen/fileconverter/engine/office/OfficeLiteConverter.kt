package dev.armen.fileconverter.engine.office

import dev.armen.fileconverter.domain.ConversionError
import dev.armen.fileconverter.domain.ErrorMapper
import dev.armen.fileconverter.domain.FileFormat
import dev.armen.fileconverter.domain.FileNames
import dev.armen.fileconverter.domain.TextDecoding
import dev.armen.fileconverter.engine.ConversionTask
import dev.armen.fileconverter.engine.Converter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The five offline office conversions. [textToPdf] renders plain text (LocalPdf's TextPdfWriter on device). */
class OfficeLiteConverter(
    private val xlsxReader: XlsxReader,
    private val xlsxWriter: XlsxWriter,
    private val docxReader: DocxReader,
    private val docxWriter: DocxWriter,
    private val textToPdf: (String, File) -> Unit,
) : Converter {

    override suspend fun convert(task: ConversionTask, onProgress: (Float) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val input = task.inputs.single()
            val outputs = try {
                when (task.source to task.target) {
                    FileFormat.XLSX to FileFormat.CSV -> xlsxToCsv(input, task)
                    FileFormat.CSV to FileFormat.XLSX -> single(task) { out ->
                        xlsxWriter.write(CsvCodec.parse(TextDecoding.decode(input.readBytes())), out)
                    }
                    FileFormat.DOCX to FileFormat.TXT -> single(task) { out -> out.writeText(docxReader.read(input), Charsets.UTF_8) }
                    FileFormat.TXT to FileFormat.DOCX -> single(task) { out -> docxWriter.write(TextDecoding.decode(input.readBytes()), out) }
                    FileFormat.DOCX to FileFormat.PDF -> single(task) { out -> textToPdf(docxReader.read(input), out) }
                    else -> throw ConversionError.UnsupportedPair(task.source, task.target)
                }
            } catch (e: ConversionError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ErrorMapper.map(e)
                throw if (mapped is ConversionError.InsufficientStorage) mapped else ConversionError.UnreadableInput(task.source, e)
            }
            onProgress(1f)
            outputs
        }

    private inline fun single(task: ConversionTask, write: (File) -> Unit): List<File> {
        val out = File(task.outputDir, FileNames.withExtension(task.baseName, task.target))
        write(out)
        return listOf(out)
    }

    private fun xlsxToCsv(input: File, task: ConversionTask): List<File> {
        val sheets = xlsxReader.read(input).ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        val names = csvNames(task.baseName, sheets.map { it.name })
        return sheets.zip(names).map { (sheet, name) ->
            // BOM so Excel opens the UTF-8 file with the right encoding.
            File(task.outputDir, name).apply { writeText("\uFEFF" + CsvCodec.write(sheet.rows), Charsets.UTF_8) }
        }
    }

    internal companion object {
        fun csvNames(base: String, sheetNames: List<String>): List<String> {
            if (sheetNames.size == 1) return listOf("$base.csv")
            val used = HashSet<String>()
            return sheetNames.map { sheet ->
                val stem = "$base-${FileNames.sanitize(sheet)}"
                var candidate = "$stem.csv"
                var n = 2
                while (!used.add(candidate.lowercase())) {
                    candidate = "$stem-$n.csv"
                    n++
                }
                candidate
            }
        }
    }
}
