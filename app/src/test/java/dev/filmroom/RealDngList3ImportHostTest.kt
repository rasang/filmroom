package dev.filmroom

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.io.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real production PhotoRepository/PhotoCodecs/JNI RAW decode. Android content
 * resolver, memory and storage services are host fixtures, not device execution.
 * Private actual RAW is optional via environment, never a source fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class RealDngList3ImportHostTest {
    private fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input-> val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)} }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun cameraList3ImportPreservesFullSpoolAndFloatPreviewAcrossReopen()=runBlocking {
        assumeTrue("Opt-in Linux native codec",System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertTrue("Must load real codec JNI",NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val cases=mutableListOf<Pair<ByteArray,Boolean>>()
        cases+=checkNotNull(javaClass.getResourceAsStream("/raw/list3-gainmap.dng")).use{it.readBytes()} to false
        val privateFile=System.getenv("FILMROOM_DJI_DNG")?.let(::File)
        val sourceHash=privateFile?.let(::sha)
        privateFile?.let{cases+=it.readBytes() to true}
        for((original,actual) in cases){
            val uri=Uri.parse("content://filmroom.list3/input")
            shadowOf(app.contentResolver).registerInputStream(uri,original.inputStream())
            val repository=PhotoRepository(app) // no fake codec/backend
            val loaded=repository.import(uri,256)
            val spool=checkNotNull(loaded.source.linearFile)
            try {
                val width=loaded.source.originalWidth;val height=loaded.source.originalHeight
                if(actual){assertEquals(3648,width);assertEquals(2736,height)}else{assertEquals(64,width);assertEquals(384,height)}
                assertEquals(width.toLong()*height*12,spool.length())
                assertArrayEquals(original,loaded.source.localFile.readBytes())
                val spoolHash=sha(spool)
                if(actual) System.getenv("FILMROOM_DJI_EXPECTED_F32_SHA256")?.let{assertEquals(it,spoolHash)}
                val floatPreview=checkNotNull(loaded.source.linearPreview(256))
                val cursor=floatPreview.buffer().asFloatBuffer();var maximum=Float.NEGATIVE_INFINITY
                while(cursor.hasRemaining()){val f=cursor.get();assertTrue(f.isFinite());maximum=maxOf(maximum,f)}
                if(actual)assertTrue("RAW effects retain HDR instead of reusing clipped display Bitmap",maximum>1f)
                assertTrue(floatPreview.buffer().isReadOnly)
                assertEquals(loaded.preview.width,floatPreview.width);assertEquals(loaded.preview.height,floatPreview.height)
                // Independent four-sample first output-pixel oracle in full RAW spool.
                val fx=(0.5*width/floatPreview.width-0.5).coerceIn(0.0,(width-1).toDouble())
                val fy=(0.5*height/floatPreview.height-0.5).coerceIn(0.0,(height-1).toDouble())
                val x0=fx.toInt();val y0=fy.toInt();val tx=(fx-x0).toFloat();val ty=(fy-y0).toFloat()
                RandomAccessFile(spool,"r").use{input->
                    fun at(x:Int,y:Int,c:Int):Float{input.seek((y.toLong()*width+x)*12+c*4);val bytes=ByteArray(4);input.readFully(bytes);return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float}
                    val preview=floatPreview.buffer().asFloatBuffer()
                    repeat(3){c->val top=at(x0,y0,c)*(1-tx)+at((x0+1).coerceAtMost(width-1),y0,c)*tx;val bottom=at(x0,(y0+1).coerceAtMost(height-1),c)*(1-tx)+at((x0+1).coerceAtMost(width-1),(y0+1).coerceAtMost(height-1),c)*tx
                        assertEquals(top*(1-ty)+bottom*ty,preview.get(c),0.000001f)}
                }
                val stamp=spool.lastModified()
                val reopened=repository.reopen(loaded.source.cacheKey,"RAW",128,loaded.source.rawCacheKey)
                try{assertEquals(spoolHash,sha(checkNotNull(reopened.source.linearFile)));assertEquals(stamp,spool.lastModified());assertEquals(width,reopened.source.originalWidth);assertEquals(height,reopened.source.originalHeight)}finally{reopened.preview.recycle()}
                assertArrayEquals(original,loaded.source.localFile.readBytes())
            } finally {loaded.preview.recycle();repository.discard(loaded.source)}
            assertFalse(spool.exists());assertFalse(loaded.source.localFile.exists())
        }
        privateFile?.let{assertEquals(sourceHash,sha(it))}
        Unit
    }
}
