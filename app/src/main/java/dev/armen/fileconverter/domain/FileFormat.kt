package dev.armen.fileconverter.domain

import dev.armen.fileconverter.domain.FormatFamily.AUDIO
import dev.armen.fileconverter.domain.FormatFamily.DOCUMENT
import dev.armen.fileconverter.domain.FormatFamily.IMAGE
import dev.armen.fileconverter.domain.FormatFamily.VIDEO

enum class FormatFamily { IMAGE, AUDIO, VIDEO, DOCUMENT }

enum class FileFormat(
    val family: FormatFamily,
    val extensions: List<String>,
    val mimeTypes: List<String>,
) {
    JPG(IMAGE, listOf("jpg", "jpeg"), listOf("image/jpeg")),
    PNG(IMAGE, listOf("png"), listOf("image/png")),
    WEBP(IMAGE, listOf("webp"), listOf("image/webp")),
    BMP(IMAGE, listOf("bmp"), listOf("image/bmp", "image/x-ms-bmp")),
    GIF(IMAGE, listOf("gif"), listOf("image/gif")),
    HEIC(IMAGE, listOf("heic", "heif"), listOf("image/heic", "image/heif")),
    AVIF(IMAGE, listOf("avif"), listOf("image/avif")),
    TIFF(IMAGE, listOf("tiff", "tif"), listOf("image/tiff")),
    ICO(IMAGE, listOf("ico"), listOf("image/x-icon", "image/vnd.microsoft.icon")),

    MP3(AUDIO, listOf("mp3"), listOf("audio/mpeg", "audio/mp3")),
    M4A(AUDIO, listOf("m4a"), listOf("audio/mp4", "audio/x-m4a", "audio/m4a")),
    AAC(AUDIO, listOf("aac"), listOf("audio/aac", "audio/x-aac", "audio/aacp")),
    WAV(AUDIO, listOf("wav"), listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")),
    FLAC(AUDIO, listOf("flac"), listOf("audio/flac", "audio/x-flac")),
    OGG(AUDIO, listOf("ogg", "oga"), listOf("audio/ogg", "application/ogg", "audio/vorbis")),
    OPUS(AUDIO, listOf("opus"), listOf("audio/opus")),
    AIFF(AUDIO, listOf("aiff", "aif"), listOf("audio/aiff", "audio/x-aiff")),
    WMA(AUDIO, listOf("wma"), listOf("audio/x-ms-wma")),

    MP4(VIDEO, listOf("mp4", "m4v"), listOf("video/mp4")),
    MKV(VIDEO, listOf("mkv"), listOf("video/x-matroska")),
    WEBM(VIDEO, listOf("webm"), listOf("video/webm")),
    MOV(VIDEO, listOf("mov"), listOf("video/quicktime")),
    AVI(VIDEO, listOf("avi"), listOf("video/x-msvideo", "video/avi")),
    THREE_GP(VIDEO, listOf("3gp"), listOf("video/3gpp")),

    PDF(DOCUMENT, listOf("pdf"), listOf("application/pdf")),
    DOCX(DOCUMENT, listOf("docx"), listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    DOC(DOCUMENT, listOf("doc"), listOf("application/msword")),
    ODT(DOCUMENT, listOf("odt"), listOf("application/vnd.oasis.opendocument.text")),
    RTF(DOCUMENT, listOf("rtf"), listOf("application/rtf", "text/rtf")),
    TXT(DOCUMENT, listOf("txt"), listOf("text/plain")),
    HTML(DOCUMENT, listOf("html", "htm"), listOf("text/html")),
    MD(DOCUMENT, listOf("md", "markdown"), listOf("text/markdown", "text/x-markdown")),
    XLSX(DOCUMENT, listOf("xlsx"), listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    XLS(DOCUMENT, listOf("xls"), listOf("application/vnd.ms-excel")),
    ODS(DOCUMENT, listOf("ods"), listOf("application/vnd.oasis.opendocument.spreadsheet")),
    CSV(DOCUMENT, listOf("csv"), listOf("text/csv", "text/comma-separated-values")),
    PPTX(DOCUMENT, listOf("pptx"), listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    PPT(DOCUMENT, listOf("ppt"), listOf("application/vnd.ms-powerpoint")),
    ODP(DOCUMENT, listOf("odp"), listOf("application/vnd.oasis.opendocument.presentation")),
    EPUB(DOCUMENT, listOf("epub"), listOf("application/epub+zip")),
    MOBI(DOCUMENT, listOf("mobi"), listOf("application/x-mobipocket-ebook")),
    AZW3(DOCUMENT, listOf("azw3"), listOf("application/vnd.amazon.ebook")),
    ;

    /** Extension used for output files and for CloudConvert format names. */
    val extension: String get() = extensions.first()
    val mimeType: String get() = mimeTypes.first()
    val label: String get() = if (this == THREE_GP) "3GP" else name
}
