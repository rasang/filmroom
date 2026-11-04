package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic container/payload tests only; these bytes do not claim to be decodable HEVC. */
class HeicPrimaryInputTest {
    private val coded = byteArrayOf(0,0,0,4,0x26,1,0x12,0x34)
    private fun data(block:DataOutputStream.()->Unit)=ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun box(type:String,body:ByteArray)=data { writeInt(body.size+8);writeBytes(type);write(body) }
    private fun full(type:String,body:ByteArray,version:Int=0,flags:Int=0)=box(type,data {writeInt((version shl 24) or flags);write(body)})
    private fun ispe(w:Int=96,h:Int=64)=full("ispe",data {writeInt(w);writeInt(h)})
    private fun colr(transfer:Int)=box("colr",data {writeBytes("nclx");writeShort(1);writeShort(transfer);writeShort(1);writeByte(0)})
    private fun hvcc(depth:Int)=box("hvcC",ByteArray(23).also {it[0]=1;it[1]=3;it[16]=0xfd.toByte();it[17]=(0xf8 or depth).toByte();it[18]=(0xf8 or depth).toByte()})
    private fun infe(id:Int,type:String)=full("infe",data {writeShort(id);writeShort(0);writeBytes(type);writeByte(0)},2,if(id==4)1 else 0)
    private fun reference(type:String,from:Int,to:List<Int>)=box(type,data {writeShort(from);writeShort(to.size);to.forEach {writeShort(it)}})
    internal fun sample(grid:Boolean=false,method:Int=0,transfer:Int=13,depth:Int=0,
        offsetDelta:Int=0,lengthDelta:Int=0,external:Int=0,tmapInputs:List<Int> = listOf(1,2),
        extraRef:ByteArray=byteArrayOf(),essentialUnknown:Boolean=false,gridWidth:Int=96,
        gridColumns:Int=1,duplicateLocation:Boolean=false,omitLocation:Boolean=false,
        wideOffsets:Boolean=false,multipleExtents:Boolean=false,overflowBase:Boolean=false,
        primaryType:String=if(grid)"grid" else "hvc1"):ByteArray {
        val gridPayload=byteArrayOf(0,0,0,(gridColumns-1).toByte(),(gridWidth ushr 8).toByte(),gridWidth.toByte(),0,64)
        val payload=if(grid)gridPayload+coded else coded
        val selected=if(grid)listOf(1,4) else listOf(1)
        val ftyp=box("ftyp",data {writeBytes("heic");writeInt(0);writeBytes("mif1");writeBytes("tmap")})
        val props=listOf(ispe(),hvcc(depth),colr(transfer),colr(16))+if(essentialUnknown)listOf(box("abcd",byteArrayOf(1)))else emptyList()
        fun meta(start:Int)=full("meta",data {
            write(full("hdlr",data {writeInt(0);writeBytes("pict");repeat(3){writeInt(0)};writeByte(0)}))
            write(full("pitm",data {writeShort(1)}))
            val entries=listOf(infe(1,primaryType),infe(2,"hvc1"),infe(3,"tmap"))+if(grid)listOf(infe(4,"hvc1"))else emptyList()
            write(full("iinf",data {writeShort(entries.size);entries.forEach {write(it)}}))
            write(box("iprp",box("ipco",data {props.forEach {write(it)}})+full("ipma",data {
                writeInt(selected.size+1)
                for(id in selected){
                    val links=(if(grid&&id==1)listOf(1,3)else listOf(1,2,3))+if(essentialUnknown)listOf(0x85)else emptyList()
                    writeShort(id);writeByte(links.size);links.forEach {writeByte(it)}
                }
                writeShort(3);writeByte(1);writeByte(4)
            })))
            write(full("iref",reference("dimg",3,tmapInputs)+(if(grid)reference("dimg",1,listOf(4))else byteArrayOf())+extraRef))
            val locations=if(omitLocation)emptyList()else selected+if(duplicateLocation)listOf(1)else emptyList()
            write(full("iloc",data {
                writeByte(if(wideOffsets)0x88 else 0x44);writeByte(if(overflowBase)0x80 else 0);writeShort(locations.size)
                for(id in locations){
                    writeShort(id);writeShort(method);writeShort(external)
                    if(overflowBase)writeLong(Long.MAX_VALUE)
                    val offset=((if(method==1)0 else start)+(if(id==4)8 else 0)+offsetDelta).toLong()
                    val size=((if(grid&&id==1)gridPayload.size else coded.size)+lengthDelta).toLong()
                    writeShort(if(multipleExtents)2 else 1)
                    fun extent(at:Long,length:Long){if(wideOffsets){writeLong(at);writeLong(length)}else{writeInt(at.toInt());writeInt(length.toInt())}}
                    if(multipleExtents){extent(offset,size/2);extent(offset+size/2,size-size/2)}else extent(offset,size)
                }
            },1))
            if(method==1)write(box("idat",payload))
        })
        val initial=meta(0);val start=ftyp.size+initial.size+8
        return ftyp+meta(start)+box("mdat",if(method==1)byteArrayOf()else payload)
    }
    private fun withSource(bytes:ByteArray=sample(),block:(File,File)->Unit){
        val root=kotlin.io.path.createTempDirectory("heic-primary-test-").toFile()
        try{val source=File(root,"source.heic").apply {writeBytes(bytes)};block(root,source)}finally{root.deleteRecursively()}
    }
    private fun rejected(bytes:ByteArray){withSource(bytes){root,source->
        try{HeicPrimaryInput.open(source).use {it.file};fail("Invalid/unsupported candidate admitted")}
        catch(_:HeicContainer.Rejected){}
        assertEquals(setOf("source.heic"),root.listFiles()!!.map {it.name}.toSet())
    }}
    @Test fun extractsPrimaryWithoutReencodingOrChangingOriginal(){withSource{root,source->
        val original=source.readBytes();var view:File?=null
        HeicPrimaryInput.open(source).use {p->
            view=p.file;assertNotEquals(source.canonicalPath,p.file.canonicalPath)
            assertEquals(96,p.info.displayWidth);assertEquals(64,p.info.displayHeight)
            assertArrayEquals(coded,p.file.readBytes().takeLast(coded.size).toByteArray())
            val text=p.file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(text.contains("tmap"));assertFalse(text.contains("altr"))
            assertEquals(p.info,HeicContainer.inspect(p.file))
        }
        assertFalse(view!!.exists());assertArrayEquals(original,source.readBytes())
        assertEquals(1,root.listFiles()!!.size)
    }}
    @Test fun readingInfoDoesNotMaterializeView(){withSource{root,source->
        HeicPrimaryInput.open(source).use {assertEquals(96,it.info.width);assertEquals(1,root.listFiles()!!.size)}
    }}
    @Test fun repeatedFileAccessReusesOneOwnedView(){withSource{root,source->
        HeicPrimaryInput.open(source).use {val a=it.file;assertEquals(a,it.file);assertEquals(2,root.listFiles()!!.size)}
    }}
    @Test fun supportsIdatConstructionWithoutChangingBytes(){withSource(sample(method=1)){_,source->
        HeicPrimaryInput.open(source).use {assertArrayEquals(coded,it.file.readBytes().takeLast(coded.size).toByteArray());assertEquals(96,it.info.width)}
    }}
    @Test fun supportsGridAndPreservesOrderedPayload(){for(method in 0..1)withSource(sample(grid=true,method=method)){_,source->
        HeicPrimaryInput.open(source).use {assertEquals(96,it.info.width);assertEquals(6144L,it.info.estimatedDecodePixels);assertArrayEquals(coded,it.file.readBytes().takeLast(coded.size).toByteArray())}
    }}
    @Test fun ipmaIdsIncreaseWithoutSortingGridTilesOrChangingTheirPropertiesAndPayloads(){
        // Android 17's CrabbyAvif rejects unsorted ipma even with strict mode disabled.
        // Deliberately keep dimg in the opposite order: its order defines tile placement.
        for(method in 0..1)withSource(descendingTileIdsSample(method)){_,source->
            val original=source.readBytes()
            val sourceProperties=propertyAssociations(original)
            val sourcePayloads=itemPayloads(original)
            val sourceMeta=children(original,topBox(original,"meta"),full=true)
            val sourceData=if(method==1)sourceMeta.single {it.type=="idat"}else topBox(original,"mdat")
            HeicPrimaryInput.open(source).use {prepared->
                val view=prepared.file.readBytes()
                val outputProperties=propertyAssociations(view)
                val ids=outputProperties.keys.toList()
                assertEquals("ipma must be sorted independently of payload order, method=$method",listOf(10,40,50),ids)
                assertTrue(ids.zipWithNext().all {(previous,current)->previous<current})
                for(id in ids)assertEquals("Properties, association order and essential bits for item $id",sourceProperties.getValue(id),outputProperties.getValue(id))
                assertEquals(listOf(40,10),derivedItems(original,50))
                assertEquals("Never sort the grid's dimg references",derivedItems(original,50),derivedItems(view,50))
                val outputPayloads=itemPayloads(view)
                assertEquals(listOf(50,40,10),sourcePayloads.keys.toList())
                assertEquals("Keep primary-first, dimg-ordered payload locations",sourcePayloads.keys.toList(),outputPayloads.keys.toList())
                assertEquals("Every selected item's exact bytes survive",sourcePayloads,outputPayloads)
                val outputData=topBox(view,"mdat")
                assertArrayEquals("The encoded payload sequence must not change",original.copyOfRange(sourceData.body,sourceData.end),view.copyOfRange(outputData.body,outputData.end))
                assertEquals(192,prepared.info.width)
                assertEquals(64,prepared.info.height)
                assertEquals(prepared.info,HeicContainer.inspect(prepared.file))
                assertArrayEquals("The source stays unchanged",original,source.readBytes())
            }
        }
    }

    private fun descendingTileIdsSample(method:Int):ByteArray {
        val gridPayload=byteArrayOf(0,0,0,1,0,192.toByte(),0,64)
        val highTile=coded.copyOf().also {it[it.lastIndex]=0x44}
        val lowTile=coded.copyOf().also {it[it.lastIndex]=0x11}
        val payloads=linkedMapOf(50 to gridPayload,40 to highTile,10 to lowTile)
        val payload=gridPayload+highTile+lowTile
        val ftyp=box("ftyp",data {writeBytes("heic");writeInt(0);writeBytes("mif1");writeBytes("tmap")})
        // Dropping unselected property 5 also exercises property-index remapping for pixi.
        val props=listOf(ispe(192,64),ispe(),hvcc(0),colr(13),colr(16),full("pixi",byteArrayOf(3,8,8,8)))
        val associations=linkedMapOf(10 to listOf(0x84,2,0x83),40 to listOf(0x83,0x82,4),50 to listOf(0x81,0x86,0x84),60 to listOf(5))
        fun meta(start:Int)=full("meta",data {
            write(full("hdlr",data {writeInt(0);writeBytes("pict");repeat(3){writeInt(0)};writeByte(0)}))
            write(full("pitm",data {writeShort(50)}))
            val entries=listOf(infe(10,"hvc1"),infe(20,"hvc1"),infe(40,"hvc1"),infe(50,"grid"),infe(60,"tmap"))
            write(full("iinf",data {writeShort(entries.size);entries.forEach {write(it)}}))
            write(box("iprp",box("ipco",data {props.forEach {write(it)}})+full("ipma",data {
                writeInt(associations.size)
                for((id,links)in associations){writeShort(id);writeByte(links.size);links.forEach {writeByte(it)}}
            })))
            write(full("iref",reference("dimg",60,listOf(50,20))+reference("dimg",50,listOf(40,10))))
            write(full("iloc",data {
                writeByte(0x44);writeByte(0);writeShort(payloads.size)
                var offset=if(method==1)0 else start
                for((id,bytes)in payloads){writeShort(id);writeShort(method);writeShort(0);writeShort(1);writeInt(offset);writeInt(bytes.size);offset+=bytes.size}
            },1))
            if(method==1)write(box("idat",payload))
        })
        val firstMeta=meta(0)
        return ftyp+meta(ftyp.size+firstMeta.size+8)+box("mdat",if(method==1)byteArrayOf()else payload)
    }

    private data class TestBox(val type:String,val start:Int,val body:Int,val end:Int)
    private fun number(bytes:ByteArray,at:Int,size:Int):Int {
        var value=0
        repeat(size){value=(value shl 8)or(bytes[at+it].toInt()and 255)}
        return value
    }
    private fun boxes(bytes:ByteArray,start:Int,end:Int):List<TestBox> {
        val out=ArrayList<TestBox>();var at=start
        while(at<end){
            val size=number(bytes,at,4);assertTrue("Complete synthetic box",size>=8&&at+size<=end)
            out.add(TestBox(String(bytes,at+4,4,Charsets.US_ASCII),at,at+8,at+size));at+=size
        }
        assertEquals(end,at);return out
    }
    private fun topBox(bytes:ByteArray,type:String)=boxes(bytes,0,bytes.size).single {it.type==type}
    private fun children(bytes:ByteArray,parent:TestBox,full:Boolean=false)=boxes(bytes,parent.body+if(full)4 else 0,parent.end)
    private fun propertyAssociations(bytes:ByteArray):Map<Int,List<Pair<Boolean,List<Byte>>>> {
        val meta=children(bytes,topBox(bytes,"meta"),full=true)
        val iprp=children(bytes,meta.single {it.type=="iprp"})
        val properties=children(bytes,iprp.single {it.type=="ipco"}).map {bytes.copyOfRange(it.start,it.end).toList()}
        val ipma=iprp.single {it.type=="ipma"};var at=ipma.body
        val version=number(bytes,at,1);val flags=number(bytes,at+1,3);val count=number(bytes,at+4,4);at+=8
        val result=linkedMapOf<Int,List<Pair<Boolean,List<Byte>>>>()
        repeat(count){
            val idSize=if(version==0)2 else 4;val id=number(bytes,at,idSize);at+=idSize
            val associationCount=number(bytes,at++,1)
            val links=List(associationCount){
                val width=if(flags and 1==0)1 else 2;val raw=number(bytes,at,width);at+=width
                val essentialMask=if(width==1)0x80 else 0x8000
                val index=raw and(essentialMask-1);assertTrue(index in 1..properties.size)
                (raw and essentialMask!=0)to properties[index-1]
            }
            assertNull("Unique ipma item",result.put(id,links))
        }
        assertEquals(ipma.end,at);return result
    }
    private fun derivedItems(bytes:ByteArray,primary:Int):List<Int> {
        val iref=children(bytes,topBox(bytes,"meta"),full=true).single {it.type=="iref"}
        assertEquals(0,number(bytes,iref.body,4))
        val dimg=children(bytes,iref,full=true).single {it.type=="dimg"&&number(bytes,it.body,2)==primary}
        val count=number(bytes,dimg.body+2,2)
        assertEquals(dimg.end,dimg.body+4+count*2)
        return List(count){number(bytes,dimg.body+4+it*2,2)}
    }
    private fun itemPayloads(bytes:ByteArray):Map<Int,List<Byte>> {
        val meta=children(bytes,topBox(bytes,"meta"),full=true)
        val iloc=meta.single {it.type=="iloc"};var at=iloc.body
        val version=number(bytes,at,1);assertTrue(version in 0..1)
        assertEquals(0x44,number(bytes,at+4,1));assertEquals(0,number(bytes,at+5,1))
        val count=number(bytes,at+6,2);at+=8
        val result=linkedMapOf<Int,List<Byte>>()
        repeat(count){
            val id=number(bytes,at,2);at+=2
            val method=if(version==1)number(bytes,at,2).also {at+=2}else 0
            assertEquals(0,number(bytes,at,2));at+=2
            assertEquals(1,number(bytes,at,2));at+=2
            val offset=number(bytes,at,4);val length=number(bytes,at+4,4);at+=8
            val absolute=offset+if(method==1)meta.single {it.type=="idat"}.body else 0
            assertNull("Unique iloc item",result.put(id,bytes.copyOfRange(absolute,absolute+length).toList()))
        }
        assertEquals(iloc.end,at);return result
    }
    @Test fun hdrPrimaryIsNotRelabeledSrgb(){rejected(sample(transfer=16))}
    @Test fun tenBitPrimaryIsStillRejected(){rejected(sample(depth=2))}
    @Test fun unknownEssentialPrimaryPropertyIsNotStripped(){rejected(sample(essentialUnknown=true))}
    @Test fun reversedOrMissingToneMapInputsAreRejected(){rejected(sample(tmapInputs=listOf(2,1)));rejected(sample(tmapInputs=listOf(1)))}
    @Test fun auxiliaryReferenceToPrimaryCannotBeDiscarded(){rejected(sample(extraRef=reference("auxl",2,listOf(1))))}
    @Test fun unknownPrimaryDependencyCannotBeDiscarded(){rejected(sample(extraRef=reference("abcd",1,listOf(2))))}
    @Test fun extentOutsideMdatIsRejected(){rejected(sample(offsetDelta=-1));rejected(sample(offsetDelta=1));rejected(sample(lengthDelta=1))}
    @Test fun zeroLengthExtentIsRejected(){rejected(sample(lengthDelta=-coded.size))}
    @Test fun externalReferenceAndItemOffsetConstructionAreRejected(){rejected(sample(external=1));rejected(sample(method=2))}
    @Test fun missingOrDuplicateSelectedLocationIsRejected(){rejected(sample(omitLocation=true));rejected(sample(duplicateLocation=true))}
    @Test fun gridPayloadMustMatchDimensionsAndTileCount(){rejected(sample(grid=true,gridWidth=95));rejected(sample(grid=true,gridColumns=2))}
    @Test fun cancellationDuringMaterializationLeavesNoView(){withSource{root,source->
        var cancel=false
        val prepared=HeicPrimaryInput.open(source){if(cancel)throw CancellationException("injected")}
        try{cancel=true;try{prepared.file;fail("Expected cancellation")}catch(_:CancellationException){}}
        finally{prepared.close()}
        assertEquals(setOf("source.heic"),root.listFiles()!!.map {it.name}.toSet())
    }}
    @Test fun unsupportedPrimaryTypeIsNotSilentlyReplaced(){rejected(sample(primaryType="iden"))}
    @Test fun ordinarySdrInputPassesThroughAndIsNeverDeleted(){
        val asset=listOf(File("src/androidTest/assets/heic/corners-sdr8.heic"),File("app/src/androidTest/assets/heic/corners-sdr8.heic")).first {it.isFile}
        withSource(asset.readBytes()){root,source->
            val original=source.readBytes()
            HeicPrimaryInput.open(source).use {assertEquals(source,it.file);assertEquals(1,root.listFiles()!!.size)}
            assertTrue(source.exists());assertArrayEquals(original,source.readBytes())
        }
    }
    @Test fun closedPreparedCannotRecreateTemporaryView(){withSource{root,source->
        val prepared=HeicPrimaryInput.open(source);prepared.close();prepared.close()
        try{prepared.file;fail("Closed input must not materialize")}catch(_:IllegalStateException){}
        assertEquals(1,root.listFiles()!!.size)
    }}
    @Test fun lowSpaceDoesNotBlockInfoButBlocksMaterializationWithoutLeaks(){withSource{root,source->
        val noSpace=object:File(source.path){ override fun getParentFile():File = object:File(root.path){override fun getUsableSpace():Long=0L} }
        HeicPrimaryInput.open(noSpace).use {p->
            assertEquals(96,p.info.width);assertEquals(1,root.listFiles()!!.size)
            try {p.file;fail("No disk must block materialization")}catch(_:HeicPrimaryInput.Storage){}
        }
        assertEquals(1,root.listFiles()!!.size)
    }}
    @Test fun cancellationAfterTemporaryCreationRemovesPartialFile(){withSource{root,source->
        val prepared=HeicPrimaryInput.open(source){if(root.listFiles()!!.any{it.name.startsWith("heic-primary-")})throw CancellationException("copy interrupted")}
        try {try{prepared.file;fail("Expected cancellation")}catch(_:CancellationException){}}finally{prepared.close()}
        assertEquals(setOf("source.heic"),root.listFiles()!!.map{it.name}.toSet())
    }}

    @Test fun extendedHevcBrandIsPreservedWithoutAdvertisingToneMap(){
        val bytes=sample();bytes[11]='x'.code.toByte()
        withSource(bytes){_,source->HeicPrimaryInput.open(source).use {
            val view=it.file.readBytes();assertEquals("heix",String(view,8,4,Charsets.US_ASCII))
            assertFalse(view.toString(Charsets.ISO_8859_1).contains("tmap"))
        }}
    }

    @Test fun supportsWideOffsetsAndMultipleExtentsWithoutChangingSelectedBytes(){
        for(method in 0..1)for(grid in listOf(false,true))withSource(sample(grid=grid,method=method,wideOffsets=true,multipleExtents=true)){_,source->
            HeicPrimaryInput.open(source).use {assertArrayEquals(coded,it.file.readBytes().takeLast(coded.size).toByteArray())}
        }
    }
    @Test fun overflowingBaseOffsetIsRejectedBeforeMaterialization(){rejected(sample(overflowBase=true))}
    @Test fun missingIdatCannotBeTreatedAsFileData(){
        val bytes=sample(method=1);val at=bytes.toString(Charsets.ISO_8859_1).indexOf("idat");assertTrue(at>0)
        "free".toByteArray().copyInto(bytes,at);rejected(bytes)
    }
    @Test fun unknownOffsetWidthsAreRejectedInsteadOfTruncated(){
        val bytes=sample();val at=bytes.toString(Charsets.ISO_8859_1).indexOf("iloc");bytes[at+8]=0x24;rejected(bytes)
    }

}
