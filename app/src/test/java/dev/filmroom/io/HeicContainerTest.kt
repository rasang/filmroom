package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class HeicContainerTest {
    /** Real HEVC bitstreams; this test validates container admission, NOT Android pixel decoding. */
    @Test fun realCc0HevcFixturesAndRotationsHaveExpectedContainerAdmission() {
        val root = listOf(File("src/androidTest/assets/heic"), File("app/src/androidTest/assets/heic"))
            .firstOrNull { it.isDirectory } ?: error("HEIC test fixtures missing")
        for ((name, rotation) in listOf("corners-sdr8.heic" to 0, "corners-grid2x2.heic" to 0, "corners-grid-thumbnail.heic" to 0, "corners-rotate90ccw.heic" to 270,
            "corners-rotate180.heic" to 180, "corners-rotate270ccw.heic" to 90)) {
            val info = HeicContainer.inspect(File(root, name))
            assertEquals(name, 96, info.width); assertEquals(name, 64, info.height)
            assertEquals(name, rotation, info.rotationDegreesClockwise)
        }
        assertEquals(5L*48*32,HeicContainer.inspect(File(root,"corners-grid-thumbnail.heic")).estimatedDecodePixels)
        for (name in listOf("corners-mirror.heic", "corners-main10.heic", "corners-display-p3.heic")) {
            try { HeicContainer.inspect(File(root,name));fail(name) }
            catch(e:HeicContainer.Rejected) { assertEquals(name,HeicContainer.Failure.UNSUPPORTED,e.failure) }
        }
    }

    @Test fun verifiedP3IccCanBeOnPrimaryOrInheritedFromAllGridTiles(){
        val root=listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic")).first{it.isDirectory}
        for(name in listOf("corners-icc-p3.heic","corners-grid-icc-p3-tiles.heic")){
            val info=HeicContainer.inspect(File(root,name))
            assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,info.sourceColor)
            assertTrue(info.declaredNclx.isEmpty())
            assertEquals(96,info.displayWidth);assertEquals(64,info.displayHeight)
        }
    }

    @Test fun conflictingOrIncompleteIccDeclarationsOnGridFailClosed(){
        val root=listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic")).first{it.isDirectory}
        val icc=box("colr","prof".toByteArray()+File(root,"original-display-p3.icc").readBytes())
        rejects(HeicContainer.Failure.UNSUPPORTED,heic(extra=listOf(icc))) // baseline NCLX + P3 ICC conflict
        val props=listOf(ispe(96,64),hvcc(),icc,colr(13))
        val items=listOf(item(1,"grid"),item(2,"hvc1",true),item(3,"hvc1",true))
        val refs=full("iref",data{write(box("dimg",data{writeShort(1);writeShort(2);writeShort(2);writeShort(3)}))})
        rejects(HeicContainer.Failure.UNSUPPORTED,file(items,props,listOf(1 to listOf(1),2 to listOf(1,2,3),3 to listOf(1,2)),refs))
        rejects(HeicContainer.Failure.UNSUPPORTED,file(items,props,listOf(1 to listOf(1),2 to listOf(1,2,3),3 to listOf(1,2,4)),refs))
        rejects(HeicContainer.Failure.UNSUPPORTED,file(items,props,listOf(1 to listOf(1,3),2 to listOf(1,2,4),3 to listOf(1,2,3)),refs))
    }

    @Test fun nonSrgbNclxAndUnverifiedIccCannotBeSilentlyRelabeledSrgb() {
        fun declared(p:Int,t:Int,m:Int)=box("colr",data{writeBytes("nclx");writeShort(p);writeShort(t);writeShort(m);writeByte(0)})
        assertTrue(inspect(heic(extra=listOf(declared(1,13,1)))).declaredNclx.all { it==HeicContainer.Nclx(1,13,1,false) })
        for (p in listOf(0,2,9,12)) rejects(HeicContainer.Failure.UNSUPPORTED,heic(extra=listOf(declared(p,13,1))))
        for (t in listOf(1,2,6,16,18)) rejects(HeicContainer.Failure.UNSUPPORTED,heic(extra=listOf(declared(1,t,1))))
        for (m in listOf(0,2,6,9)) rejects(HeicContainer.Failure.UNSUPPORTED,heic(extra=listOf(declared(1,13,m))))
        for(kind in listOf("prof","rICC")) rejects(HeicContainer.Failure.INVALID,
            heic(extra=listOf(box("colr",kind.toByteArray()+ByteArray(128)))))
    }

    @Test fun missingOrUnassociatedColorDeclarationCannotUsePlatformFallbackAsProof(){
        rejects(HeicContainer.Failure.UNSUPPORTED,file(listOf(item(1,"hvc1")),listOf(ispe(96,64),hvcc()),listOf(1 to listOf(1,2))))
        rejects(HeicContainer.Failure.UNSUPPORTED,file(listOf(item(1,"hvc1")),listOf(ispe(96,64),hvcc(),colr(13)),listOf(1 to listOf(1,2))))
    }

    @Test fun primaryDimensionsAndCounterclockwiseContainerRotation() {
        val plain = inspect(heic())
        assertEquals(96, plain.width); assertEquals(64, plain.height)
        assertEquals(6144L, plain.estimatedDecodePixels)
        val rotated = inspect(heic(extra = listOf(box("irot", byteArrayOf(1)))))
        assertEquals(270, rotated.rotationDegreesClockwise)
        assertEquals(64, rotated.displayWidth); assertEquals(96, rotated.displayHeight)
    }

    @Test fun allQuarterTurnsProduceKnownDisplayDimensions() {
        for (quarter in 0..3) {
            val info = inspect(heic(extra = listOf(box("irot", byteArrayOf(quarter.toByte())))))
            assertEquals((360 - quarter * 90) % 360, info.rotationDegreesClockwise)
            assertEquals(if (quarter % 2 == 0) 96 else 64, info.displayWidth)
        }
    }

    @Test fun gridIncludesPaddingAndAllTilesInMemoryEstimate() {
        val props = listOf(ispe(1, 1), ispe(96, 64), hvcc(), colr(13))
        val info = inspect(file(
            listOf(item(1, "grid"), item(2, "hvc1", hidden = true), item(3, "hvc1", hidden = true)),
            props, listOf(1 to listOf(1), 2 to listOf(2, 3, 4), 3 to listOf(2, 3, 4)),
            refs = full("iref", data { write(box("dimg", data { writeShort(1); writeShort(2); writeShort(2); writeShort(3) })) }),
        ))
        assertEquals(1, info.width)
        assertEquals(6144L, info.maxCodedPixels)
        assertEquals(12288L, info.estimatedDecodePixels)
    }

    @Test fun highBitDepthHdrAuxiliaryMirrorCropAndSequenceAreExplicitlyUnsupported() {
        rejects(HeicContainer.Failure.UNSUPPORTED, heic(hvcc = hvcc(depth = 2)))
        rejects(HeicContainer.Failure.UNSUPPORTED, heic(hvcc = hvcc(chroma = 2)))
        rejects(HeicContainer.Failure.UNSUPPORTED, heic(hvcc = hvcc(profile = 4)))
        for (type in listOf("imir", "clap", "auxC", "mdcv", "clli"))
            rejects(HeicContainer.Failure.UNSUPPORTED, heic(extra = listOf(box(type, byteArrayOf(0)))))
        for (transfer in listOf(16, 18, 0, 255))
            rejects(HeicContainer.Failure.UNSUPPORTED, heic(extra = listOf(colr(transfer))))
        for (brand in listOf("avif", "avis", "msf1", "hevc", "hevx", "hevm", "hevs"))
            rejects(HeicContainer.Failure.UNSUPPORTED, heic(brands = listOf("heic", brand)))
        rejects(HeicContainer.Failure.UNSUPPORTED, heic(brands = listOf("mif1")))
        rejects(HeicContainer.Failure.UNSUPPORTED, heic() + box("moov", byteArrayOf()))
        rejects(HeicContainer.Failure.UNSUPPORTED, heic(extra = listOf(full("pixi", byteArrayOf(3, 10, 10, 10)))))
    }

    @Test fun unknownOptionalPropertyDoesNotClaimItIsUnderstoodButEssentialIsRejected() {
        inspect(heic(extra = listOf(box("abcd", byteArrayOf(0)))))
        rejects(HeicContainer.Failure.UNSUPPORTED,
            file(listOf(item(1, "hvc1")), listOf(ispe(96,64), hvcc(), box("abcd", byteArrayOf(0))),
                listOf(1 to listOf(1, 2, 0x83))))
    }

    @Test fun duplicatedDimensionsRotationOrAssociationFailClosed() {
        rejects(HeicContainer.Failure.INVALID, heic(extra = listOf(ispe(96,64))))
        rejects(HeicContainer.Failure.INVALID, heic(extra = listOf(box("irot", byteArrayOf(1)), box("irot", byteArrayOf(2)))))
        rejects(HeicContainer.Failure.INVALID, file(listOf(item(1,"hvc1")), listOf(ispe(96,64),hvcc()), listOf(1 to listOf(1,1,2))))
        rejects(HeicContainer.Failure.INVALID, file(listOf(item(1,"hvc1")), listOf(ispe(96,64),hvcc()), listOf(1 to listOf(1,3))))
    }

    @Test fun invalidDimensionsAndHugeMetadataFailBeforeAnyDecoder() {
        for ((w,h) in listOf(0 to 1, 30001 to 2, 30000 to 30000, -1 to 1))
            rejects(HeicContainer.Failure.LIMIT, heic(width=w,height=h))
        rejects(HeicContainer.Failure.LIMIT, ftyp(listOf("heic")) + full("meta", ByteArray(HeicContainer.MAX_METADATA_BYTES.toInt())))
        rejects(HeicContainer.Failure.LIMIT, ByteArrayOutputStream().apply { repeat(HeicContainer.MAX_BOXES+1) { write(box("free", byteArrayOf())) } }.toByteArray())
    }

    @Test fun truncatedAndOverflowBoxesAreRejectedWithoutUnboundedAllocation() {
        val sample=heic()
        for (size in 0 until sample.size) {
            try { inspect(sample.copyOf(size)); fail("accepted prefix $size") } catch (_: HeicContainer.Rejected) { }
        }
        rejects(HeicContainer.Failure.INVALID, data { writeInt(7); writeBytes("ftyp") })
        rejects(HeicContainer.Failure.INVALID, data { writeInt(Int.MAX_VALUE); writeBytes("ftyp") })
        rejects(HeicContainer.Failure.LIMIT, data { writeInt(1); writeBytes("ftyp"); writeLong(Long.MAX_VALUE) })
        rejects(HeicContainer.Failure.INVALID, heic(extra=listOf(box("irot",byteArrayOf(4)))))
    }

    @Test fun boundedRandomInputNeverEscapesAsIndexErrorOrEofException() {
        val random=Random(7251)
        repeat(300) {
            val bytes=ByteArray(random.nextInt(1,4096));random.nextBytes(bytes)
            try { inspect(bytes); fail("random data accepted") } catch (_:HeicContainer.Rejected) { }
        }
    }

    @Test fun cancellationPropagatesAndClosesInput() {
        val source=File.createTempFile("heic-cancel-", ".heic")
        try {
            source.writeBytes(heic())
            var checks=0
            try { HeicContainer.inspect(source) { if (++checks==7)throw CancellationException("stop") };fail() }
            catch (_:CancellationException) { }
            assertEquals(7,checks)
            assertTrue(source.delete())
        } finally { source.delete() }
    }

    @Test fun sparseOversizeFileRejectedWithoutReadingPayload() {
        val source=File.createTempFile("heic-size-", ".heic")
        try {
            RandomAccessFile(source,"rw").use { it.setLength(PhotoLimits.MAX_COMPRESSED_BYTES+1) }
            try { HeicContainer.inspect(source);fail() } catch(e:HeicContainer.Rejected) { assertEquals(HeicContainer.Failure.LIMIT,e.failure) }
        } finally { source.delete() }
    }

    private fun rejects(failure:HeicContainer.Failure, bytes:ByteArray) {
        try { inspect(bytes);fail("expected $failure") } catch(e:HeicContainer.Rejected) { assertEquals(e.message,failure,e.failure) }
    }
    private fun inspect(bytes:ByteArray):HeicContainer.Info {
        val f=File.createTempFile("heic-header-", ".heic")
        return try { f.writeBytes(bytes);HeicContainer.inspect(f) } finally { f.delete() }
    }
    private fun data(block:DataOutputStream.()->Unit):ByteArray = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.block() } }.toByteArray()
    private fun box(type:String, body:ByteArray)=data { writeInt(body.size+8);writeBytes(type);write(body) }
    private fun full(type:String, body:ByteArray, version:Int=0,flags:Int=0)=box(type,data { writeInt((version shl 24) or flags);write(body) })
    private fun ftyp(brands:List<String>)=box("ftyp",data { writeBytes(brands.first());writeInt(0);brands.drop(1).forEach { writeBytes(it) } })
    private fun ispe(w:Int,h:Int)=full("ispe",data { writeInt(w);writeInt(h) })
    private fun hvcc(depth:Int=0,chroma:Int=1,profile:Int=1)=box("hvcC",ByteArray(23).also { it[0]=1;it[1]=profile.toByte();it[16]=(0xfc or chroma).toByte();it[17]=(0xf8 or depth).toByte();it[18]=(0xf8 or depth).toByte() })
    private fun colr(transfer:Int)=box("colr",data { writeBytes("nclx");writeShort(1);writeShort(transfer);writeShort(1);writeByte(0) })
    private fun item(id:Int,type:String,hidden:Boolean=false)=full("infe",data { writeShort(id);writeShort(0);writeBytes(type);writeByte(0) },2,if(hidden)1 else 0)
    private fun heic(width:Int=96,height:Int=64,hvcc:ByteArray=hvcc(),extra:List<ByteArray> = emptyList(),brands:List<String> = listOf("heic","mif1")):ByteArray {
        val props=listOf(ispe(width,height),hvcc,colr(13))+extra
        return file(listOf(item(1,"hvc1")),props,listOf(1 to (1..props.size).toList()),brands=brands)
    }
    private fun file(items:List<ByteArray>,properties:List<ByteArray>,links:List<Pair<Int,List<Int>>>,refs:ByteArray=byteArrayOf(),brands:List<String> = listOf("heic","mif1")):ByteArray =
        ftyp(brands)+full("meta",data {
            write(full("pitm",data { writeShort(1) }))
            write(full("iinf",data { writeShort(items.size);items.forEach { write(it) } }))
            write(box("iprp",box("ipco",data { properties.forEach { write(it) } }) + full("ipma",data {
                writeInt(links.size);links.forEach { (id,indices)->writeShort(id);writeByte(indices.size);indices.forEach { writeByte(it) } }
            })))
            write(refs)
        })
}
