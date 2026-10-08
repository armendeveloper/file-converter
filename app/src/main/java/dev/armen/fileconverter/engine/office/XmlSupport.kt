package dev.armen.fileconverter.engine.office

import org.xmlpull.v1.XmlPullParser

/** Creates a namespace-aware pull parser. Android uses [AndroidXml.factory]; JVM tests use kxml2. */
typealias XmlParserFactory = () -> XmlPullParser

/** Attribute by local name, ignoring its namespace. */
internal fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == localName) return getAttributeValue(i)
    return null
}

/** The `r:id` attribute, for both transitional and strict OOXML relationship namespaces. */
internal fun XmlPullParser.relationshipId(): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == "id" && getAttributeNamespace(i).orEmpty().endsWith("/relationships")) {
            return getAttributeValue(i)
        }
    }
    return null
}

/** Escapes text for element content and attributes, dropping characters XML 1.0 forbids. */
internal fun escapeXml(text: String): String = buildString(text.length) {
    for (ch in text) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
            ch < ' ' || ch == '￾' || ch == '￿' -> Unit
            else -> append(ch)
        }
    }
}
