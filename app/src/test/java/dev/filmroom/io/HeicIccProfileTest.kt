package dev.filmroom.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** All profiles are generated independently from public colorimetry, never copied from a photo. */
class HeicIccProfileTest {
    @Test fun roundedSdrCurveIsPreservedAndNeverReturnedAsStandardEnumOnly() {
        val bytes=profile(rounded=true)
        val actual=HeicIccProfile.inspectProfile(bytes)
        assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,actual.identity)
        assertEquals(2556/65536.0,actual.transfer.d,0.0)
        assertEquals(5046/65536.0,actual.transfer.c,0.0)
        reject(bytes) // Legacy enum-only interface may not relabel custom TRC as standard P3.
        assertEquals(actual,HeicIccProfile.inspectProfile(bytes.copyOf()))
        assertFalse(HeicIccProfile.equivalent(actual,HeicIccProfile.Profile.standard(actual.identity)))
        val matrix=actual.toXyzD50.values();matrix[0]=9.0
        assertTrue(actual.toXyzD50.values()[0]<1.0)
        val tinyChange=bytes.copyOf();val at=tagOffset(tinyChange,"rTRC")
        putInt(tinyChange,at+12,getInt(tinyChange,at+12)+1)
        try{HeicIccProfile.inspectProfile(tinyChange);fail("Custom curve tuple is exact, not a widened tolerance")}catch(e:HeicIccProfile.Rejected){assertEquals(HeicIccProfile.Failure.UNSUPPORTED,e.failure)}
        val mismatch=profile(rounded=true,shareTrc=false)
        putInt(mismatch,tagOffset(mismatch,"gTRC")+28,fixed(.04045))
        try{HeicIccProfile.inspectProfile(mismatch);fail("RGB curves must agree")}catch(_:HeicIccProfile.Rejected){}
    }
    @Test fun fixedFpxrTrailerOnlyUsesDeclaredIccBounds() {
        val bytes=profile(rounded=true)
        val suffix=byteArrayOf(0x46,0x50,0x58,0x52,0,0,0,0)
        assertEquals(HeicIccProfile.inspectProfile(bytes),HeicIccProfile.inspectProfile(bytes+suffix))
        val candidates=mutableListOf(bytes+suffix+byteArrayOf(0),bytes+suffix.copyOf(7),bytes+ByteArray(8))
        for(i in suffix.indices){val wrong=suffix.copyOf();wrong[i]=(wrong[i].toInt() xor 1).toByte();candidates+=bytes+wrong}
        val outOfBounds=bytes+suffix;putInt(outOfBounds,136,bytes.size);candidates+=outOfBounds
        val short=bytes+suffix;putInt(short,0,128);candidates+=short
        val long=bytes+suffix;putInt(long,0,Int.MAX_VALUE);candidates+=long
        for(b in candidates)try{HeicIccProfile.inspectProfile(b);fail("Malformed wrapper accepted")}catch(e:HeicIccProfile.Rejected){assertEquals(HeicIccProfile.Failure.INVALID,e.failure)}
    }
    @Test fun allChannelsMustShareTheActualTransferForAndroidRgbContract() {
        val bytes=profile(shareTrc=false)
        val at=tagOffset(bytes,"gTRC")
        putInt(bytes,at+12,getInt(bytes,at+12)+1)
        // Individually near-standard curves are not one actual RGB transfer descriptor.
        try{HeicIccProfile.inspectProfile(bytes);fail("Per-channel transfer mismatch accepted")}
        catch(e:HeicIccProfile.Rejected){assertEquals(HeicIccProfile.Failure.UNSUPPORTED,e.failure)}
    }
    @Test fun standardP3AndSrgbMatricesNotDescriptionsEstablishIdentity() {
        assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicIccProfile.inspect(profile()))
        assertEquals(HeicIccProfile.ColorIdentity.SRGB,HeicIccProfile.inspect(profile(p3=false)))
        assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicIccProfile.inspect(profile(description="Definitely not a color space name")))
        assertEquals(HeicIccProfile.ColorIdentity.SRGB,HeicIccProfile.inspect(profile(p3=false,description="Display P3")))
    }
    @Test fun d50OrVerifiedLegacyD65MediaWhiteAndSharedOrSeparateTrcsAreAccepted() {
        for(nativeWhite in listOf(false,true))for(shared in listOf(false,true))
            assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicIccProfile.inspect(profile(nativeWhite=nativeWhite,shareTrc=shared)))
    }
    @Test fun publicMatricesAndTransferContractAreColumnMajorAndDefensivelyCopied() {
        val identity=HeicIccProfile.inspect(profile())
        val matrix=identity.expectedToXyzD50
        assertEquals(.5151187,matrix[0],1e-7);assertEquals(.24118917,matrix[1],1e-7)
        assertEquals(.29197783,matrix[3],1e-7)
        matrix[0]=20.0;assertEquals(.5151187,identity.expectedToXyzD50[0],1e-7)
        assertEquals(2.4,identity.expectedTransfer.g,0.0)
        assertEquals(1.0/12.92,identity.expectedTransfer.c,0.0)
        assertEquals(.04045,identity.expectedTransfer.d,0.0)
        assertEquals(0.0,identity.expectedTransfer.e,0.0);assertEquals(0.0,identity.expectedTransfer.f,0.0)
    }
    @Test fun oneFixedPointQuantumIsAcceptedButAlteredCurveAndHdrAreRejected() {
        val one=profile();val trc=tagOffset(one,"rTRC");putInt(one,trc+12,getInt(one,trc+12)+1)
        assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicIccProfile.inspect(one))
        val wrong=profile();val at=tagOffset(wrong,"rTRC");putInt(wrong,at+12,fixed(2.2));reject(wrong)
        val pq=profile();putInt(pq,tagOffset(pq,"rTRC")+12,fixed(78.84375));reject(pq)
        val type=profile();type[tagOffset(type,"rTRC")+9]=4;reject(type)
        val mismatch=profile(shareTrc=false);putInt(mismatch,tagOffset(mismatch,"gTRC")+12,fixed(2.2));reject(mismatch)
    }
    @Test fun unknownMatricesWhitePointsAndAdaptationCannotPassByName() {
        val matrix=profile();putInt(matrix,tagOffset(matrix,"rXYZ")+8,fixed(.6));reject(matrix)
        val wrongWhite=profile();putInt(wrongWhite,tagOffset(wrongWhite,"wtpt")+8,fixed(.8));reject(wrongWhite)
        val noAdapt=profile();putInt(noAdapt,tagOffset(noAdapt,"chad")+8,fixed(1.0));reject(noAdapt)
        val transpose=profile();val at=tagOffset(transpose,"chad");val a=getInt(transpose,at+12);putInt(transpose,at+12,getInt(transpose,at+20));putInt(transpose,at+20,a);reject(transpose)
        val illuminant=profile();putInt(illuminant,68,fixed(.95045));reject(illuminant)
    }
    @Test fun lutAndPrivateTransformTagsAreNeverIgnored() {
        for(signature in listOf("A2B0","B2A0","D2B0","B2D0","vcgt","lumi","chrm","abcd"))
            reject(profile(extra=signature to typed("mAB ",ByteArray(24))))
    }
    @Test fun malformedHeaderAndUnsupportedClassVersionAreRejected() {
        val magic=profile();magic[36]=0;reject(magic,HeicIccProfile.Failure.INVALID)
        val size=profile();putInt(size,0,size.size+4);reject(size,HeicIccProfile.Failure.INVALID)
        val model=profile();"CMYK".toByteArray().copyInto(model,16);reject(model)
        val pcs=profile();"Lab ".toByteArray().copyInto(pcs,20);reject(pcs)
        val clazz=profile();"link".toByteArray().copyInto(clazz,12);reject(clazz)
        val version=profile();version[8]=2;reject(version)
        val reserved=profile();reserved[100]=1;reject(reserved,HeicIccProfile.Failure.INVALID)
        val intent=profile();putInt(intent,64,4);reject(intent)
    }
    @Test fun duplicatesOverflowHeaderOverlapMisalignmentAndPartialAliasFailClosed() {
        val duplicate=profile();"rXYZ".toByteArray().copyInto(duplicate,144);reject(duplicate,HeicIccProfile.Failure.INVALID)
        val outside=profile();putInt(outside,136,Int.MAX_VALUE);reject(outside,HeicIccProfile.Failure.INVALID)
        val huge=profile();putInt(huge,140,-1);reject(huge,HeicIccProfile.Failure.INVALID)
        val header=profile();putInt(header,136,0);reject(header,HeicIccProfile.Failure.INVALID)
        val unaligned=profile();putInt(unaligned,136,getInt(unaligned,136)+1);reject(unaligned,HeicIccProfile.Failure.INVALID)
        val overlap=profile();putInt(overlap,148,getInt(overlap,136)+4);reject(overlap,HeicIccProfile.Failure.INVALID)
        val alias=profile();putInt(alias,148,getInt(alias,136));reject(alias,HeicIccProfile.Failure.INVALID)
        val count=profile();putInt(count,128,129);reject(count,HeicIccProfile.Failure.LIMIT)
        reject(ByteArray(HeicIccProfile.MAX_PROFILE_BYTES+1),HeicIccProfile.Failure.LIMIT)
    }
    @Test fun malformedTagPayloadCannotBeReadThroughNextTag() {
        val reserved=profile();putInt(reserved,tagOffset(reserved,"rXYZ")+4,1);reject(reserved,HeicIccProfile.Failure.INVALID)
        val shorter=profile();putInt(shorter,140,16);reject(shorter,HeicIccProfile.Failure.INVALID)
        val desc=profile();putInt(desc,tagOffset(desc,"desc")+8,Int.MAX_VALUE);reject(desc,HeicIccProfile.Failure.INVALID)
        val missing=profile();"aXYZ".toByteArray().copyInto(missing,132);reject(missing)
    }
    @Test fun truncatedAndFuzzedProfilesNeverThrowBoundsOrAllocationErrors() {
        val original=profile()
        for(n in original.indices)reject(original.copyOf(n),HeicIccProfile.Failure.INVALID)
        val random=Random(194)
        repeat(200) {
            val bytes=ByteArray(random.nextInt(1,2048));random.nextBytes(bytes)
            try{HeicIccProfile.inspect(bytes);fail("Random profile admitted")}catch(_:HeicIccProfile.Rejected){}
        }
    }
    @Test fun independentlyGeneratedPublicLcmsFixtureHasExpectedIdentity() {
        val fixture=listOf(java.io.File("src/androidTest/assets/heic/original-display-p3.icc"),
            java.io.File("app/src/androidTest/assets/heic/original-display-p3.icc"))
            .firstOrNull{it.isFile} ?: error("Independently generated ICC fixture missing")
        assertEquals(HeicIccProfile.ColorIdentity.DISPLAY_P3,HeicIccProfile.inspect(fixture.readBytes()))
    }

    private fun reject(bytes:ByteArray,failure:HeicIccProfile.Failure=HeicIccProfile.Failure.UNSUPPORTED) {
        try{HeicIccProfile.inspect(bytes);fail("Expected $failure")}catch(e:HeicIccProfile.Rejected){assertEquals(failure,e.failure)}
    }
    private fun fixed(value:Double)=(value*65536).roundToInt()
    private fun getInt(b:ByteArray,at:Int)=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt(at)
    private fun putInt(b:ByteArray,at:Int,value:Int){ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).putInt(at,value)}
    private fun data(block:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().also{DataOutputStream(it).use{stream->stream.block()}}.toByteArray()
    private fun typed(type:String,body:ByteArray)=data{writeBytes(type);writeInt(0);write(body)}
    private fun xyz(x:Double,y:Double,z:Double)=typed("XYZ ",data{for(v in listOf(x,y,z))writeInt(fixed(v))})
    private fun tagOffset(b:ByteArray,name:String):Int {
        for(i in 0 until getInt(b,128)){val at=132+i*12;if(String(b,at,4,Charsets.US_ASCII)==name)return getInt(b,at+4)}
        error("Tag missing")
    }
    private fun profile(p3:Boolean=true,nativeWhite:Boolean=false,shareTrc:Boolean=true,description:String="Independent SDR fixture",extra:Pair<String,ByteArray>?=null,rounded:Boolean=false):ByteArray {
        // Independent rounded standard D50 colorants. Not imported from the production constants.
        val matrix=if(p3) arrayOf(doubleArrayOf(.515119,.241189,-.001050),doubleArrayOf(.291978,.692244,.041879),doubleArrayOf(.157103,.066567,.784071))
            else arrayOf(doubleArrayOf(.436041,.222485,.013920),doubleArrayOf(.385113,.716905,.097067),doubleArrayOf(.143046,.060610,.713913))
        val trc=typed("para",data{writeShort(3);writeShort(0);if(rounded)for(v in listOf(157286,62128,3408,5046,2556))writeInt(v)else for(v in listOf(2.4,1.0/1.055,.055/1.055,1.0/12.92,.04045))writeInt(fixed(v))})
        val desc=typed("desc",data{val ascii=(description+"\u0000").toByteArray(Charsets.US_ASCII);writeInt(ascii.size);write(ascii);writeInt(0);writeInt(0);writeShort(0);writeByte(0);write(ByteArray(67))})
        val tags=mutableListOf("rXYZ" to xyz(*matrix[0]),"gXYZ" to xyz(*matrix[1]),"bXYZ" to xyz(*matrix[2]),
            "rTRC" to trc,"gTRC" to trc,"bTRC" to trc,
            "wtpt" to if(nativeWhite)xyz(.950455,1.0,1.089058)else xyz(.9642,1.0,.8249),
            "chad" to typed("sf32",data{for(v in listOf(1.047886,.022919,-.050216,.029582,.990484,-.017079,-.009252,.015073,.751678))writeInt(fixed(v))}),"desc" to desc)
        extra?.let{tags.add(it)}
        val tableEnd=132+tags.size*12
        val payload=ByteArrayOutputStream();val offsets=mutableListOf<Int>();var trcOffset:Int?=null
        for((name,bytes)in tags){
            if(shareTrc&&name.endsWith("TRC")&&trcOffset!=null){offsets.add(trcOffset);continue}
            while((tableEnd+payload.size())%4!=0)payload.write(0)
            val offset=tableEnd+payload.size();offsets.add(offset)
            if(name=="rTRC")trcOffset=offset
            payload.write(bytes)
        }
        val header=ByteArray(128);putInt(header,0,tableEnd+payload.size());putInt(header,8,0x04300000)
        "mntr".toByteArray().copyInto(header,12);"RGB ".toByteArray().copyInto(header,16);"XYZ ".toByteArray().copyInto(header,20);"acsp".toByteArray().copyInto(header,36)
        for((i,v)in listOf(.9642,1.0,.8249).withIndex())putInt(header,68+i*4,fixed(v))
        return data{write(header);writeInt(tags.size);tags.forEachIndexed{i,(name,bytes)->writeBytes(name);writeInt(offsets[i]);writeInt(bytes.size)};write(payload.toByteArray())}
    }
    private fun xyz(vararg v:Double)=xyz(v[0],v[1],v[2])
}
