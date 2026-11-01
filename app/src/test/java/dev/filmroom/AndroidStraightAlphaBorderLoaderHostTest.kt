package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidStraightAlphaBorderLoaderHostTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun spec(bytes:ByteArray)=StraightAlphaBorderLoader.Spec("loader_fixture","v1",sha(bytes),bytes.size)
    private fun reference(file:File):IntArray {
        val image=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,file)
        val i=Int::class.javaPrimitiveType!!
        return image.javaClass.getMethod("getRGB",i,i,i,i,IntArray::class.java,i,i).invoke(image,0,0,1520,1400,null,0,1520) as IntArray
    }
    @Test fun reviewedLengthHashAndMetadataFailClosedBeforeDecode(){
        val bytes=byteArrayOf(1,2,3,4)
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.Spec("fixture","v1","a".repeat(64),StraightAlphaBorderLoader.MAX_ENCODED_BYTES+1)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.Spec("fixture","v1","a".repeat(64),1,width=3040,height=2800)}
        var closed=false
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.load(spec(bytes).copy(sha256="0".repeat(64)),{
            object:ByteArrayInputStream(bytes){override fun close(){closed=true;super.close()}}
        })}
        assertTrue(closed)
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.load(spec(bytes),{ByteArrayInputStream(bytes+byteArrayOf(5))})}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.load(spec(bytes),{ByteArrayInputStream(bytes.copyOf(3))})}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.load(spec(bytes),{ByteArrayInputStream(bytes)})}
    }

    @Test fun validHashWrongPngDimensionsRejectBeforeFullBitmapDecode(){
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val encoded=java.io.ByteArrayOutputStream()
        try{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,encoded))}finally{bitmap.recycle()}
        val bytes=encoded.toByteArray();var decoded:Bitmap?=null
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.decodeStraight(spec(bytes),{ByteArrayInputStream(bytes)},onDecoded={decoded=it})}
        assertNull(decoded)
    }

    @Test fun cancellationDuringReadClosesStreamAndDoesNotDecode(){
        val bytes=ByteArray(100_000){(it%251).toByte()};var closed=false;var read=false
        assertThrows(InterruptedException::class.java){StraightAlphaBorderLoader.load(spec(bytes),{
            object:FilterInputStream(ByteArrayInputStream(bytes)){
                override fun read(b:ByteArray,off:Int,len:Int):Int{read=true;return super.read(b,off,len)}
                override fun close(){closed=true;super.close()}
            }
        },{if(read)throw InterruptedException("test")})}
        assertTrue(read);assertTrue(closed)
    }

    @Test fun researchPngAndroidNativeGraphicsStraightDecodeMatchesHostReferenceAndRecyclesOnCancel(){
        assumeTrue("Set FILMROOM_RGBA_RESEARCH=1 for the two external research PNGs",System.getenv("FILMROOM_RGBA_RESEARCH")=="1")
        val root=File(System.getenv("FILMROOM_RGBA_RESEARCH_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-new3-v1/assets-only")
        val hashes=mapOf("portra800" to "3ff71f3f1bacb8b453f1fb910ef7e976fcd57c7aaaff5ad86443491502b0b5e5","kodachrome64" to "f0c051655533c3e79d0140234d54f09ff42df0b4f1f2835b8f5cf7b80bd6c41f")
        for((name,hash) in hashes){
            val file=File(root,"$name-fixed135-36x24.png");assertTrue(file.isFile)
            val spec=StraightAlphaBorderLoader.Spec("research_$name","review-v1",hash,file.length().toInt())
            var decoded:Bitmap?=null
            val pixels=StraightAlphaBorderLoader.decodeStraight(spec,{file.inputStream()},onDecoded={decoded=it;assertFalse(it.isPremultiplied)})
            assertTrue(decoded!!.isRecycled)
            assertEquals(191,pixels[80*1520+72] ushr 24)
            assertEquals(if(name=="portra800")0x00c56838 else 0x000e0b07,pixels[80*1520+72]and 0x00ffffff)
            assertEquals(0,pixels[136*1520+95]);assertEquals(0,pixels[220*1520+40])
            assertArrayEquals("Whole straight decoded pixel array $name",reference(file),pixels)
            val atlas=StraightAlphaBorderLoader.load(spec,{file.inputStream()})
            atlas.validate(BorderSnapshot.fixed135(spec.stock,spec.version,96,64))
            decoded=null
            assertThrows(InterruptedException::class.java){StraightAlphaBorderLoader.decodeStraight(spec,{file.inputStream()},
                checkActive={if(decoded!=null)throw InterruptedException("after allocation")},onDecoded={decoded=it})}
            assertTrue("Decoded bitmap recycled after cancellation",decoded!!.isRecycled)
        }
    }
}
