package dev.armen.fileconverter.engine.office

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes plain text as a DOCX: one paragraph per line, tabs kept, Calibri 11 pt, A4 page. */
class DocxWriter {

    fun write(text: String, out: File) {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("[Content_Types].xml", CONTENT_TYPES)
            zip.putText("_rels/.rels", ROOT_RELS)
            zip.putText("word/_rels/document.xml.rels", DOCUMENT_RELS)
            zip.putText("word/styles.xml", STYLES)
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val writer = zip.bufferedWriter(Charsets.UTF_8) // flushed, not closed: closing would close the zip
            writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W_NS"><w:body>""")
            lines.forEach { writer.write(paragraph(it)) }
            writer.write(SECTION)
            writer.write("</w:body></w:document>")
            writer.flush()
            zip.closeEntry()
        }
    }

    internal fun paragraph(line: String): String {
        if (line.isEmpty()) return "<w:p/>"
        val runs = line.split('\t').joinToString("<w:tab/>") { part ->
            if (part.isEmpty()) "" else """<w:t xml:space="preserve">${escapeXml(part)}</w:t>"""
        }
        return "<w:p><w:r>$runs</w:r></w:p>"
    }

    private companion object {
        const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

        const val CONTENT_TYPES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

        const val ROOT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="word/document.xml"/></Relationships>"""

        const val DOCUMENT_RELS =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="$REL_NS/styles" Target="styles.xml"/></Relationships>"""

        const val STYLES =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W_NS"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""

        const val SECTION =
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
    }
}
