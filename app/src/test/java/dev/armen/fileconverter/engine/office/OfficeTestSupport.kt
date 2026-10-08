package dev.armen.fileconverter.engine.office

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** kxml2 is the same parser Android ships; created directly so the android.jar stubs are never touched. */
val kxmlFactory: XmlParserFactory = {
    KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
}

fun writeZip(file: File, entries: Map<String, String>) {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}

fun readZipEntry(file: File, name: String): String =
    ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8) }

private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

/** A workbook shaped like Excel's output: shared strings, rich text, phonetic runs, dates, prefixes. */
fun writeExcelFixture(file: File) = writeZip(
    file,
    mapOf(
        "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
        "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr/><sheets><sheet name="People" sheetId="1" r:id="rId1"/><sheet name="Q&amp;A" sheetId="2" r:id="rId2"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG_REL"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL/worksheet" Target="/xl/worksheets/sheet2.xml"/><Relationship Id="rId3" Type="$REL/styles" Target="styles.xml"/></Relationships>""",
        "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$MAIN" count="3" uniqueCount="3"><si><t>Name</t></si><si><t>Joined</t></si><si><r><t xml:space="preserve">Ann </t></r><r><rPr><b/></rPr><t>Lee</t></r><rPh sb="0" eb="1"><t>アン</t></rPh></si></sst>""",
        "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$MAIN"><numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/></numFmts><cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="2"/></cellXfs></styleSheet>""",
        "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$MAIN"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>Score</t></is></c></row><row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" s="1"><v>45306</v></c><c r="C2" s="3"><v>98.5</v></c></row><row r="3"><c r="A3" t="b"><v>1</v></c><c r="C3" t="str"><f>"4"&amp;"2"</f><v>42</v></c></row><row r="5"><c r="A5" t="e"><v>#DIV/0!</v></c><c r="D5" s="2"><v>45306.4375</v></c></row></sheetData></worksheet>""",
        "xl/worksheets/sheet2.xml" to """<x:worksheet xmlns:x="$MAIN"><x:sheetData><x:row><x:c t="inlineStr"><x:is><x:t>x</x:t></x:is></x:c><x:c><x:v>7</x:v></x:c></x:row></x:sheetData></x:worksheet>""",
    ),
)

fun writeMinimalWorkbook(file: File, sheetData: String, numFmtIds: List<Int> = listOf(0), date1904: Boolean = false) = writeZip(
    file,
    mapOf(
        "xl/workbook.xml" to """<workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr${if (date1904) " date1904=\"1\"" else ""}/><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$PKG_REL"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/styles.xml" to """<styleSheet xmlns="$MAIN"><cellXfs>${numFmtIds.joinToString("") { "<xf numFmtId=\"$it\"/>" }}</cellXfs></styleSheet>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$MAIN"><sheetData>$sheetData</sheetData></worksheet>""",
    ),
)
