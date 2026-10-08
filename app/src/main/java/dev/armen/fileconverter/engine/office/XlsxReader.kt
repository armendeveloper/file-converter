package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap

data class Sheet(val name: String, val rows: List<List<String>>)

/** Reads cell values (as displayed text) from every sheet of an XLSX workbook. */
class XlsxReader(private val newParser: XmlParserFactory) {

    private data class SheetRef(val name: String, val relId: String)
    private data class Workbook(val sheets: List<SheetRef>, val date1904: Boolean)

    fun read(file: File): List<Sheet> {
        val parts = OoxmlZip.read(file) { (it.startsWith("xl/") && it.endsWith(".xml")) || it.endsWith(".rels") }
        val workbookXml = parts["xl/workbook.xml"] ?: throw IOException("Not an XLSX file: xl/workbook.xml is missing")
        val workbook = parseWorkbook(workbookXml)
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::parseRelationships).orEmpty()
        val shared = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val dateStyles = parts["xl/styles.xml"]?.let(::parseDateStyles).orEmpty()
        return workbook.sheets.mapNotNull { ref ->
            val target = rels[ref.relId] ?: return@mapNotNull null
            val bytes = parts[resolvePart(target)] ?: return@mapNotNull null
            Sheet(ref.name, parseSheet(bytes, shared, dateStyles, workbook.date1904))
        }
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        newParser().apply { setInput(ByteArrayInputStream(bytes), null) }

    private fun resolvePart(target: String): String =
        if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

    private fun parseWorkbook(bytes: ByteArray): Workbook {
        val p = parser(bytes)
        val sheets = mutableListOf<SheetRef>()
        var date1904 = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val id = p.relationshipId()
                    if (id != null) sheets += SheetRef(p.attr("name") ?: "Sheet${sheets.size + 1}", id)
                }
            }
        }
        return Workbook(sheets, date1904)
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val p = parser(bytes)
        val map = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id")
                val target = p.attr("Target")
                if (id != null && target != null) map[id] = target
            }
        }
        return map
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        var phoneticDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> current.setLength(0)
                    "rPh" -> phoneticDepth++
                    "t" -> inText = phoneticDepth == 0
                }
                XmlPullParser.TEXT -> if (inText) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> strings += current.toString()
                    "rPh" -> phoneticDepth--
                    "t" -> inText = false
                }
            }
        }
        return strings
    }

    /** Indexes into cellXfs whose number format is a date. */
    private fun parseDateStyles(bytes: ByteArray): Set<Int> {
        val p = parser(bytes)
        val customFormats = HashMap<Int, String>()
        val xfFormats = mutableListOf<Int>()
        var inCellXfs = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "numFmt" -> {
                        val id = p.attr("numFmtId")?.toIntOrNull()
                        val code = p.attr("formatCode")
                        if (id != null && code != null) customFormats[id] = code
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) xfFormats += p.attr("numFmtId")?.toIntOrNull() ?: 0
                }
                XmlPullParser.END_TAG -> if (p.name == "cellXfs") inCellXfs = false
            }
        }
        return xfFormats.indices.filter { isDateFormat(xfFormats[it], customFormats) }.toSet()
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>, date1904: Boolean): List<List<String>> {
        val p = parser(bytes)
        val cells = TreeMap<Int, TreeMap<Int, String>>()
        var rowIndex = 0
        var nextRow = 0
        var nextCol = 0
        var col = 0
        var type: String? = null
        var style = 0
        var value: StringBuilder? = null
        val inline = StringBuilder()
        var inValue = false
        var inInlineText = false
        var phoneticDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        rowIndex = p.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
                        nextRow = rowIndex + 1
                        nextCol = 0
                    }
                    "c" -> {
                        col = p.attr("r")?.let(::columnIndex) ?: nextCol
                        nextCol = col + 1
                        type = p.attr("t")
                        style = p.attr("s")?.toIntOrNull() ?: 0
                        value = null
                        inline.setLength(0)
                    }
                    "v" -> {
                        inValue = true
                        value = StringBuilder()
                    }
                    "rPh" -> phoneticDepth++
                    "t" -> inInlineText = phoneticDepth == 0
                }
                XmlPullParser.TEXT -> {
                    if (inValue) value?.append(p.text)
                    if (inInlineText) inline.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "rPh" -> phoneticDepth--
                    "c" -> {
                        val text = cellText(type, value?.toString(), inline.toString(), style, shared, dateStyles, date1904)
                        if (text.isNotEmpty()) cells.getOrPut(rowIndex) { TreeMap() }[col] = text
                    }
                }
            }
        }
        if (cells.isEmpty()) return emptyList()
        return (0..cells.lastKey()).map { r ->
            val row = cells[r] ?: return@map emptyList()
            (0..row.lastKey()).map { c -> row[c].orEmpty() }
        }
    }

    private fun cellText(
        type: String?,
        v: String?,
        inline: String,
        style: Int,
        shared: List<String>,
        dateStyles: Set<Int>,
        date1904: Boolean,
    ): String = when (type) {
        "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
        "inlineStr" -> inline
        "b" -> when (v?.trim()) {
            "1" -> "TRUE"
            "0" -> "FALSE"
            else -> ""
        }
        "str", "e", "d" -> v.orEmpty()
        else -> {
            val raw = v?.trim().orEmpty()
            val number = raw.toDoubleOrNull()
            if (number != null && style in dateStyles) serialToIso(number, date1904) else raw
        }
    }

    internal companion object {
        private val BUILT_IN_DATE_FORMATS: Set<Int> = (14..22).toSet() + (45..47)
        private val QUOTED = Regex("\"[^\"]*\"")
        private val BRACKETED = Regex("\\[[^\\]]*\\]")
        private val ESCAPED = Regex("\\\\.")
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        fun isDateFormat(id: Int, custom: Map<Int, String>): Boolean {
            if (id in BUILT_IN_DATE_FORMATS) return true
            val code = custom[id] ?: return false
            val cleaned = code.replace(QUOTED, "").replace(BRACKETED, "").replace(ESCAPED, "").lowercase()
            return cleaned.any { it == 'y' || it == 'd' || it == 'h' }
        }

        fun serialToIso(serial: Double, date1904: Boolean): String {
            val epoch = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
            val totalSeconds = Math.round(serial * 86_400)
            val dateTime = epoch.plusSeconds(totalSeconds)
            return if (totalSeconds % 86_400 == 0L) dateTime.toLocalDate().toString() else dateTime.format(DATE_TIME)
        }

        fun columnIndex(ref: String): Int? {
            var index = 0
            var letters = 0
            for (ch in ref) {
                val upper = ch.uppercaseChar()
                if (upper !in 'A'..'Z') break
                index = index * 26 + (upper - 'A' + 1)
                letters++
            }
            return if (letters == 0) null else index - 1
        }
    }
}
