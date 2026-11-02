package dev.filmroom.engine

import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Opt-in isolated JVM: actual Kotlin adapter -> actual owned JNI -> original scientific pipeline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class RealOwnedDetailHostTest {
    @Test fun actualOwnedJniBuildTileIdentityAndRetirement()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        val data=System.getenv("FILMROOM_DETAIL_DATA") ?: error("FILMROOM_DETAIL_DATA required")
        val root=Files.createTempDirectory("real-owned-detail").toFile().canonicalFile
        try {
            File(data).copyRecursively(File(root,"files/engine-data"))
            val original=File(root,"original.dng").apply{writeText("immutable original marker")}
            val linear=File(root,"source.f32le")
            val b=ByteBuffer.allocate(32*24*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(32*24){i->b.putFloat(if(i==0)-0.05f else 0.1f+(i%32)/64f);b.putFloat(0.3f);b.putFloat(if(i==1)2f else 0.2f)}
            linear.writeBytes(b.array())
            val photo=PhotoSource(original,"source.dng","image/x-adobe-dng",32,24,original.length(),linearFile=linear)
            val provider=OwnedDetailProvider(root,NativeDetailBridge,{NativeMemorySnapshot(8L*1024*1024*1024,0,false)})
            val parameters=EditorState(grain=0f,halation=0f,scatterAmount=0f,dirAmount=0f,grainBlur=0f,grainDyeCloud=0f)
            val session=provider.open(photo,parameters,preferRegion=true)
            try {
                val tile=session.tile(0,0)
                assertEquals(DetailRect(0,0,32,24),tile.core);assertEquals(tile.core,tile.sample)
                assertEquals(session.key,tile.key);assertTrue(tile.cpuFallback)
                assertEquals(32,tile.bitmap.width);assertEquals(24,tile.bitmap.height)
                val region=session.region(DetailRect(5,6,10,8)){false}
                assertEquals(DetailRect(4,5,12,10),region.sample)
                for(y in 0 until region.sample.height)for(x in 0 until region.sample.width)
                    assertEquals(tile.bitmap.getPixel(x+4,y+5),region.bitmap.getPixel(x,y))
                region.bitmap.recycle();tile.bitmap.recycle()
            } finally {session.close()}
            withTimeout(5000){while(File(root,"cache/detail-preview/sources").listFiles().orEmpty().isNotEmpty())delay(10)}
            assertTrue(original.exists());assertTrue(linear.exists())
            assertTrue(File(root,"cache/detail-preview/sessions").listFiles().orEmpty().isEmpty())
        }finally{root.deleteRecursively()}
    }
}
