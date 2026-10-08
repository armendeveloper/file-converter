package dev.armen.fileconverter.engine.office

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

object AndroidXml {
    val factory: XmlParserFactory = {
        Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
    }
}
