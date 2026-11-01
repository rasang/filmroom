package dev.filmroom.io

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/** Read one bounded, explicitly zoned source CreateDate, not the rest of the private XMP packet. */
internal object XmpCaptureTime {
    private const val XMP="http://ns.adobe.com/xap/1.0/"
    fun read(bytes:ByteArray?):String?=runCatching {
        if(bytes==null || bytes.size>128*1024)return null
        val parser=XmlPullParserFactory.newInstance().apply { isNamespaceAware=true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
        parser.setInput(ByteArrayInputStream(bytes),null)
        val dates=mutableSetOf<String>()
        var collecting=false
        val text=StringBuilder()
        while(true) {
            when(parser.nextToken()) {
                XmlPullParser.END_DOCUMENT->break
                XmlPullParser.DOCDECL,XmlPullParser.ENTITY_REF->return null
                XmlPullParser.START_TAG->{
                    if(collecting)return null
                    for(i in 0 until parser.attributeCount)if(parser.getAttributeNamespace(i)==XMP && parser.getAttributeName(i)=="CreateDate")dates.add(parser.getAttributeValue(i).trim())
                    collecting=parser.namespace==XMP && parser.name=="CreateDate"
                    if(collecting)text.setLength(0)
                }
                XmlPullParser.TEXT,XmlPullParser.CDSECT->if(collecting){text.append(parser.text);if(text.length>64)return null}
                XmlPullParser.END_TAG->if(collecting){dates.add(text.toString().trim());collecting=false}
            }
        }
        dates.singleOrNull()?.takeIf{it.length<=64}
    }.getOrNull()
}
