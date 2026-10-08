package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FileFormat.AVIF
import dev.armen.fileconverter.domain.FileFormat.AZW3
import dev.armen.fileconverter.domain.FileFormat.BMP
import dev.armen.fileconverter.domain.FileFormat.CSV
import dev.armen.fileconverter.domain.FileFormat.DOC
import dev.armen.fileconverter.domain.FileFormat.DOCX
import dev.armen.fileconverter.domain.FileFormat.EPUB
import dev.armen.fileconverter.domain.FileFormat.GIF
import dev.armen.fileconverter.domain.FileFormat.HEIC
import dev.armen.fileconverter.domain.FileFormat.HTML
import dev.armen.fileconverter.domain.FileFormat.ICO
import dev.armen.fileconverter.domain.FileFormat.JPG
import dev.armen.fileconverter.domain.FileFormat.MD
import dev.armen.fileconverter.domain.FileFormat.MOBI
import dev.armen.fileconverter.domain.FileFormat.ODP
import dev.armen.fileconverter.domain.FileFormat.ODS
import dev.armen.fileconverter.domain.FileFormat.ODT
import dev.armen.fileconverter.domain.FileFormat.PDF
import dev.armen.fileconverter.domain.FileFormat.PNG
import dev.armen.fileconverter.domain.FileFormat.PPT
import dev.armen.fileconverter.domain.FileFormat.PPTX
import dev.armen.fileconverter.domain.FileFormat.RTF
import dev.armen.fileconverter.domain.FileFormat.TIFF
import dev.armen.fileconverter.domain.FileFormat.TXT
import dev.armen.fileconverter.domain.FileFormat.WEBP
import dev.armen.fileconverter.domain.FileFormat.XLS
import dev.armen.fileconverter.domain.FileFormat.XLSX

object FormatRegistry {

    private val GENERIC_MIME = setOf("application/octet-stream", "*/*", "application/x-binary", "application/unknown")

    /** Extension first (most specific); MIME type only when the name has no known extension. */
    fun detect(fileName: String?, mimeType: String?): FileFormat? {
        val ext = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
        if (ext.isNotEmpty()) {
            FileFormat.entries.firstOrNull { ext in it.extensions }?.let { return it }
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime.isNullOrEmpty() || mime in GENERIC_MIME) return null
        return FileFormat.entries.firstOrNull { mime in it.mimeTypes }
    }

    fun targetsFor(source: FileFormat, sdkInt: Int): List<FileFormat> =
        FileFormat.entries.filter { routesFor(source, it, sdkInt).isNotEmpty() }

    fun chooseRoute(source: FileFormat, target: FileFormat, sdkInt: Int, cloudAvailable: Boolean): Route? =
        routesFor(source, target, sdkInt).firstOrNull { it.engine != Engine.CLOUD || cloudAvailable }

    /** All routes for a pair, most preferred first: on-device FULL, then CLOUD, then on-device BASIC. */
    fun routesFor(source: FileFormat, target: FileFormat, sdkInt: Int): List<Route> {
        if (source == target) return emptyList()
        return when (source.family) {
            FormatFamily.IMAGE -> imageRoutes(source, target, sdkInt)
            FormatFamily.AUDIO ->
                if (target.family == FormatFamily.AUDIO) listOf(Route(Engine.FFMPEG)) else emptyList()
            FormatFamily.VIDEO ->
                if (target.family == FormatFamily.VIDEO || target.family == FormatFamily.AUDIO || target == GIF) {
                    listOf(Route(Engine.FFMPEG))
                } else {
                    emptyList()
                }
            FormatFamily.DOCUMENT -> documentRoutes(source, target)
        }
    }

    fun isNativelyDecodable(format: FileFormat, sdkInt: Int): Boolean = when (format) {
        JPG, PNG, WEBP, BMP, GIF, ICO -> true
        HEIC -> sdkInt >= 28
        AVIF -> sdkInt >= 31
        else -> false
    }

    private val NATIVE_IMAGE_OUTPUTS = setOf(JPG, PNG, WEBP)
    private val FFMPEG_IMAGE_OUTPUTS = setOf(BMP, TIFF, ICO, GIF)

    private fun imageRoutes(source: FileFormat, target: FileFormat, sdkInt: Int): List<Route> {
        val native = isNativelyDecodable(source, sdkInt)
        return when {
            target in NATIVE_IMAGE_OUTPUTS -> listOf(Route(if (native) Engine.IMAGE_NATIVE else Engine.FFMPEG))
            target in FFMPEG_IMAGE_OUTPUTS -> listOf(Route(Engine.FFMPEG))
            target == PDF && native -> listOf(Route(Engine.LOCAL_PDF))
            else -> emptyList()
        }
    }

    private val CLOUD_GROUPS: List<Set<FileFormat>> = listOf(
        setOf(DOCX, DOC, ODT, RTF, TXT, HTML, MD, PDF),
        setOf(XLSX, XLS, ODS, CSV, PDF),
        setOf(PPTX, PPT, ODP, PDF),
        setOf(EPUB, MOBI, AZW3, PDF),
    )

    private val LOCAL_FULL: Map<Pair<FileFormat, FileFormat>, Engine> = mapOf(
        (PDF to PNG) to Engine.LOCAL_PDF,
        (PDF to JPG) to Engine.LOCAL_PDF,
        (TXT to PDF) to Engine.LOCAL_PDF,
        (XLSX to CSV) to Engine.OFFICE_LITE,
        (CSV to XLSX) to Engine.OFFICE_LITE,
        (DOCX to TXT) to Engine.OFFICE_LITE,
        (TXT to DOCX) to Engine.OFFICE_LITE,
    )

    private val LOCAL_BASIC: Map<Pair<FileFormat, FileFormat>, Engine> = mapOf(
        (DOCX to PDF) to Engine.OFFICE_LITE,
    )

    private fun documentRoutes(source: FileFormat, target: FileFormat): List<Route> = buildList {
        LOCAL_FULL[source to target]?.let { add(Route(it)) }
        if (CLOUD_GROUPS.any { source in it && target in it }) add(Route(Engine.CLOUD))
        LOCAL_BASIC[source to target]?.let { add(Route(it, Fidelity.BASIC)) }
    }
}
