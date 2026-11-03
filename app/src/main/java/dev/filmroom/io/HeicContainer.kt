package dev.filmroom.io

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Conservative, bounded admission for the platform's opaque static 8-bit HEVC image route.
 * This is NOT a pixel decoder or a claim that an admitted file is valid. Android must still
 * decode the complete primary image and verify its dimensions before publishing a source.
 * Unknown essential properties, auxiliary images, mirroring, cropping and sequences are
 * rejected rather than silently discarded. Original bytes are never modified.
 */
internal object HeicContainer {
    const val MAX_METADATA_BYTES = 4L * 1024 * 1024
    const val MAX_BOXES = 4096
    const val MAX_ITEMS = 1024
    const val MAX_PROPERTIES = 1024

    enum class Failure { INVALID, UNSUPPORTED, LIMIT }
    class Rejected(val failure: Failure, message: String, val uiMessage:dev.filmroom.i18n.UiMessage?=null) : IOException(message) {
        constructor(failure:Failure,message:dev.filmroom.i18n.UiMessage):this(failure,
            message.render() ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ae173945fb80),message)
    }
    data class Nclx(val primaries:Int, val transfer:Int, val matrix:Int, val fullRange:Boolean)
    data class Info(
        val encodedWidth: Int,
        val encodedHeight: Int,
        val rotationDegreesClockwise: Int,
        /** Largest described coded image/tile; used in addition to primary dimensions. */
        val maxCodedPixels: Long,
        /** All described HEVC image pixels, including padded grid tiles and thumbnails. */
        val estimatedDecodePixels: Long,
        /** Retain admitted declarations; never infer them from the requested output color space. */
        val declaredNclx: List<Nclx> = emptyList(),
        val sourceColor: HeicIccProfile.ColorIdentity = HeicIccProfile.ColorIdentity.SRGB,
        val sourceProfile: HeicIccProfile.Profile = HeicIccProfile.Profile.standard(sourceColor),
    ) {
        init { require(sourceColor==sourceProfile.identity) }
        val width: Int get() = encodedWidth
        val height: Int get() = encodedHeight
        val displayWidth: Int get() = if (rotationDegreesClockwise % 180 == 0) encodedWidth else encodedHeight
        val displayHeight: Int get() = if (rotationDegreesClockwise % 180 == 0) encodedHeight else encodedWidth
    }

    fun inspect(file: File, checkCancelled: () -> Unit = {}): Info =
        RandomAccessFile(file, "r").use { Reader(it, checkCancelled).inspect() }

    private fun invalid(): Nothing = throw Rejected(Failure.INVALID, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_f515de6af876))
    private fun unsupported(): Nothing = throw Rejected(Failure.UNSUPPORTED,
        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_0f079bb732f5))
    private fun limit(): Nothing = throw Rejected(Failure.LIMIT, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1351a2d16cbe))
    private data class Box(val type: String, val body: Long, val end: Long)
    private data class Association(val property: Int, val essential: Boolean)
    private data class Property(val type: String, val width: Int = 0, val height: Int = 0, val angle: Int = 0, val nclx:Nclx? = null, val icc:HeicIccProfile.Profile? = null)
    private data class Item(val type: String, val hidden: Boolean)

    private class Reader(val input: RandomAccessFile, val check: () -> Unit) {
        var boxes = 0
        val length = input.length()
        private fun need(end: Long, bytes: Int) {
            check()
            val at = input.filePointer
            if (bytes < 0 || at < 0 || at > end || bytes.toLong() > end - at) invalid()
        }
        private fun u8(end: Long): Int { need(end, 1); return input.readUnsignedByte() }
        private fun u16(end: Long): Int { need(end, 2); return input.readUnsignedShort() }
        private fun u32(end: Long): Long { need(end, 4); return input.readInt().toLong() and 0xffffffffL }
        private fun fourcc(end: Long): String = buildString { repeat(4) { append(u8(end).toChar()) } }
        private fun seek(at: Long) { if (at < 0 || at > length) invalid(); input.seek(at) }
        private fun children(start: Long, end: Long): List<Box> {
            if (start < 0 || end < start || end > length) invalid()
            val result = ArrayList<Box>()
            var at = start
            while (at < end) {
                check()
                if (++boxes > MAX_BOXES) limit()
                seek(at)
                val shortSize = u32(end)
                val type = fourcc(end)
                val size = when (shortSize) {
                    0L -> end - at
                    1L -> { val hi = u32(end); val lo = u32(end); if (hi != 0L) limit(); lo }
                    else -> shortSize
                }
                val body = input.filePointer
                if (size < body - at || size > end - at) invalid()
                result.add(Box(type, body, at + size))
                at += size
            }
            return result
        }
        private fun full(box: Box): Pair<Int, Int> {
            seek(box.body)
            val version = u8(box.end)
            val flags = (u8(box.end) shl 16) or (u8(box.end) shl 8) or u8(box.end)
            return version to flags
        }
        private fun single(list: List<Box>, name: String): Box {
            val matches = list.filter { it.type == name }
            if (matches.size != 1) invalid()
            return matches.single()
        }
        private fun id(end: Long, wide: Boolean): Long = if (wide) u32(end) else u16(end).toLong()
        private fun atEnd(end: Long) { if (input.filePointer != end) invalid() }

        fun inspect(): Info {
            if (length <= 0) invalid()
            if (length > PhotoLimits.MAX_COMPRESSED_BYTES) limit()
            val top = children(0, length)
            if (top.any { it.type == "moov" || it.type == "moof" }) unsupported()
            brands(single(top, "ftyp"))
            val meta = single(top, "meta")
            if (meta.end - meta.body > MAX_METADATA_BYTES) limit()
            val (version, flags) = full(meta)
            if (version != 0 || flags != 0) unsupported()
            val parts = children(input.filePointer, meta.end)
            val primaryBox = single(parts, "pitm")
            val (pv, pf) = full(primaryBox)
            if (pv !in 0..1 || pf != 0) unsupported()
            val primary = id(primaryBox.end, pv == 1)
            atEnd(primaryBox.end)
            val items = items(single(parts, "iinf"))
            val item = items[primary] ?: invalid()
            if (item.type !in setOf("hvc1", "grid") || item.hidden) unsupported()
            val iprp = single(parts, "iprp")
            val propertyParts = children(iprp.body, iprp.end)
            val ipco = single(propertyParts, "ipco")
            val propertyBoxes = children(ipco.body, ipco.end)
            if (propertyBoxes.size > MAX_PROPERTIES) limit()
            val properties = propertyBoxes.map { property(it) }
            val links = associations(single(propertyParts, "ipma"), properties.size)
            if (links.keys.any { it !in items }) invalid()
            val refs = references(parts.filter { it.type == "iref" })
            val primaryProps = linked(primary, links, properties)
            val shape = primaryProps.filter { it.type == "ispe" }.singleOrNull() ?: invalid()
            val rotations = primaryProps.filter { it.type == "irot" }
            if (rotations.size > 1) invalid()
            val clockwise = (360 - (rotations.singleOrNull()?.angle ?: 0)) % 360
            val codedIds = if (item.type == "hvc1") listOf(primary) else {
                val tiles = refs[primary] ?: invalid()
                if (tiles.isEmpty() || tiles.distinct().size != tiles.size) invalid()
                tiles
            }
            // The platform may eagerly initialize other images; validate all described HEVC items,
            // not only the primary, before allowing its decoder to allocate any pixel buffers.
            var maxPixels = shape.width.toLong() * shape.height
            var codedPixels = 0L
            items.forEach { (itemId, value) ->
                if (value.type == "hvc1") {
                    val props = linked(itemId, links, properties)
                    val size = props.filter { it.type == "ispe" }.singleOrNull() ?: invalid()
                    if (props.count { it.type == "hvcC" } != 1) invalid()
                    val pixels = size.width.toLong() * size.height
                    maxPixels = maxOf(maxPixels, pixels)
                    codedPixels += pixels
                } else if (value.type !in setOf("grid", "Exif", "mime")) unsupported()
            }
            codedIds.forEach { tile ->
                if (items[tile]?.type != "hvc1") unsupported()
                if (tile != primary && linked(tile, links, properties).any { it.type == "irot" && it.angle != 0 }) unsupported()
            }
            if (item.type == "hvc1" && refs.containsKey(primary)) unsupported()
            // An explicit validated color contract is mandatory. A grid may inherit a common
            // profile from all coded tiles; this is how ordinary ICC HEIC files are authored.
            fun declaredColor(props:List<Property>):HeicIccProfile.Profile? {
                val colors=props.mapNotNull { it.icc ?: if(it.nclx!=null)HeicIccProfile.Profile.standard(HeicIccProfile.ColorIdentity.SRGB) else null }
                val selected=props.mapNotNull{it.icc}.firstOrNull() ?: colors.firstOrNull() ?: return null
                if(colors.any{!HeicIccProfile.equivalent(it,selected)})unsupported()
                return selected
            }
            val primaryColor=declaredColor(primaryProps)
            val tileColors=if(item.type=="grid")codedIds.map { declaredColor(linked(it,links,properties)) } else emptyList()
            val sourceColor=primaryColor ?: if(item.type=="grid" && tileColors.all{it!=null}) {
                tileColors.filterNotNull().firstOrNull() ?: unsupported()
            }else unsupported()
            if(tileColors.filterNotNull().any{!HeicIccProfile.equivalent(it,sourceColor)})unsupported()
            // Retain an actual, associated ICC descriptor even when the primary has equivalent
            // exact NCLX and the grid tiles carry the quantized standard ICC declaration.
            val actualIcc=primaryProps.firstNotNullOfOrNull{it.icc} ?: codedIds.asSequence()
                .mapNotNull{linked(it,links,properties).firstNotNullOfOrNull{property->property.icc}}.firstOrNull()
            val sourceProfile=actualIcc ?: sourceColor
            if(!HeicIccProfile.equivalent(sourceProfile,sourceColor))unsupported()
            return Info(shape.width, shape.height, clockwise, maxPixels, maxOf(maxPixels, codedPixels), properties.mapNotNull { it.nclx },sourceProfile.identity,sourceProfile)
        }

        private fun brands(box: Box) {
            seek(box.body)
            if (box.end - box.body < 8 || (box.end - box.body) % 4 != 0L || box.end - box.body > 4096) invalid()
            val brands = mutableSetOf(fourcc(box.end))
            u32(box.end) // minor version, not a brand
            while (input.filePointer < box.end) brands.add(fourcc(box.end))
            if (brands.any { it in setOf("avif", "avis", "msf1", "hevc", "hevx", "hevm", "hevs") }) unsupported()
            // A generic mif1 container alone could carry a different codec.
            if (brands.none { it == "heic" || it == "heix" }) unsupported()
        }
        private fun items(box: Box): Map<Long, Item> {
            val (v, flags) = full(box)
            if (v !in 0..1 || flags != 0) unsupported()
            val count = if (v == 0) u16(box.end).toLong() else u32(box.end)
            if (count !in 1..MAX_ITEMS.toLong()) limit()
            val entries = children(input.filePointer, box.end)
            if (entries.size.toLong() != count || entries.any { it.type != "infe" }) invalid()
            val result = LinkedHashMap<Long, Item>()
            entries.forEach { entry ->
                val (ev, ef) = full(entry)
                if (ev !in 2..3 || ef and 0xfffffe != 0) unsupported()
                val itemId = id(entry.end, ev == 3)
                if (itemId == 0L || result.containsKey(itemId)) invalid()
                if (u16(entry.end) != 0) unsupported() // protected item
                val type = fourcc(entry.end)
                // Bounded null-terminated item name. MIME/EXIF payloads are not interpreted here.
                var terminated = false
                while (input.filePointer < entry.end) { if (u8(entry.end) == 0) { terminated = true; break } }
                if (!terminated) invalid()
                result[itemId] = Item(type, ef and 1 != 0)
            }
            return result
        }
        private fun property(box: Box): Property {
            seek(box.body)
            return when (box.type) {
                "ispe" -> {
                    val (v, f) = full(box)
                    if (v != 0 || f != 0) unsupported()
                    val width = u32(box.end); val height = u32(box.end)
                    atEnd(box.end)
                    if (width > Int.MAX_VALUE || height > Int.MAX_VALUE || !PhotoLimits.validSource(width.toInt(), height.toInt())) limit()
                    Property(box.type, width.toInt(), height.toInt())
                }
                "irot" -> { val angle = u8(box.end); atEnd(box.end); if (angle and 0xfc != 0) invalid(); Property(box.type, angle = angle * 90) }
                "imir", "clap", "auxC", "mdcv", "clli" -> unsupported()
                "hvcC" -> {
                    if (box.end - box.body < 23) invalid()
                    if (u8(box.end) != 1) unsupported()
                    val profile = u8(box.end) and 31
                    // H.265 Annex A: Main=1, Main 10=2, Main Still Picture=3.
                    // Profile admission never relaxes the SDR8 4:2:0 checks below.
                    if (profile !in 1..3) unsupported()
                    seek(box.body + 16)
                    val chroma = u8(box.end) and 3
                    val lumaDepth = u8(box.end) and 7
                    val chromaDepth = u8(box.end) and 7
                    if (chroma != 1 || lumaDepth != 0 || chromaDepth != 0) unsupported()
                    Property(box.type)
                }
                "pixi" -> {
                    val (v, f) = full(box)
                    if (v != 0 || f != 0) unsupported()
                    val channels = u8(box.end)
                    if (channels !in 1..3) unsupported()
                    repeat(channels) { if (u8(box.end) != 8) unsupported() }
                    atEnd(box.end); Property(box.type)
                }
                "colr" -> {
                    val kind = fourcc(box.end)
                    when (kind) {
                        "nclx" -> {
                            val primaries = u16(box.end)
                            val transfer = u16(box.end)
                            val matrix = u16(box.end)
                            val range = u8(box.end); atEnd(box.end)
                            if (range and 0x7f != 0) invalid()
                            // Some Android HEIF backends ignore NCLX and report fallback sRGB.
                            // Admit only explicit sRGB primaries/transfer with BT.709 YUV matrix;
                            // never relabel Display P3/BT.2020/BT.709 transfer as sRGB.
                            if (primaries != 1 || transfer != 13 || matrix != 1) unsupported()
                            Property(box.type,nclx=Nclx(primaries,transfer,matrix,range and 0x80 != 0))
                        }
                        "prof", "rICC" -> {
                            val length=box.end-input.filePointer
                            if(length>HeicIccProfile.MAX_PROFILE_BYTES)limit()
                            if(length<132)invalid()
                            val profile=ByteArray(length.toInt());input.readFully(profile)
                            val identity=try{HeicIccProfile.inspectProfile(profile)}catch(e:HeicIccProfile.Rejected){
                                when(e.failure){HeicIccProfile.Failure.INVALID->invalid();HeicIccProfile.Failure.LIMIT->limit();else->unsupported()}
                            }
                            Property(box.type,icc=identity)
                        }
                        else -> unsupported()
                    }
                }
                else -> Property(box.type)
            }
        }
        private fun associations(box: Box, properties: Int): Map<Long, List<Association>> {
            val (v, flags) = full(box)
            if (v !in 0..1 || flags and 0xfffffe != 0) unsupported()
            val count = u32(box.end)
            if (count > MAX_ITEMS) limit()
            val result = LinkedHashMap<Long, List<Association>>()
            repeat(count.toInt()) {
                val itemId = id(box.end, v == 1)
                if (itemId == 0L || result.containsKey(itemId)) invalid()
                val number = u8(box.end)
                val wide = flags and 1 != 0
                val associations = ArrayList<Association>(number)
                repeat(number) {
                    val raw = if (wide) u16(box.end) else u8(box.end)
                    val index = raw and if (wide) 0x7fff else 0x7f
                    val essential = (raw and (if (wide) 0x8000 else 0x80)) != 0
                    if (index > properties) invalid()
                    if (index != 0) associations.add(Association(index, essential))
                }
                if (associations.map { it.property }.distinct().size != associations.size) invalid()
                result[itemId] = associations
            }
            atEnd(box.end)
            return result
        }
        private fun linked(item: Long, links: Map<Long, List<Association>>, properties: List<Property>): List<Property> =
            links[item].orEmpty().map { association ->
                val property = properties[association.property - 1]
                if (association.essential && property.type !in setOf("ispe", "hvcC", "pixi", "colr", "irot", "pasp")) unsupported()
                if (property.type == "pasp") unsupported() // non-square pixel presentation unimplemented
                property
            }
        private fun references(boxes: List<Box>): Map<Long, List<Long>> {
            if (boxes.size > 1) invalid()
            val box = boxes.singleOrNull() ?: return emptyMap()
            val (v, flags) = full(box)
            if (v !in 0..1 || flags != 0) unsupported()
            val children = children(input.filePointer, box.end)
            val result = LinkedHashMap<Long, List<Long>>()
            children.forEach { child ->
                if (child.type == "auxl") unsupported()
                seek(child.body)
                val from = id(child.end, v == 1)
                val count = u16(child.end)
                if (count > MAX_ITEMS) limit()
                val to = List(count) { id(child.end, v == 1) }
                atEnd(child.end)
                if (child.type == "dimg") {
                    if (result.put(from, to) != null) invalid()
                } else if (child.type !in setOf("thmb", "cdsc")) unsupported()
            }
            return result
        }
    }
}
