package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Extracts plain text from word/document.xml: paragraphs become lines, w:tab becomes a tab,
 * table rows become lines of tab-separated cells, and mc:Fallback copies are skipped.
 */
class DocxReader(private val newParser: XmlParserFactory) {

    fun read(file: File): String {
        val xml = OoxmlZip.read(file) { it == "word/document.xml" }["word/document.xml"]
            ?: throw IOException("Not a DOCX file: word/document.xml is missing")
        return extractText(xml)
    }

    private fun extractText(xml: ByteArray): String {
        val p = newParser().apply { setInput(ByteArrayInputStream(xml), null) }
        val out = StringBuilder()
        var inText = false
        var fallbackDepth = 0
        var tabsDepth = 0
        var cellDepth = 0
        // One entry per open paragraph: did a nested paragraph (e.g. in a text box) end inside it?
        val paragraphs = ArrayDeque<Boolean>()

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when {
                    p.name == "Fallback" -> fallbackDepth++
                    fallbackDepth > 0 || !isWordNamespace(p.namespace) -> Unit
                    p.name == "p" -> paragraphs.addLast(false)
                    p.name == "t" -> inText = true
                    p.name == "tabs" -> tabsDepth++
                    p.name == "tab" && tabsDepth == 0 -> out.append('\t')
                    p.name == "br" || p.name == "cr" -> out.append('\n')
                    p.name == "tc" -> cellDepth++
                }
                XmlPullParser.TEXT -> if (inText && fallbackDepth == 0) out.append(p.text)
                XmlPullParser.END_TAG -> when {
                    p.name == "Fallback" -> fallbackDepth--
                    fallbackDepth > 0 || !isWordNamespace(p.namespace) -> Unit
                    p.name == "t" -> inText = false
                    p.name == "tabs" -> tabsDepth--
                    p.name == "p" -> {
                        val hadNested = paragraphs.removeLastOrNull() ?: false
                        if (paragraphs.isNotEmpty()) paragraphs[paragraphs.lastIndex] = true
                        when {
                            cellDepth > 0 -> out.append(' ')
                            hadNested && out.endsWith('\n') -> Unit
                            else -> out.append('\n')
                        }
                    }
                    p.name == "tc" -> {
                        while (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
                        out.append('\t')
                        cellDepth--
                    }
                    p.name == "tr" -> {
                        if (out.endsWith('\t')) out.setLength(out.length - 1)
                        out.append('\n')
                    }
                }
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun isWordNamespace(ns: String?): Boolean =
        ns != null && (ns.endsWith("wordprocessingml/2006/main") || ns.endsWith("wordprocessingml/main"))
}
