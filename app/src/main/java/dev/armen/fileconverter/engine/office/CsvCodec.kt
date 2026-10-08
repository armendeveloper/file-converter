package dev.armen.fileconverter.engine.office

/** RFC 4180 CSV with delimiter detection. Lenient: a quote inside an unquoted field is kept literally. */
object CsvCodec {
    // Order matters: on a tie the earlier candidate wins (";" CSVs often use "," as decimal mark).
    private val CANDIDATES = listOf(';', '\t', ',')

    fun detectDelimiter(text: String): Char {
        val record = firstRecord(text.removePrefix("\uFEFF"))
        val best = CANDIDATES.maxBy { countOutsideQuotes(record, it) }
        return if (countOutsideQuotes(record, best) == 0) ',' else best
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(ch)
                    delimiter -> endField()
                    '\r' -> {
                        if (i + 1 < s.length && s[i + 1] == '\n') i++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun write(rows: List<List<String>>, delimiter: Char = ','): String = buildString {
        rows.forEach { row ->
            row.joinTo(this, delimiter.toString()) { quote(it, delimiter) }
            append("\r\n")
        }
    }

    private fun quote(value: String, delimiter: Char): String {
        val needsQuotes = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun firstRecord(text: String): String {
        var inQuotes = false
        for ((index, ch) in text.withIndex()) {
            if (ch == '"') inQuotes = !inQuotes
            else if ((ch == '\n' || ch == '\r') && !inQuotes) return text.substring(0, index)
        }
        return text
    }

    private fun countOutsideQuotes(record: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in record) {
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == delimiter && !inQuotes) count++
        }
        return count
    }
}
