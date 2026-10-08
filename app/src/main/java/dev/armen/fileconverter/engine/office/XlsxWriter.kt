package dev.armen.fileconverter.engine.office

import java.io.File
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes one sheet of text/number cells as a minimal, valid XLSX package. */
class XlsxWriter {

    fun write(rows: List<List<String>>, out: File, sheetName: String = "Sheet1") {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("[Content_Types].xml", CONTENT_TYPES)
            zip.putText("_rels/.rels", ROOT_RELS)
            zip.putText("xl/workbook.xml", workbookXml(safeSheetName(sheetName)))
            zip.putText("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.putText("xl/styles.xml", STYLES)
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val writer = zip.bufferedWriter(Charsets.UTF_8) // flushed, not closed: closing would close the zip
            writeSheet(rows, writer)
            writer.flush()
            zip.closeEntry()
        }
    }

    private fun writeSheet(rows: List<List<String>>, w: Writer) {
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$MAIN_NS"><sheetData>""")
        rows.forEachIndexed { r, row ->
            if (row.all { it.isEmpty() }) return@forEachIndexed
            w.write("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                if (value.isEmpty()) return@forEachIndexed
                val ref = columnName(c) + (r + 1)
                if (NUMBER.matches(value)) {
                    w.write("""<c r="$ref"><v>$value</v></c>""")
                } else {
                    w.write("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>""")
                }
            }
            w.write("</row>")
        }
        w.write("</sheetData></worksheet>")
    }

    private fun workbookXml(sheetName: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS"><sheets><sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    companion object {
        private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

        /** Plain decimals up to 15 significant integer digits; anything else (007, long IDs, 1e5) stays text. */
        private val NUMBER = Regex("-?(0|[1-9][0-9]{0,14})(\\.[0-9]+)?")

        private const val CONTENT_TYPES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

        private const val ROOT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        private const val WORKBOOK_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL_NS/styles" Target="styles.xml"/></Relationships>"""

        private const val STYLES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="$MAIN_NS"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append('A' + rem)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        fun safeSheetName(name: String): String =
            name.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31).ifBlank { "Sheet1" }
    }
}
