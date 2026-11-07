package dev.filmroom.export

import android.app.Application
import android.graphics.*
import android.net.Uri
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
class ExportFinalPreviewHostTest {
    private fun scene(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this);c.drawColor(Color.rgb(242,238,225))
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(28,43,58);textSize=58f}
        c.drawText("FILMROOM  Fine-detail preview  0123456789",42f,100f,p)
        for(x in 32 until w-32 step 12) {p.color=if((x/12)%2==0)Color.rgb(25,85,90)else Color.rgb(240,150,70);c.drawRect(x.toFloat(),150f,(x+5).toFloat(),(h-100).toFloat(),p)}
        p.color=Color.rgb(20,28,35);p.textSize=34f;c.drawText("Saved photograph pixels are unchanged",42f,(h-40).toFloat(),p)
    }
    @Test fun savedImageUses1024DerivativeWithoutChangingOriginalAndPublishesAtomically() {
        val app=RuntimeEnvironment.getApplication();val d=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val input=File(d,"saved.png");val original=scene(2048,1024)
        try {
            input.outputStream().use{original.compress(Bitmap.CompressFormat.PNG,100,it)}
            val before=input.readBytes();val started=System.nanoTime()
            val display=ExportFinalPreview.decode(app,Uri.fromFile(input).toString())
            try {
                assertEquals(1024,display.width);assertEquals(512,display.height)
                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),display.colorSpace)
                ExportFinalPreview.write(display,d)
                assertArrayEquals(before,input.readBytes());assertFalse(File(d,"final-preview.jpg.part").exists())
                val read=BitmapFactory.decodeFile(File(d,"final-preview.jpg").path)
                try{assertEquals(1024,read.width);assertEquals(512,read.height)}finally{read.recycle()}
                System.getProperty("filmroom.preview.comparison")?.let { path ->
                    val folder=File(path).apply{mkdirs()}
                    val oldDecoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver,Uri.fromFile(input))) { decoder,_,_->
                        decoder.setTargetSize(240,120);decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    val oldBytes=java.io.ByteArrayOutputStream().also{oldDecoded.compress(Bitmap.CompressFormat.JPEG,88,it)}.toByteArray()
                    oldDecoded.recycle()
                    val old=BitmapFactory.decodeByteArray(oldBytes,0,oldBytes.size)
                    val encoded=BitmapFactory.decodeFile(File(d,"final-preview.jpg").path)
                    val comparison=Bitmap.createBitmap(1024,1152,Bitmap.Config.ARGB_8888)
                    try {
                        val c=Canvas(comparison);c.drawColor(Color.rgb(22,22,22));val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        p.color=Color.WHITE;p.textSize=24f
                        c.drawText("Synthetic test chart: old 240px thumbnail",20f,36f,p)
                        c.drawBitmap(old,null,RectF(20f,54f,1004f,546f),p)
                        c.drawText("Same saved pixels: new 1024px thumbnail",20f,606f,p)
                        c.drawBitmap(encoded,null,RectF(20f,624f,1004f,1116f),p)
                        File(folder,"final-thumbnail-before-after.png").outputStream().use{comparison.compress(Bitmap.CompressFormat.PNG,100,it)}
                    }finally{old.recycle();encoded.recycle();comparison.recycle()}
                }
                println("FINAL_PREVIEW_2048_TO_1024_MS="+(System.nanoTime()-started)/1_000_000)
            }finally{display.recycle()}
        }finally{original.recycle();d.deleteRecursively()}
    }
    @Test fun smallSavedPhotoIsNeverInventedOrUpscaled() {
        val app=RuntimeEnvironment.getApplication();val d=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val input=File(d,"small.png");val small=scene(320,200)
        try {
            input.outputStream().use{small.compress(Bitmap.CompressFormat.PNG,100,it)}
            val display=ExportFinalPreview.decode(app,Uri.fromFile(input).toString())
            try{assertEquals(320,display.width);assertEquals(200,display.height)}finally{display.recycle()}
        }finally{small.recycle();d.deleteRecursively()}
    }
    @Test fun invalidOversizedDerivativeCannotReplaceExistingPreview() {
        val app=RuntimeEnvironment.getApplication();val d=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val final=File(d,"final-preview.jpg").apply{writeText("previous valid bytes")}
        val oversized=Bitmap.createBitmap(1025,1,Bitmap.Config.ARGB_8888)
        try {
            try{ExportFinalPreview.write(oversized,d);fail("Must reject unbounded dimensions")}catch(_:IllegalArgumentException){}
            assertEquals("previous valid bytes",final.readText());assertFalse(File(d,"final-preview.jpg.part").exists())
        }finally{oversized.recycle();d.deleteRecursively()}
    }
    @Test fun queueFallsBackTo240WhenLargerDerivativeIsNotAdmitted() {
        val app=RuntimeEnvironment.getApplication();val d=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val input=File(d,"saved.png");val original=scene(1200,600)
        val manager=app.getSystemService(android.app.ActivityManager::class.java)
        val shadow=org.robolectric.Shadows.shadowOf(manager)
        try {
            input.outputStream().use{original.compress(Bitmap.CompressFormat.PNG,100,it)}
            val bytes=input.readBytes();val queue=ExportQueue(app)
            // Existing policy allows half of headroom above 64 MiB: 8 MiB admits
            // the 4 MiB fallback but rejects the optional 16 MiB sharper decode.
            shadow.setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply{availMem=80L*1024*1024;threshold=0;lowMemory=false})
            val save=ExportQueue::class.java.getDeclaredMethod("saveFinalThumbnail",String::class.java,File::class.java).apply{isAccessible=true}
            save.invoke(queue,Uri.fromFile(input).toString(),d)
            val file=File(d,"final-preview.jpg");val small=BitmapFactory.decodeFile(file.path)
            try{assertEquals(240,small.width);assertEquals(120,small.height)}finally{small.recycle()}
            assertArrayEquals(bytes,input.readBytes());val previous=file.readBytes()
            shadow.setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply{availMem=70L*1024*1024;threshold=0;lowMemory=false})
            try{save.invoke(queue,Uri.fromFile(input).toString(),d);fail("Both optional budgets should be rejected")}
            catch(e:java.lang.reflect.InvocationTargetException){assertTrue(e.cause is dev.filmroom.io.PhotoException)}
            assertArrayEquals(previous,file.readBytes());assertArrayEquals(bytes,input.readBytes())
        }finally{original.recycle();d.deleteRecursively()}
    }
}
