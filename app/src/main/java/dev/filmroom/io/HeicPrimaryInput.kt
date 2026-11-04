package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Select an explicitly declared SDR primary from a gain-map HEIF without asking the platform
 * to choose an alternate HDR rendition. This is a lossless container view, not HDR decoding:
 * selected HEVC bytes, item order and associated color/transform properties are copied intact.
 * The original remains the metadata/restore source. The ordinary strict SDR admission and
 * Android color/output guards still apply. No manufacturer, filename or profile-name checks.
 */
internal object HeicPrimaryInput {
    class Storage: java.io.IOException("Insufficient space for HEIF primary view")
    class Prepared internal constructor(val info: HeicContainer.Info, private val source: File?, private val materialize: (() -> File)?) : Closeable {
        private var temporary: File? = null
        private var closed = false
        val file: File get() {
            check(!closed)
            return source ?: temporary ?: checkNotNull(materialize).invoke().also { temporary = it }
        }
        override fun close() { closed = true; temporary?.delete() }
    }

    fun open(original: File, check: () -> Unit = {}): Prepared {
        try { return Prepared(HeicContainer.inspect(original, check), original, null) }
        catch (error: HeicContainer.Rejected) {
            if (error.failure != HeicContainer.Failure.UNSUPPORTED) throw error
        }
        return RandomAccessFile(original, "r").use { Reader(it, check).plan(original) }
    }

    private fun invalid(): Nothing = throw HeicContainer.Rejected(HeicContainer.Failure.INVALID,
        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_f515de6af876))
    private fun unsupported(): Nothing = throw HeicContainer.Rejected(HeicContainer.Failure.UNSUPPORTED,
        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_0f079bb732f5))
    private fun limit(): Nothing = throw HeicContainer.Rejected(HeicContainer.Failure.LIMIT,
        dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1351a2d16cbe))
    private data class Box(val type: String, val start: Long, val body: Long, val end: Long)
    private data class Item(val id: Int, val type: String, val hidden: Boolean, val box: Box)
    private data class Link(val property: Int, val essential: Boolean)
    private data class Reference(val type: String, val from: Int, val to: List<Int>)
    private data class Extent(val offset: Long, val length: Long)

    private fun data(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
    private fun box(type: String, body: ByteArray) = data { writeInt(body.size + 8); writeBytes(type); write(body) }
    private fun full(type: String, body: ByteArray, version: Int = 0, flags: Int = 0) =
        box(type, data { writeInt((version shl 24) or flags); write(body) })

    private class Reader(val input: RandomAccessFile, val check: () -> Unit) {
        val length = input.length()
        var count = 0
        private fun need(end: Long, bytes: Long) {
            check()
            if (bytes < 0 || input.filePointer > end || bytes > end - input.filePointer) invalid()
        }
        private fun u8(end: Long): Int { need(end, 1); return input.readUnsignedByte() }
        private fun u16(end: Long): Int { need(end, 2); return input.readUnsignedShort() }
        private fun u32(end: Long): Long { need(end, 4); return input.readInt().toLong() and 0xffffffffL }
        private fun cc(end: Long) = buildString { repeat(4) { append(u8(end).toChar()) } }
        private fun end(end: Long) { if (input.filePointer != end) invalid() }
        private fun header(box: Box): Pair<Int, Int> {
            input.seek(box.body)
            return u8(box.end) to ((u8(box.end) shl 16) or (u8(box.end) shl 8) or u8(box.end))
        }
        private fun boxes(start: Long, end: Long): List<Box> {
            if (start < 0 || end < start || end > length) invalid()
            val out = ArrayList<Box>(); var at = start
            while (at < end) {
                if (++count > HeicContainer.MAX_BOXES) limit()
                input.seek(at); val n = u32(end); val type = cc(end)
                val size = when(n) { 0L -> end - at; 1L -> { if (u32(end) != 0L) limit(); u32(end) }; else -> n }
                if (size < input.filePointer - at || size > end - at) invalid()
                out.add(Box(type, at, input.filePointer, at + size)); at += size
            }
            return out
        }
        private fun one(boxes: List<Box>, type: String) = boxes.filter { it.type == type }.singleOrNull() ?: invalid()
        private fun bytes(box: Box): ByteArray {
            val size = box.end - box.start
            if (size > HeicContainer.MAX_METADATA_BYTES) limit()
            return ByteArray(size.toInt()).also { input.seek(box.start); input.readFully(it); check() }
        }
        private fun smallId(end: Long, wide: Boolean): Int {
            val id = if (wide) u32(end) else u16(end).toLong()
            // Output uses 16-bit item IDs. Wider IDs remain unsupported, never truncated.
            if (id !in 1..65535) unsupported()
            return id.toInt()
        }
        private fun items(box: Box): Map<Int, Item> {
            val (v, flags) = header(box)
            if (v !in 0..1 || flags != 0) unsupported()
            val n = if (v == 0) u16(box.end).toLong() else u32(box.end)
            if (n !in 1..HeicContainer.MAX_ITEMS.toLong()) limit()
            val entries = boxes(input.filePointer, box.end)
            if (entries.size.toLong() != n) invalid()
            val out = LinkedHashMap<Int, Item>()
            for (entry in entries) {
                if (entry.type != "infe") invalid()
                val (iv, f) = header(entry)
                if (iv !in 2..3 || f and 0xfffffe != 0) unsupported()
                val id = smallId(entry.end, iv == 3)
                if (u16(entry.end) != 0) unsupported()
                val type = cc(entry.end)
                var terminated = false
                while (input.filePointer < entry.end) if (u8(entry.end) == 0) { terminated = true; break }
                if (!terminated || out.put(id, Item(id, type, f and 1 != 0, entry)) != null) invalid()
            }
            return out
        }
        private fun links(box: Box, propertyCount: Int): Map<Int, List<Link>> {
            val (v, flags) = header(box)
            if (v !in 0..1 || flags and 0xfffffe != 0) unsupported()
            val n = u32(box.end); if (n > HeicContainer.MAX_ITEMS) limit()
            val out = LinkedHashMap<Int, List<Link>>()
            repeat(n.toInt()) {
                val id = smallId(box.end, v == 1); val wide = flags and 1 != 0
                val assoc = List(u8(box.end)) {
                    val raw = if (wide) u16(box.end) else u8(box.end)
                    Link(raw and if (wide) 0x7fff else 0x7f, (raw and (if (wide) 0x8000 else 0x80)) != 0)
                }.filter { it.property != 0 }
                if (assoc.any { it.property > propertyCount } || assoc.map { it.property }.distinct().size != assoc.size || out.put(id, assoc) != null) invalid()
            }
            end(box.end); return out
        }
        private fun refs(box: Box?): List<Reference> {
            if (box == null) return emptyList()
            val (v, flags) = header(box)
            if (v !in 0..1 || flags != 0) unsupported()
            return boxes(input.filePointer, box.end).map { child ->
                input.seek(child.body); val from = smallId(child.end, v == 1); val n = u16(child.end)
                if (n > HeicContainer.MAX_ITEMS) limit()
                val to = List(n) { smallId(child.end, v == 1) }; end(child.end)
                Reference(child.type, from, to)
            }
        }
        private fun locations(box: Box, idat: Box?, mdats: List<Box>, selected: Set<Int>): Map<Int, List<Extent>> {
            val (v, flags) = header(box)
            if (v !in 0..2 || flags != 0) unsupported()
            val a = u8(box.end); val b = u8(box.end)
            val offsetSize = a ushr 4; val lengthSize = a and 15; val baseSize = b ushr 4; val indexSize = if (v > 0) b and 15 else 0
            if (listOf(offsetSize, lengthSize, baseSize, indexSize).any { it !in listOf(0, 4, 8) } || (v == 0 && b and 15 != 0)) unsupported()
            fun number(size: Int): Long = when(size) { 0 -> 0; 4 -> u32(box.end); else -> { val hi = u32(box.end); val lo = u32(box.end); if (hi > 0x7fffffffL) limit(); (hi shl 32) or lo } }
            fun sum(a: Long, b: Long): Long { if (a > Long.MAX_VALUE - b) invalid(); return a + b }
            val n = if (v < 2) u16(box.end).toLong() else u32(box.end)
            if (n > HeicContainer.MAX_ITEMS) limit()
            val seen = HashSet<Int>(); val out = LinkedHashMap<Int, List<Extent>>()
            var totalExtents = 0; var totalBytes = 0L
            repeat(n.toInt()) {
                val id = smallId(box.end, v == 2)
                if (!seen.add(id)) invalid()
                val method = if (v > 0) u16(box.end) else 0
                val reference = u16(box.end); val base = number(baseSize); val count = u16(box.end)
                totalExtents += count; if (totalExtents > HeicContainer.MAX_BOXES) limit()
                val extents = ArrayList<Extent>()
                repeat(count) {
                    val index = number(indexSize); val offset = number(offsetSize); val size = number(lengthSize)
                    if (id in selected) {
                        if (method !in 0..1 || reference != 0 || index != 0L) unsupported()
                        if (size <= 0) invalid()
                        val relative = sum(base, offset)
                        val at = if (method == 1) sum(idat?.body ?: invalid(), relative) else relative
                        val boundary = if (method == 1) idat!! else mdats.singleOrNull { at >= it.body && at < it.end } ?: invalid()
                        if (at < boundary.body || at > boundary.end || size > boundary.end - at) invalid()
                        totalBytes = sum(totalBytes, size); if (totalBytes > PhotoLimits.MAX_COMPRESSED_BYTES) limit()
                        extents.add(Extent(at, size))
                    }
                }
                if (id in selected) { if (extents.isEmpty()) invalid(); out[id] = extents }
            }
            end(box.end)
            if (out.keys != selected) invalid()
            return out
        }

        fun plan(original: File): Prepared {
            val directory = original.parentFile ?: original.absoluteFile.parentFile ?: invalid()
            if (length !in 1..PhotoLimits.MAX_COMPRESSED_BYTES) limit()
            val top = boxes(0, length)
            if (top.any { it.type in setOf("moov", "moof") }) unsupported()
            val ftyp = one(top, "ftyp"); input.seek(ftyp.body)
            if (ftyp.end - ftyp.body !in 8..4096 || (ftyp.end - ftyp.body) % 4 != 0L) invalid()
            val brands = mutableSetOf(cc(ftyp.end)); u32(ftyp.end)
            while (input.filePointer < ftyp.end) brands.add(cc(ftyp.end))
            if (brands.none { it in setOf("heic", "heix") } || brands.any { it in setOf("avif", "avis", "msf1", "hevc", "hevx", "hevm", "hevs") }) unsupported()
            val meta = one(top, "meta")
            if (meta.end - meta.body > HeicContainer.MAX_METADATA_BYTES) limit()
            if (header(meta) != (0 to 0)) unsupported()
            val parts = boxes(input.filePointer, meta.end)
            val handler = one(parts, "hdlr")
            if (header(handler) != (0 to 0)) unsupported()
            u32(handler.end); if (cc(handler.end) != "pict") unsupported()
            val pitm = one(parts, "pitm"); val (pv, pf) = header(pitm)
            if (pv !in 0..1 || pf != 0) unsupported()
            val primary = smallId(pitm.end, pv == 1); end(pitm.end)
            val items = items(one(parts, "iinf")); val base = items[primary] ?: invalid()
            if (base.hidden || base.type !in setOf("hvc1", "grid")) unsupported()
            val refBoxes = parts.filter { it.type == "iref" }; if (refBoxes.size > 1) invalid()
            val refs = refs(refBoxes.singleOrNull())
            if (refs.any { it.from !in items || it.to.any { to -> to !in items } }) invalid()
            // Explicit tmap relationship: primary is the base input, not the gain map or HDR output.
            val toneMaps = items.values.filter { it.type == "tmap" }
            if (toneMaps.size != 1) unsupported()
            val tmapRefs = refs.filter { it.from == toneMaps.single().id && it.type == "dimg" }
            val inputs = tmapRefs.singleOrNull()?.to ?: unsupported()
            if (inputs.size != 2 || inputs[0] != primary || inputs[1] == primary || items[inputs[1]]?.type !in setOf("hvc1", "grid")) unsupported()
            val primaryRefs = refs.filter { it.from == primary && it.type == "dimg" }
            val tiles = if (base.type == "grid") primaryRefs.singleOrNull()?.to ?: invalid() else {
                if (primaryRefs.isNotEmpty()) unsupported(); emptyList()
            }
            if (base.type == "grid" && (tiles.isEmpty() || tiles.distinct().size != tiles.size)) invalid()
            if (tiles.any { it == primary || items[it]?.type != "hvc1" }) unsupported()
            val selected = (listOf(primary) + tiles).toSet()
            if (inputs[1] in selected) unsupported()
            // Do not discard alpha, mirroring, dependency, or an unknown essential reference.
            for (ref in refs) if (ref.from in selected) {
                if (ref.type == "dimg") { if (ref.from != primary || base.type != "grid") unsupported() }
                else if (ref.type !in setOf("cdsc", "thmb")) unsupported()
            }
            if (refs.any { it.type !in setOf("dimg", "cdsc", "thmb") && it.to.any { to -> to in selected } }) unsupported()
            val iprp = one(parts, "iprp"); val iparts = boxes(iprp.body, iprp.end)
            val ipco = one(iparts, "ipco"); val props = boxes(ipco.body, ipco.end)
            if (props.size > HeicContainer.MAX_PROPERTIES) limit()
            val associations = links(one(iparts, "ipma"), props.size)
            if (associations.keys.any { it !in items }) invalid()
            val indices = selected.flatMap { associations[it].orEmpty().map(Link::property) }.distinct().sorted()
            val remap = indices.withIndex().associate { it.value to it.index + 1 }
            val selectedProps = indices.map { props[it - 1] }
            val idats = parts.filter { it.type == "idat" }; if (idats.size > 1) invalid()
            val extents = locations(one(parts, "iloc"), idats.singleOrNull(), top.filter { it.type == "mdat" }, selected)
            if (base.type == "grid") validateGrid(primary, tiles, extents, associations, props)
            val imageBrand = if ("heix" in brands) "heix" else "heic"
            val outputFtyp = box("ftyp", data { writeBytes(imageBrand); writeInt(0); writeBytes("mif1"); writeBytes(imageBrand) })
            val sizes = selected.associateWith { id -> extents.getValue(id).sumOf { it.length } }
            fun outputMeta(start: Long): ByteArray = full("meta", data {
                write(full("hdlr", data { writeInt(0); writeBytes("pict"); repeat(3) { writeInt(0) }; writeByte(0) }))
                write(full("pitm", data { writeShort(primary) }))
                write(full("iinf", data { writeShort(selected.size); for (id in selected) {
                    val item = items.getValue(id)
                    write(full("infe", data { writeShort(id); writeShort(0); writeBytes(item.type); writeByte(0) }, 2, if (id == primary) 0 else 1))
                } }))
                write(full("iloc", data {
                    writeByte(0x44); writeByte(0); writeShort(selected.size); var at = start
                    for (id in selected) { writeShort(id); writeShort(0); writeShort(1); writeInt(at.toInt()); writeInt(sizes.getValue(id).toInt()); at += sizes.getValue(id) }
                }))
                write(box("iprp", box("ipco", data { selectedProps.forEach { write(bytes(it)) } }) + full("ipma", data {
                    writeInt(selected.size)
                    // ISO/IEC 23008-12:2017 section 9.3.1 requires increasing item_ID in ipma. Keep this
                    // table order independent of the primary-first payload and dimg tile order.
                    // Android 17's CrabbyAvif rejects an unsorted table before codec creation.
                    for (id in selected.sorted()) { val links = associations[id].orEmpty(); writeShort(id); writeByte(links.size)
                        for (link in links) writeShort(remap.getValue(link.property) or if (link.essential) 0x8000 else 0)
                    }
                }, flags = 1)))
                if (tiles.isNotEmpty()) write(full("iref", box("dimg", data { writeShort(primary); writeShort(tiles.size); tiles.forEach { writeShort(it) } })))
            })
            val firstMeta = outputMeta(0); val payloadStart = outputFtyp.size.toLong() + firstMeta.size + 8
            val outMeta = outputMeta(payloadStart); check(firstMeta.size == outMeta.size)
            val payloadSize = sizes.values.sum(); val outputSize = payloadStart + payloadSize
            if (outputSize > PhotoLimits.MAX_COMPRESSED_BYTES) limit()
            // Validate the selected graph/profile in memory. Restoring a retained linear spool
            // must not write a new encoded file or require free disk space.
            val skeleton = outputFtyp + outMeta + box("mdat", byteArrayOf())
            val info = HeicContainer.inspectSelectedMetadata(skeleton, check)
            return Prepared(info, null) {
                check()
                if (directory.usableSpace < outputSize + PhotoLimits.IMPORT_RESERVE_BYTES) throw Storage()
                val output = File.createTempFile("heic-primary-", ".heic", directory)
                try {
                    RandomAccessFile(original, "r").use { source ->
                        if (source.length() != length) invalid()
                        output.outputStream().buffered(64 * 1024).use { stream ->
                            stream.write(outputFtyp); stream.write(outMeta)
                            stream.write(data { writeInt((payloadSize + 8).toInt()); writeBytes("mdat") })
                            val buffer = ByteArray(64 * 1024)
                            for (id in selected) for (extent in extents.getValue(id)) {
                                source.seek(extent.offset); var left = extent.length
                                while (left > 0) { check(); val n = minOf(buffer.size.toLong(), left).toInt(); source.readFully(buffer, 0, n); stream.write(buffer, 0, n); left -= n }
                            }
                        }
                    }
                    check(); if (output.length() != outputSize) invalid()
                    if (HeicContainer.inspect(output, check) != info) invalid()
                    output
                } catch (error: Throwable) { output.delete(); throw error }
            }
        }

        private fun validateGrid(primary: Int, tiles: List<Int>, extents: Map<Int, List<Extent>>, links: Map<Int, List<Link>>, props: List<Box>) {
            val parts = extents.getValue(primary)
            val total = parts.sumOf { it.length }; if (total !in listOf(8L, 12L)) invalid()
            val payload = ByteArray(total.toInt()); var at = 0
            for (part in parts) { input.seek(part.offset); input.readFully(payload, at, part.length.toInt()); at += part.length.toInt() }
            val buffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
            val version = buffer.get().toInt() and 255; val flags = buffer.get().toInt() and 255
            if (version != 0 || flags !in 0..1 || total != if (flags == 0) 8L else 12L) unsupported()
            val rows = (buffer.get().toInt() and 255) + 1; val cols = (buffer.get().toInt() and 255) + 1
            if (rows * cols != tiles.size) invalid()
            fun size() = if (flags == 0) (buffer.short.toInt() and 65535).toLong() else buffer.int.toLong() and 0xffffffffL
            val width = size(); val height = size()
            fun shape(id: Int): Pair<Long, Long> {
                val property = links[id].orEmpty().map { props[it.property - 1] }.filter { it.type == "ispe" }.singleOrNull() ?: invalid()
                if (header(property) != (0 to 0)) unsupported()
                val w = u32(property.end); val h = u32(property.end); end(property.end)
                if (w > Int.MAX_VALUE || h > Int.MAX_VALUE || !PhotoLimits.validSource(w.toInt(), h.toInt())) limit()
                return w to h
            }
            if (shape(primary) != (width to height)) invalid()
            val tileShape = shape(tiles.first())
            if (tiles.any { shape(it) != tileShape }) unsupported()
            if (width <= (cols - 1) * tileShape.first || width > cols * tileShape.first || height <= (rows - 1) * tileShape.second || height > rows * tileShape.second) invalid()
        }
    }
}
