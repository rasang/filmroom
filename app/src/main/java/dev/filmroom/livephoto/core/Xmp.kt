package dev.filmroom.livephoto.core

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object Xmp {
    const val G = "http://ns.google.com/photos/1.0/camera/"
    const val C = "http://ns.google.com/photos/1.0/container/"
    const val I = "http://ns.google.com/photos/1.0/container/item/"
    const val O = "http://ns.oplus.com/photos/1.0/camera/"
    const val V = "http://ns.vivo.com/photos/1.0/camera/"
    fun build(profile: LivePhotoProfile, videoLength: Long, extent: Long, padding: Int, pts: Long): String {
        val extra = when(profile) {
            LivePhotoProfile.OPLUS -> """xmlns:OpCamera="$O" OpCamera:MotionPhotoOwner="oplus" OpCamera:OLivePhotoVersion="2" OpCamera:MotionPhotoFeatureFlag="1" OpCamera:VideoLength="$videoLength" OpCamera:MotionPhotoPrimaryPresentationTimestampUs="$pts" """
            LivePhotoProfile.VIVO_SINGLE -> """xmlns:VCamera="$V" VCamera:VMotionPhotoVersion="1" """
            // Compatibility candidate, not Xiaomi's proprietary SDK and not a fake third-party owner ID.
            LivePhotoProfile.XIAOMI_COMPAT -> """GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$extent" GCamera:MicroVideoPresentationTimestampUs="$pts" """
            else -> ""
        }
        val videoPadding = if(profile == LivePhotoProfile.OPLUS || profile == LivePhotoProfile.VIVO_SINGLE) " Item:Padding=\"0\"" else ""
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="$G" xmlns:Container="$C" xmlns:Item="$I" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$pts" $extra><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="$padding"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$extent"$videoPadding/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
    }
    data class Info(val pts: Long, val extent: Long, val padding: Int, val secondaryPadding: Boolean, val fields: Map<Pair<String,String>,String>)
    fun inspect(xml: String): Info {
        demand(xml.toByteArray(Charsets.UTF_8).size <= SdkLimits.MAX_METADATA_BYTES && !xml.contains("<!", true), ErrorCode.INVALID_METADATA, "DTD, entities and declarations are forbidden in generated XMP")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            // Android parsers differ in optional feature support; lexical rejection above is mandatory.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = try { factory.newDocumentBuilder().apply { setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() { override fun error(e: org.xml.sax.SAXParseException) { throw e }; override fun fatalError(e: org.xml.sax.SAXParseException) { throw e } }) }.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))) } catch(e: Exception) { throw LivePhotoException(ErrorCode.INVALID_METADATA, "Malformed XMP",e) }
        val fields = mutableMapOf<Pair<String,String>,String>(); val nodes = doc.getElementsByTagName("*")
        demand(nodes.length <= 128, ErrorCode.RESOURCE_LIMIT, "Too many XMP elements")
        fun put(ns: String?, local: String?, value: String) {
            if (ns !in setOf(G,O,V) || local == null) return
            val key = ns!! to local
            demand(!fields.containsKey(key), ErrorCode.INVALID_METADATA, "Duplicate XMP control property: $local")
            fields[key] = value.trim()
        }
        for(i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.namespaceURI in setOf(G,O,V)) put(e.namespaceURI,e.localName,e.textContent)
            for (j in 0 until e.attributes.length) { val a=e.attributes.item(j); put(a.namespaceURI,a.localName,a.nodeValue) }
        }
        demand(fields[G to "MotionPhoto"] == "1" && fields[G to "MotionPhotoVersion"] == "1", ErrorCode.INVALID_METADATA, "Motion Photo is disabled or has a wrong version")
        fun number(s: String?): Long = s?.toLongOrNull() ?: throw LivePhotoException(ErrorCode.INVALID_METADATA, "Missing/invalid numeric XMP property")
        val dirs = doc.getElementsByTagNameNS(C,"Directory"); val items = doc.getElementsByTagNameNS(C,"Item")
        demand(dirs.length == 1 && items.length == 2 && (dirs.item(0) as Element).getElementsByTagNameNS(C,"Item").length == 2, ErrorCode.INVALID_METADATA, "Expected one two-item directory")
        val image = items.item(0) as Element; val video=items.item(1) as Element
        demand(image.getAttributeNS(I,"Semantic") == "Primary" && image.getAttributeNS(I,"Mime") == "image/jpeg" && image.getAttributeNS(I,"Length") == "0", ErrorCode.INVALID_METADATA, "Invalid primary item")
        demand(video.getAttributeNS(I,"Semantic") == "MotionPhoto" && video.getAttributeNS(I,"Mime") == "video/mp4", ErrorCode.INVALID_METADATA, "Invalid video item")
        val padding = number(image.getAttributeNS(I,"Padding")); val extent=number(video.getAttributeNS(I,"Length")); val pts=number(fields[G to "MotionPhotoPresentationTimestampUs"])
        demand(padding in 0..65536 && extent > 0 && pts >= 0, ErrorCode.INVALID_METADATA, "Invalid XMP bounds or cover PTS")
        val secondary = video.hasAttributeNS(I,"Padding")
        if(secondary) demand(video.getAttributeNS(I,"Padding") == "0", ErrorCode.INVALID_METADATA, "Unsupported video padding")
        return Info(pts,extent,padding.toInt(),secondary,fields)
    }
}

