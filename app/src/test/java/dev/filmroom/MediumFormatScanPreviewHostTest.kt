package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.filmroom.border.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatScanPreviewHostTest {
    @Test fun frozen120ScanThumbnailKeepsFormatOrientationAndSource()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        BorderCatalog.initialize(app.assets){NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        withContext(Dispatchers.IO){
            for(format in MediumFormat.entries)for(portrait in listOf(false,true)){
                val dir=File(app.cacheDir,"medium-scan-${UUID.randomUUID()}").apply{mkdirs()}
                try{
                    val p=EditorState(filmBorder=true,composition=CompositionTransform(.4,.6,1.7)).selectMediumFormat(format,portrait)
                    val frozen=BorderCatalog.snapshot(p.frameStock,2400,1600,2400,1600,p.composition,true,"print",format,portrait)
                    val job=ExportJob(UUID.randomUUID().toString(),1,p,2400,1600,ExportFormat.JPEG,95,border=frozen)
                    val token=ScanPreviewStore.begin(dir)
                    val bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)
                    for(y in 0 until 160)for(x in 0 until 240)bitmap.setPixel(x,y,0xff000000.toInt() or ((x*255/239) shl 16) or ((y*255/159) shl 8) or ((x+y)%256))
                    val input=File(dir,"scan-source-${UUID.randomUUID()}.png")
                    input.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    val sourceBytes=input.readBytes()
                    ScanPreviewStore.publish(dir,token,input,job,BorderCatalog){}
                    assertArrayEquals(sourceBytes,input.readBytes())
                    val preview=BitmapFactory.decodeFile(checkNotNull(ScanPreviewStore.select(dir)).path)
                    assertTrue(maxOf(preview.width,preview.height)<=256)
                    assertEquals(frozen.outputWidth.toDouble()/frozen.outputHeight,preview.width.toDouble()/preview.height,.035)
                    assertEquals(frozen,job.border)
                    preview.recycle()
                }finally{dir.deleteRecursively()}
            }
        }
    }
}
