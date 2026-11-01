package dev.filmroom.io

import java.io.IOException
import kotlin.math.abs

/**
 * Narrow ICC v4 RGB matrix/TRC admission, not a general ICC engine. Only independently
 * specified sRGB/Display P3 SDR transforms are accepted. Descriptions, manufacturer names,
 * profile IDs and hashes never establish identity. The platform must separately prove it
 * recognized this color space BEFORE a target-sRGB request, otherwise importing is unsafe.
 *
 * Primaries/curve: https://registry.color.org/rgb-registry/{srgb,displayp3}
 * Colorants below: standard D65 primaries, Bradford adaptation to ICC PCS D50
 * (0.9642,1,0.8249), rounded only when represented as ICC s15Fixed16 numbers.
 */
internal object HeicIccProfile {
    const val MAX_PROFILE_BYTES = 64 * 1024
    const val MAX_TAGS = 128
    const val PROFILE_MATRIX_TOLERANCE = 5.0 / 65536.0
    const val TRANSFER_TOLERANCE = 2.0 / 65536.0
    /** Android's D50 xy definition differs slightly from ICC's rounded D50 XYZ definition. */
    const val PLATFORM_MATRIX_TOLERANCE = 0.0006
    enum class Failure { INVALID, UNSUPPORTED, LIMIT }
    class Rejected(val failure: Failure) : IOException(when (failure) {
        Failure.INVALID -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_19e0ce29db17)
        Failure.UNSUPPORTED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_da9f421ae9ad)
        Failure.LIMIT -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9cd4df0f9933)
    })
    data class Transfer(val g:Double, val a:Double, val b:Double, val c:Double, val d:Double, val e:Double=0.0, val f:Double=0.0)
    enum class ColorIdentity {
        SRGB, DISPLAY_P3;
        /** Column-major RGB -> D50 XYZ, compatible with Android ColorSpace.Rgb.transform. */
        val expectedToXyzD50: DoubleArray get() = when(this) {
            SRGB -> doubleArrayOf(.436041251616051,.22248454022947742,.013920187471375373,
                .3851129107981555,.7169050786084572,.09706723869712398,
                .1430458375857936,.06061038116206524,.7139125738315006)
            DISPLAY_P3 -> doubleArrayOf(.5151187009280961,.24118916783740302,-.0010504527079767036,
                .29197783204137456,.6922440506378194,.04187912006015113,
                .1571034670305294,.06656678152477759,.7840713326478255)
        }
        val expectedTransfer:Transfer get()=Transfer(2.4,1.0/1.055,.055/1.055,1.0/12.92,.04045)
    }
    private val d50=doubleArrayOf(.9642,1.0,.8249)
    private val d65=doubleArrayOf(.3127/.329,1.0,(1-.3127-.329)/.329)
    // ICC sf32 matrices are row-major (unlike Android's RGB transform above).
    private val bradford=doubleArrayOf(1.0478860032225505,.02291876517477955,-.050216095311733044,
        .029581782498003462,.9904835184905485,-.017078707704482696,
        -.009251880839208845,.015072607487031322,.7516781336176034)
    private fun invalid():Nothing=throw Rejected(Failure.INVALID)
    private fun unsupported():Nothing=throw Rejected(Failure.UNSUPPORTED)
    private fun limit():Nothing=throw Rejected(Failure.LIMIT)
    private fun near(actual:DoubleArray,expected:DoubleArray,tolerance:Double)=
        actual.size==expected.size && actual.indices.all { actual[it].isFinite() && abs(actual[it]-expected[it])<=tolerance }
    private data class Tag(val name:String,val offset:Int,val size:Int)

    fun inspect(bytes:ByteArray):ColorIdentity {
        if(bytes.size>MAX_PROFILE_BYTES)limit()
        if(bytes.size<132)invalid()
        val r=Reader(bytes)
        if(r.u32(0)!=bytes.size.toLong() || r.signature(36)!="acsp")invalid()
        if(r.u8(8)!=4 || r.u8(9) ushr 4>4 || r.u16(10)!=0)unsupported()
        if(r.signature(12)!="mntr" || r.signature(16)!="RGB " || r.signature(20)!="XYZ ")unsupported()
        if(r.u32(44) and 0xfffffffcL != 0L || r.u32(56)!=0L || r.u32(60)!=0L || r.u32(64)>3L)unsupported()
        if(!near(r.fixedArray(68,3),d50,2.0/65536))unsupported()
        if((100 until 128).any{r.u8(it)!=0})invalid()
        val count=r.u32(128)
        if(count>MAX_TAGS)limit()
        if(count<8)invalid()
        val tableEnd=132+count.toInt()*12
        if(tableEnd>bytes.size)invalid()
        val tags=LinkedHashMap<String,Tag>()
        repeat(count.toInt()) { index ->
            val at=132+index*12
            val name=r.signature(at);val offset=r.u32(at+4);val size=r.u32(at+8)
            if(offset<tableEnd || offset%4!=0L || size<8 || offset>bytes.size || size>bytes.size-offset)invalid()
            if(tags.put(name,Tag(name,offset.toInt(),size.toInt()))!=null)invalid()
        }
        val allowed=setOf("rXYZ","gXYZ","bXYZ","rTRC","gTRC","bTRC","wtpt","chad","desc","cprt")
        if(tags.keys.any{it !in allowed})unsupported() // includes A2B/B2A/D2B/B2D/LUT/vcgt/private transforms
        val list=tags.values.toList()
        for(i in list.indices)for(j in 0 until i) {
            val a=list[i];val b=list[j]
            if(a.offset < b.offset+b.size && b.offset < a.offset+a.size) {
                // ICC legally shares exactly the same TRC block among channels; partial aliases
                // and aliases between semantically different tags are never accepted.
                if(a.offset!=b.offset || a.size!=b.size || a.name !in setOf("rTRC","gTRC","bTRC") || b.name !in setOf("rTRC","gTRC","bTRC"))invalid()
            }
        }
        fun tag(name:String)=tags[name] ?: invalid()
        fun typed(t:Tag,type:String,size:Int?=null) {
            if(r.signature(t.offset)!=type || r.u32(t.offset+4)!=0L)invalid()
            if(size!=null && t.size!=size)invalid()
        }
        fun xyz(name:String):DoubleArray { val t=tag(name);typed(t,"XYZ ",20);return r.fixedArray(t.offset+8,3) }
        val matrix=xyz("rXYZ")+xyz("gXYZ")+xyz("bXYZ")
        val identity=ColorIdentity.entries.singleOrNull{near(matrix,it.expectedToXyzD50,PROFILE_MATRIX_TOLERANCE)} ?: unsupported()
        val expected=identity.expectedTransfer
        val expectedTrc=doubleArrayOf(expected.g,expected.a,expected.b,expected.c,expected.d)
        for(name in listOf("rTRC","gTRC","bTRC")) {
            val t=tag(name);typed(t,"para",32)
            if(r.u16(t.offset+8)!=3 || r.u16(t.offset+10)!=0)unsupported()
            if(!near(r.fixedArray(t.offset+12,5),expectedTrc,TRANSFER_TOLERANCE))unsupported()
        }
        val chad=tag("chad");typed(chad,"sf32",44)
        val adaptation=r.fixedArray(chad.offset+8,9)
        if(!near(adaptation,bradford,PROFILE_MATRIX_TOLERANCE))unsupported()
        // ICC v4 specifies wtpt D50. Some otherwise consistent matrix display profiles retain
        // native D65 in wtpt; accept ONLY this known convention with independently verified
        // D50 colorants + D65->D50 Bradford chad, never an arbitrary media white or adaptation.
        val white=xyz("wtpt")
        if(!near(white,d50,2.0/65536) && !near(white,d65,2.0/65536))unsupported()
        val adapted=DoubleArray(3){row->(0..2).sumOf { col->adaptation[row*3+col]*d65[col] }}
        if(!near(adapted,d50,PROFILE_MATRIX_TOLERANCE))unsupported()
        val matrixWhite=DoubleArray(3){row->matrix[row]+matrix[3+row]+matrix[6+row]}
        if(!near(matrixWhite,d50,PROFILE_MATRIX_TOLERANCE*3))unsupported()
        for(name in listOf("desc","cprt"))tags[name]?.let { t ->
            if(r.u32(t.offset+4)!=0L)invalid()
            when(r.signature(t.offset)) {
                "text" -> { if(name!="cprt" || t.size<9 || r.u8(t.offset+t.size-1)!=0)invalid() }
                "desc" -> {
                    if(name!="desc" || t.size<90)invalid()
                    val chars=r.u32(t.offset+8)
                    if(chars<1 || chars>t.size-90L)invalid()
                    val asciiEnd=t.offset+12+chars.toInt()
                    if(r.u8(asciiEnd-1)!=0)invalid()
                    val unicode=r.u32(asciiEnd+4)
                    val afterUnicode=asciiEnd.toLong()+8+unicode*2
                    if(afterUnicode+70>t.offset+t.size || unicode>MAX_PROFILE_BYTES/2)invalid()
                    if(r.u8(afterUnicode.toInt()+2)>67)invalid()
                }
                "mluc" -> {
                    if(t.size<16)invalid()
                    val records=r.u32(t.offset+8);val entrySize=r.u32(t.offset+12)
                    if(records>MAX_TAGS || entrySize!=12L || 16+records*12>t.size)invalid()
                    repeat(records.toInt()) { n ->
                        val at=t.offset+16+n*12;val size=r.u32(at+4);val offset=r.u32(at+8)
                        if(size%2!=0L || offset%2!=0L || offset<16+records*12 || offset>t.size || size>t.size-offset)invalid()
                    }
                }
                else -> unsupported()
            }
        }
        return identity
    }
    private class Reader(private val b:ByteArray) {
        fun u8(at:Int):Int { if(at !in b.indices)invalid();return b[at].toInt() and 255 }
        fun u16(at:Int)=(u8(at) shl 8) or u8(at+1)
        fun u32(at:Int):Long=(u16(at).toLong() shl 16) or u16(at+2).toLong()
        fun signature(at:Int)=buildString{repeat(4){append(u8(at+it).toChar())}}
        fun fixedArray(at:Int,count:Int)=DoubleArray(count){u32(at+it*4).toInt().toDouble()/65536.0}
    }
}
