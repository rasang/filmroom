package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Restricted independent-grid route. Does not relax the ordinary container/color admission.
 * Only non-grid inputs return no plan. A recognized grid that cannot prove the
 * smaller-budget contract fails closed, rather than trusting a possibly false ispe.
 * A plan is not a proof of valid HEVC pixels: the platform must decode every complete tile.
 */
internal object HeicGridInput {
    const val MAX_TILE_PIXELS = 1_048_576L
    const val MAX_TILE_BYTES = 8L * 1024 * 1024
    private const val MAX_HEADER_BYTES = 128 * 1024
    private const val MAX_PLANNED_HEADERS = 8L * 1024 * 1024

    internal data class Tile(val info: HeicContainer.Info, val extents: List<Extent>,
        val prefix: ByteArray, val payloadBytes: Long, val digest: ByteArray)

    class PreparedTile internal constructor(val file: File, val info: HeicContainer.Info,
        val x: Int, val y: Int, val validWidth: Int, val validHeight: Int) : Closeable {
        override fun close() { file.delete() }
    }

    class Plan internal constructor(private val source: File, val info: HeicContainer.Info,
        val columns: Int, val rows: Int, private val tiles: List<Tile>,
        private val sourceBytes: Long, private val sourceModified: Long) {
        val tileCount: Int get() = tiles.size
        val maximumViewBytes: Long = tiles.maxOf { it.prefix.size + it.payloadBytes }
        // Includes one platform tile, its native buffers, one oriented RGB-float tile,
        // row buffers and bounded plan metadata. This remains an estimate, not a cap.
        val estimatedBytes: Long = 64L * 1024 * 1024 + tiles.maxOf {
            it.info.estimatedDecodePixels * 48L + it.prefix.size + it.payloadBytes
        }
        private fun unchanged() {
            if (source.length()!=sourceBytes || source.lastModified()!=sourceModified)
                throw java.io.IOException("HEIF source changed during grid import")
        }
        fun materialize(index: Int, check: () -> Unit): PreparedTile {
            check(); unchanged()
            val tile=tiles[index]
            val directory=source.parentFile ?: throw java.io.IOException("Missing HEIF source directory")
            val size=tile.prefix.size+tile.payloadBytes
            if(directory.usableSpace<size+PhotoLimits.IMPORT_RESERVE_BYTES)throw HeicPrimaryInput.Storage()
            // Reuse the already recognized private primary-view temporary-file family.
            val target=File.createTempFile("heic-primary-", ".heic", directory)
            try {
                RandomAccessFile(source,"r").use { input ->
                    target.outputStream().use { output ->
                        output.write(tile.prefix)
                        val stream=ExtentsInput(input,tile.extents,check)
                        val buffer=ByteArray(64*1024)
                        while(true) { val n=stream.read(buffer);if(n<0)break;output.write(buffer,0,n) }
                        if(stream.readBytesCount!=tile.payloadBytes || !MessageDigest.isEqual(stream.digest.digest(),tile.digest))
                            throw java.io.IOException("HEIF tile payload changed")
                        output.fd.sync()
                    }
                }
                check();unchanged()
                if(target.length()!=size || HeicContainer.inspect(target,check)!=tile.info)
                    throw java.io.IOException("Invalid independent HEIF tile view")
                val tw=tile.info.encodedWidth;val th=tile.info.encodedHeight
                val x=(index%columns)*tw;val y=(index/columns)*th
                return PreparedTile(target,tile.info,x,y,minOf(tw,info.encodedWidth-x),minOf(th,info.encodedHeight-y))
            } catch(error:Throwable) { target.delete();throw error }
        }
    }

    class Rejected internal constructor(val failure: HeicContainer.Failure, cause: HeicContainer.Rejected) :
        java.io.IOException("HEIF grid configuration cannot be admitted safely", cause)

    /** Only a validated non-grid input retains the ordinary path. Once classified as a
     * grid, unsupported/malformed layout or HEVC cannot bypass validation via fallback.
     * The old whole-image estimate trusts container sizes, not actual SPS dimensions.
     * Cancellation and I/O failures propagate; no view is created while planning. */
    fun planOrNull(file: File, info: HeicContainer.Info, check: () -> Unit): Plan? {
        try {
            val candidate = HeicContainer.inspectGridCandidate(file, check)
            if (candidate.info != info) throw java.io.IOException("HEIF source metadata changed")
            if (!candidate.primaryIsGrid) return null
            return RandomAccessFile(file, "r").use { Reader(it, check).grid(file, info) }
        } catch (error: HeicContainer.Rejected) {
            throw Rejected(error.failure, error)
        }
    }

    private class ExtentsInput(val file:RandomAccessFile,val extents:List<Extent>,val check:()->Unit):InputStream() {
        private var index=0;private var within=0L
        var readBytesCount=0L;private set
        val digest:MessageDigest=MessageDigest.getInstance("SHA-256")
        override fun read():Int { val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255 }
        override fun read(b:ByteArray,off:Int,len:Int):Int {
            require(off>=0 && len>=0 && off<=b.size-len)
            check();if(len==0)return 0
            while(index<extents.size && within==extents[index].length){index++;within=0}
            if(index==extents.size)return -1
            val extent=extents[index];val n=minOf(len.toLong(),extent.length-within).toInt()
            file.seek(extent.offset+within);file.readFully(b,off,n);within+=n;readBytesCount+=n
            digest.update(b,off,n);return n
        }
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
    internal data class Extent(val offset: Long, val length: Long)

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
                        totalBytes = sum(totalBytes, size); if (totalBytes > PhotoLimits.MAX_COMPRESSED_BYTES) HeicContainer.sizeLimit(dev.filmroom.R.string.text_2673ef9ba447)
                        extents.add(Extent(at, size))
                    }
                }
                if (id in selected) { if (extents.isEmpty()) invalid(); out[id] = extents }
            }
            end(box.end)
            if (out.keys != selected) invalid()
            return out
        }

        fun grid(original:File,info:HeicContainer.Info):Plan {
            val modified=original.lastModified()
            if(length<=0 || length>PhotoLimits.MAX_COMPRESSED_BYTES)unsupported()
            val top=boxes(0,length)
            if(top.any { it.type in setOf("moov","moof") })unsupported()
            val ftyp=one(top,"ftyp");input.seek(ftyp.body)
            if(ftyp.end-ftyp.body !in 8..4096 || (ftyp.end-ftyp.body)%4!=0L)invalid()
            val brands=mutableSetOf(cc(ftyp.end));u32(ftyp.end)
            while(input.filePointer<ftyp.end)brands.add(cc(ftyp.end))
            if(brands.none { it in setOf("heic","heix") } || brands.any { it in setOf("avif","avis","msf1","hevc","hevx","hevm","hevs") })unsupported()
            val meta=one(top,"meta")
            if(meta.end-meta.body>HeicContainer.MAX_METADATA_BYTES)limit()
            if(header(meta)!=(0 to 0))unsupported()
            val parts=boxes(input.filePointer,meta.end)
            val handler=one(parts,"hdlr");if(header(handler)!=(0 to 0))unsupported()
            u32(handler.end);if(cc(handler.end)!="pict")unsupported()
            val pitm=one(parts,"pitm");val(pv,pf)=header(pitm)
            if(pv !in 0..1 || pf!=0)unsupported()
            val primary=smallId(pitm.end,pv==1);end(pitm.end)
            val allItems=items(one(parts,"iinf"));val base=allItems[primary] ?: invalid()
            if(base.hidden || base.type!="grid" || allItems.values.count { it.type=="grid" }!=1)unsupported()
            if(allItems.values.any { it.type !in setOf("grid","hvc1","Exif","mime") })unsupported()
            val refBoxes=parts.filter { it.type=="iref" };if(refBoxes.size!=1)unsupported()
            val references=refs(refBoxes.single())
            if(references.any { it.from !in allItems || it.to.any { id->id !in allItems } })invalid()
            if(references.any { it.type !in setOf("dimg","cdsc","thmb") })unsupported()
            val ordered=references.filter { it.from==primary && it.type=="dimg" }.singleOrNull()?.to ?: invalid()
            if(ordered.isEmpty() || ordered.distinct().size!=ordered.size || ordered.any { it==primary || allItems[it]?.type!="hvc1" })unsupported()
            if(references.any { it.type=="dimg" && it.from!=primary })unsupported()
            val iprp=one(parts,"iprp");val iparts=boxes(iprp.body,iprp.end)
            val ipco=one(iparts,"ipco");val props=boxes(ipco.body,ipco.end)
            if(props.size>HeicContainer.MAX_PROPERTIES)limit()
            val associations=links(one(iparts,"ipma"),props.size)
            if(associations.keys.any { it !in allItems })invalid()
            fun linked(id:Int)=associations[id].orEmpty().map { it to props[it.property-1] }
            fun shape(id:Int):Pair<Int,Int> {
                val b=linked(id).filter { it.second.type=="ispe" }.singleOrNull()?.second ?: invalid()
                if(header(b)!=(0 to 0))unsupported()
                val w=u32(b.end);val h=u32(b.end);end(b.end);HeicContainer.validateSourceSize(w,h)
                return w.toInt() to h.toInt()
            }
            if(shape(primary)!=(info.encodedWidth to info.encodedHeight))invalid()
            if(linked(primary).any { it.second.type !in setOf("ispe","pixi","colr","irot") })unsupported()
            if(info.rotationDegreesClockwise!=0 && (info.encodedWidth%2!=0 || info.encodedHeight%2!=0))unsupported()
            val selected=(listOf(primary)+ordered).toSet()
            val idats=parts.filter { it.type=="idat" };if(idats.size>1)invalid()
            val locations=locations(one(parts,"iloc"),idats.singleOrNull(),top.filter { it.type=="mdat" },selected)
            val gridLocations=locations.getValue(primary);val gridLength=gridLocations.sumOf { it.length }
            if(gridLength !in listOf(8L,12L))unsupported()
            val grid=ByteArray(gridLength.toInt());var p=0
            for(e in gridLocations){input.seek(e.offset);input.readFully(grid,p,e.length.toInt());p+=e.length.toInt()}
            val gb=java.nio.ByteBuffer.wrap(grid).order(java.nio.ByteOrder.BIG_ENDIAN)
            val gv=gb.get().toInt() and 255;val flags=gb.get().toInt() and 255
            if(gv!=0 || flags !in 0..1 || gridLength!=if(flags==0)8L else 12L)unsupported()
            val rows=(gb.get().toInt() and 255)+1;val columns=(gb.get().toInt() and 255)+1
            fun number()=if(flags==0)(gb.short.toInt() and 65535).toLong() else gb.int.toLong() and 0xffffffffL
            if(rows*columns!=ordered.size || number()!=info.encodedWidth.toLong() || number()!=info.encodedHeight.toLong())invalid()
            val(tw,th)=shape(ordered.first())
            if(tw.toLong()*th>MAX_TILE_PIXELS || tw>2048 || th>2048)unsupported()
            if(ordered.any { shape(it)!=(tw to th) })unsupported()
            if(info.encodedWidth.toLong() !in ((columns-1L)*tw+1)..(columns.toLong()*tw) ||
                info.encodedHeight.toLong() !in ((rows-1L)*th+1)..(rows.toLong()*th))invalid()
            val primaryColors=linked(primary).filter { it.second.type=="colr" }
            if(primaryColors.size>1)unsupported()
            val canonicalColor=primaryColors.singleOrNull()?.let { bytes(it.second) }
                ?: linked(ordered.first()).filter { it.second.type=="colr" }.singleOrNull()?.let { bytes(it.second) } ?: unsupported()
            val outputFtyp=box("ftyp",data {writeBytes(if("heix" in brands)"heix" else "heic");writeInt(0);writeBytes("mif1");writeBytes("heic")})
            var planned=0L
            val tiles=ordered.map { id ->
                check()
                val linked=linked(id)
                if(linked.any { it.second.type !in setOf("ispe","pixi","colr","hvcC") })unsupported()
                val colors=linked.filter { it.second.type=="colr" }
                if(colors.size>1 || (colors.size==1 && !bytes(colors.single().second).contentEquals(canonicalColor)))unsupported()
                val rawProps=linked.map { it.first.essential to bytes(it.second) }.toMutableList()
                if(colors.isEmpty())rawProps.add(true to canonicalColor)
                if(rawProps.size>255)unsupported()
                val config=rawProps.filter { String(it.second,4,4,Charsets.US_ASCII)=="hvcC" }.singleOrNull()?.second ?: invalid()
                val extents=locations.getValue(id);val payloadBytes=extents.sumOf { it.length }
                if(payloadBytes !in 1..MAX_TILE_BYTES)unsupported()
                val stream=ExtentsInput(input,extents,check)
                if(!HevcTileConfiguration.validate(config.copyOfRange(8,config.size),stream,payloadBytes,tw,th,check))unsupported()
                if(stream.readBytesCount!=payloadBytes || stream.read()!=-1)invalid()
                val digest=stream.digest.digest()
                fun outputMeta(offset:Long)=full("meta",data {
                    write(full("hdlr",data {writeInt(0);writeBytes("pict");repeat(3){writeInt(0)};writeByte(0)}))
                    write(full("pitm",data {writeShort(1)}))
                    write(full("iinf",data {writeShort(1);write(full("infe",data {writeShort(1);writeShort(0);writeBytes("hvc1");writeByte(0)},2))}))
                    write(full("iloc",data {writeByte(0x44);writeByte(0);writeShort(1);writeShort(1);writeShort(0);writeShort(1);writeInt(offset.toInt());writeInt(payloadBytes.toInt())}))
                    write(box("iprp",box("ipco",data {rawProps.forEach { write(it.second) }})+full("ipma",data {
                        writeInt(1);writeShort(1);writeByte(rawProps.size)
                        rawProps.forEachIndexed { index,item ->writeShort(index+1 or if(item.first)0x8000 else 0) }
                    },flags=1)))
                })
                val first=outputMeta(0);val payloadStart=outputFtyp.size.toLong()+first.size+8
                val metadata=outputMeta(payloadStart);check(first.size==metadata.size)
                if(payloadStart>MAX_HEADER_BYTES)unsupported()
                planned+=payloadStart;if(planned>MAX_PLANNED_HEADERS)unsupported()
                val skeleton=outputFtyp+metadata+box("mdat",byteArrayOf())
                val tileInfo=HeicContainer.inspectSelectedMetadata(skeleton,check)
                if(tileInfo.encodedWidth!=tw || tileInfo.encodedHeight!=th || tileInfo.rotationDegreesClockwise!=0 ||
                    tileInfo.sourceProfile!=info.sourceProfile || tileInfo.estimatedDecodePixels!=tw.toLong()*th)unsupported()
                val prefix=outputFtyp+metadata+data {writeInt((payloadBytes+8).toInt());writeBytes("mdat")}
                Tile(tileInfo,extents,prefix,payloadBytes,digest)
            }
            if(original.length()!=length || original.lastModified()!=modified)throw java.io.IOException("HEIF source changed while planning")
            return Plan(original,info,columns,rows,tiles,length,modified)
        }
    }
}
