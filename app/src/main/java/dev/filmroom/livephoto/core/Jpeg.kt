package dev.filmroom.livephoto.core

/** Marker-aware JPEG scanner. It does not confuse thumbnails or stuffed scan bytes with the primary EOI. */
internal object Jpeg {
    data class Segment(val marker: Int, val start: Int, val payload: Int, val end: Int)
    data class Layout(val segments: List<Segment>, val end: Int, val width: Int, val height: Int)
    private val XMP = ascii("http://ns.adobe.com/xap/1.0/\u0000")
    private val EXT_XMP = ascii("http://ns.adobe.com/xmp/extension/\u0000")
    fun inspect(b: ByteArray): Layout {
        demand(b.size >= 4 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte(), ErrorCode.INVALID_JPEG, "Missing JPEG SOI")
        var p = 2; var width = 0; var height = 0; var scans = 0; val segments = mutableListOf<Segment>()
        while (p < b.size) {
            val start = p
            demand(b[p] == 0xff.toByte(), ErrorCode.INVALID_JPEG, "Expected JPEG marker at $p")
            while (p < b.size && b[p] == 0xff.toByte()) p++
            demand(p < b.size, ErrorCode.INVALID_JPEG, "Truncated JPEG marker")
            val marker = b[p++].toInt() and 255
            demand(marker != 0 && marker != 0xd8, ErrorCode.INVALID_JPEG, "Unexpected JPEG marker")
            if (marker == 0xd9) {
                demand(scans > 0 && width > 0 && height > 0, ErrorCode.INVALID_JPEG, "Missing JPEG frame or scan")
                return Layout(segments, p, width, height)
            }
            if (marker == 1 || marker in 0xd0..0xd7) continue
            val n = u16(b, p)
            demand(n >= 2 && n <= b.size - p, ErrorCode.INVALID_JPEG, "JPEG segment overruns input")
            val end = p + n
            segments += Segment(marker, start, p + 2, end)
            demand(segments.size <= 4096, ErrorCode.RESOURCE_LIMIT, "Too many JPEG segments")
            if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)) {
                demand(n >= 8 && (b[p + 2].toInt() and 255) == 8, ErrorCode.UNSUPPORTED_INPUT, "Only 8-bit JPEG supported")
                height = u16(b, p + 3); width = u16(b, p + 5)
                demand(width.toLong() * height <= 64_000_000, ErrorCode.RESOURCE_LIMIT, "JPEG dimensions exceed limit")
            }
            p = end
            if (marker == 0xda) {
                scans++
                while (p < b.size) {
                    if (b[p] != 0xff.toByte()) { p++; continue }
                    val markerStart = p
                    while (p < b.size && b[p] == 0xff.toByte()) p++
                    demand(p < b.size, ErrorCode.INVALID_JPEG, "Truncated JPEG entropy stream")
                    val c = b[p].toInt() and 255
                    if (c == 0 || c in 0xd0..0xd7) { p++; continue }
                    p = markerStart; break
                }
            }
        }
        throw LivePhotoException(ErrorCode.INVALID_JPEG, "Missing primary JPEG EOI")
    }
    /** The caller supplies a fresh SDR cover. EXIF/XMP/MPF are deliberately rebuilt, never merged. */
    fun clean(b: ByteArray): ByteArray {
        val layout = inspect(b)
        demand(layout.end == b.size, ErrorCode.UNSUPPORTED_INPUT, "Cover must be a single SDR JPEG without appended data/gain maps")
        val drop = layout.segments.filter {
            (it.marker == 0xe1 && (b.startsAt(ascii("Exif\u0000\u0000"), it.payload) || b.startsAt(XMP, it.payload) || b.startsAt(EXT_XMP, it.payload))) ||
                (it.marker == 0xe2 && b.startsAt(ascii("MPF\u0000"), it.payload))
        }
        val out = java.io.ByteArrayOutputStream(); var p = 0
        for (s in drop) { out.write(b, p, s.start - p); p = s.end }
        out.write(b, p, b.size - p); return out.toByteArray()
    }
    fun app(marker: Int, payload: ByteArray): ByteArray {
        demand(payload.size <= SdkLimits.MAX_METADATA_BYTES, ErrorCode.RESOURCE_LIMIT, "APP segment too large")
        return bytes { writeByte(0xff); writeByte(marker); writeShort(payload.size + 2); write(payload) }
    }
    fun xmp(xml: String): ByteArray = app(0xe1, XMP + xml.toByteArray(Charsets.UTF_8))
    fun insert(clean: ByteArray, metadata: ByteArray): ByteArray {
        val layout = inspect(clean)
        // Keep JFIF / leading APP0 before EXIF, XMP and MPF.
        val p = layout.segments.takeWhile { it.marker == 0xe0 }.lastOrNull()?.end ?: 2
        return clean.copyOfRange(0, p) + metadata + clean.copyOfRange(p, clean.size)
    }
    fun xmpPackets(b: ByteArray, layout: Layout): List<String> = layout.segments.filter { it.marker == 0xe1 && b.startsAt(XMP, it.payload) }.map {
        b.copyOfRange(it.payload + XMP.size, it.end).toString(Charsets.UTF_8)
    }
    fun mpf(imageSize: Long): ByteArray {
        demand(imageSize in 1..0xffffffffL, ErrorCode.RESOURCE_LIMIT, "MPF image size overflow")
        return app(0xe2, bytes {
            write(ascii("MPF\u0000MM")); writeShort(42); writeInt(8); writeShort(3)
            writeShort(0xb000); writeShort(7); writeInt(4); write(ascii("0100"))
            writeShort(0xb001); writeShort(4); writeInt(1); writeInt(1)
            writeShort(0xb002); writeShort(7); writeInt(16); writeInt(50)
            writeInt(0); writeInt(0x00030000); writeInt(imageSize.toInt()); writeInt(0); writeShort(0); writeShort(0)
        })
    }
    fun exif(comment: ByteArray?, dates: LivePhotoDates? = null, xiaomiLivePhoto: Boolean = false, captureExif: List<LivePhotoCaptureExif> = emptyList(), width:Int=0, height:Int=0): ByteArray {
        if (dates != null || xiaomiLivePhoto || captureExif.isNotEmpty() || width>0) return datedExif(comment, dates, xiaomiLivePhoto, captureExif,width,height)
        val tiff = bytes {
            write(ascii("II")); write(byteArrayOf(42, 0)); write(le32(8))
            write(byteArrayOf(if (comment == null) 1 else 2, 0))
            write(byteArrayOf(0x12, 0x01, 0x03, 0)); write(le32(1)); write(byteArrayOf(1, 0, 0, 0))
            if (comment != null) { write(byteArrayOf(0x69, 0x87.toByte(), 0x04, 0)); write(le32(1)); write(le32(38)) }
            write(le32(0))
            if (comment != null) {
                write(byteArrayOf(1, 0)); write(byteArrayOf(0x86.toByte(), 0x92.toByte(), 7, 0)); write(le32(comment.size.toLong())); write(le32(56)); write(le32(0)); write(comment)
            }
        }
        return app(0xe1, ascii("Exif\u0000\u0000") + tiff)
    }
    private data class Entry(val tag:Int,val type:Int,val count:Int,val value:ByteArray)
    private fun datedExif(comment:ByteArray?,dates:LivePhotoDates?,xiaomiLivePhoto:Boolean,captureExif:List<LivePhotoCaptureExif>,width:Int,height:Int):ByteArray {
        fun text(tag:Int,value:String):Entry { val b=ascii(value)+byteArrayOf(0);return Entry(tag,2,b.size,b) }
        val main=mutableListOf(Entry(0x112,3,1,byteArrayOf(1,0)))
        val exif=mutableListOf<Entry>()
        val gps=mutableListOf<Entry>()
        require(captureExif.size<=96 && captureExif.sumOf { it.data.size }<=48*1024)
        require(captureExif.map { it.group to it.tag }.distinct().size==captureExif.size)
        for(field in captureExif) {
            field.validate()
            val unit=when(field.type){3->2;4->4;5,10->8;else->1}
            val entry=Entry(field.tag,field.type,field.data.size/unit,field.data.copyOf())
            when(field.group){0->main+=entry;1->exif+=entry;2->gps+=entry}
        }
        if(width>0 || height>0) {
            require(width>0 && height>0)
            main+=Entry(0x100,4,1,le32(width.toLong())); main+=Entry(0x101,4,1,le32(height.toLong()))
            exif+=Entry(0xa002,4,1,le32(width.toLong())); exif+=Entry(0xa003,4,1,le32(height.toLong()))
            exif+=Entry(0xa001,3,1,byteArrayOf(1,0)) // generated SDR cover is sRGB
        }
        dates?.let { d ->
            main+=text(0x132,d.modified)
            exif+=text(0x9010,d.modifiedOffset)
            d.modifiedSubsec?.let{exif+=text(0x9290,it)}
            d.original?.let{exif+=text(0x9003,it)}
            d.originalOffset?.let{exif+=text(0x9011,it)}
            d.originalSubsec?.let{exif+=text(0x9291,it)}
        }
        // Xiaomi SDK livePhoto flag: ExifIFD tag 0x9A01, BYTE, one value.
        if(xiaomiLivePhoto)exif+=Entry(0x9a01,1,1,byteArrayOf(1))
        comment?.let{exif+=Entry(0x9286,7,it.size,it)}
        val exifStart=8+2+(main.size+1+(if(gps.isEmpty())0 else 1))*12+4
        main+=Entry(0x8769,4,1,le32(exifStart.toLong()))
        val gpsStart=exifStart+2+exif.size*12+4
        if(gps.isNotEmpty())main+=Entry(0x8825,4,1,le32(gpsStart.toLong()))
        var offset=gpsStart+(if(gps.isEmpty())0 else 2+gps.size*12+4)
        val payload=java.io.ByteArrayOutputStream()
        fun directory(entries:List<Entry>):ByteArray=bytes {
            val sorted=entries.sortedBy{it.tag}
            writeShort(java.lang.Short.reverseBytes(sorted.size.toShort()).toInt())
            for(e in sorted) {
                writeShort(java.lang.Short.reverseBytes(e.tag.toShort()).toInt())
                writeShort(java.lang.Short.reverseBytes(e.type.toShort()).toInt())
                write(le32(e.count.toLong()))
                if(e.value.size<=4)write(e.value.copyOf(4)) else {
                    write(le32(offset.toLong()));payload.write(e.value);offset+=e.value.size
                    if(offset%2!=0){payload.write(0);offset++}
                }
            }
            write(le32(0))
        }
        return app(0xe1,ascii("Exif\u0000\u0000")+ascii("II")+byteArrayOf(42,0)+le32(8)+directory(main)+directory(exif)+(if(gps.isEmpty())byteArrayOf() else directory(gps))+payload.toByteArray())
    }

}

