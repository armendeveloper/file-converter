package dev.armen.fileconverter.domain

object FileNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")
    private const val MAX_LENGTH = 100

    /** "My Report.final.docx" -> "My Report.final", sanitised. */
    fun baseName(displayName: String): String =
        sanitize(displayName.substringBeforeLast('.', missingDelimiterValue = displayName))

    /** Drops control characters, replaces characters illegal in file names with '_'. */
    fun sanitize(name: String): String =
        ILLEGAL.replace(CONTROL.replace(name, ""), "_").trim().trim('.').take(MAX_LENGTH).ifBlank { "converted" }

    fun withExtension(base: String, format: FileFormat): String = "$base.${format.extension}"
}
