package dev.filmroom.presets

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetQrLogoHostTest {
    @org.junit.After fun clearProviderHostCache() {
        // AndroidX caches paths process-wide; Robolectric gives each test a different private root.
        val field=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }
        (field.get(null) as MutableMap<*,*>).clear()
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private fun artifact(name:String, bitmap:Bitmap) { System.getProperty("filmroom.qr.artifacts")?.let { path -> File(path).mkdirs();File(path,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } }
    private fun jpeg(source:Bitmap):Bitmap { val bytes=ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG,85,it) }.toByteArray();return checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)) }
    private fun skew(source:Bitmap, large:Boolean=false):Bitmap {
        val side=if(large) 600 else 430
        val output=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);val canvas=Canvas(output);canvas.drawColor(Color.WHITE)
        val matrix=Matrix();val n=source.width.toFloat()
        val dest=floatArrayOf(35f,18f,401f,40f,380f,410f,12f,375f).map { it*side/430f }.toFloatArray()
        assertTrue(matrix.setPolyToPoly(floatArrayOf(0f,0f,n,0f,n,n,0f,n),0,dest,0,4))
        canvas.drawBitmap(source,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG));return output
    }
    private fun checkDecode(label:String,text:String,bitmap:Bitmap) { try { assertEquals(label,text,PresetQrImage.decode(bitmap)) } catch (failure:com.google.zxing.ReaderException) { throw AssertionError("Decode failed: $label (${bitmap.width}px)",failure) } }
    private fun frozenPerspectiveText() = checkNotNull(javaClass.getResourceAsStream("/presets/qr-v2-beta4-dense-perspective.txt"))
        .bufferedReader().use { it.readText() }
    private fun frozenPerspectiveBytes() = checkNotNull(javaClass.getResourceAsStream("/presets/qr-v2-beta4-dense-perspective.png"))
        .use { it.readBytes() }
    @Test fun frozenDensePerspectiveImageRemainsReadableFromPixels() {
        // Keep the actual failed beta4 image: changing the renderer must not hide an import bug.
        val expected = frozenPerspectiveText()
        val bytes = frozenPerspectiveBytes()
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(600, bitmap.width)
            assertEquals(600, bitmap.height)
            checkDecode("frozen beta4 perspective", expected, bitmap)
        } finally { bitmap.recycle() }
    }
    @Test fun frozenDensePerspectiveImageRemainsReadableFromAlbum() {
        val directory = File(app.cacheDir, "preset-qr-shares").apply { mkdirs() }
        val file = File(directory, "frozen-dense-perspective.png").apply { writeBytes(frozenPerspectiveBytes()) }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.presetqr", file)
        assertEquals(frozenPerspectiveText(), PresetQrImage.read(app, uri))
    }
    @Test fun realLogoCodesDecodeOriginalSmallJpegPerspectiveAndAlbum() {
        val rows=JSONArray()
        for ((id,entry) in PresetQrCompactHostTest().samples().withIndex()) {
            val (name,state)=entry;val text=PresetQrCodec.encode(name,state)
            val info=Encoder.encode(text,ErrorCorrectionLevel.H)
            assertEquals(Mode.ALPHANUMERIC,info.mode)
            val code=PresetQrImage.render(app,text)
            System.getProperty("filmroom.qr.artifacts")?.let { File(it,"$id-v2.txt").writeText(text) }
            val modules=info.matrix.width;val patch=PresetQrImage.logoPatchModules(modules)
            assertTrue(patch.toDouble()*patch/(modules*modules)<=.029)
            assertEquals(text,PresetQrImage.decode(code));artifact("$id-new.png",code)
            // PNG export and the real album content-URI input path.
            val directory=File(app.cacheDir,"preset-qr-shares").apply { mkdirs() };val file=File(directory,"logo-$id.png")
            file.outputStream().use { code.compress(Bitmap.CompressFormat.PNG,100,it) }
            val uri=androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.presetqr",file)
            assertEquals(state,PresetQrCodec.decode(PresetQrImage.read(app,uri)).parameters)
            val screenSize=if(id==6) 360 else 260
            val small=Bitmap.createScaledBitmap(code,screenSize,screenSize,true)
            assertEquals(text,PresetQrImage.decode(small));artifact("$id-screen.png",small)
            val jpg=jpeg(small);artifact("$id-jpeg-decoded.png",jpg); checkDecode("$id JPEG",text,jpg);jpg.recycle()
            val tilted=skew(code,large=id==6);artifact("$id-perspective.png",tilted);checkDecode("$id perspective",text,tilted);tilted.recycle()
            val rotated=Bitmap.createBitmap(small,0,0,small.width,small.height,Matrix().apply { postRotate(90f) },false)
            assertEquals(text,PresetQrImage.decode(rotated));rotated.recycle();small.recycle()
            rows.put(JSONObject().put("id",id).put("name",name).put("characters",text.length).put("version",info.version.versionNumber)
                .put("modules",modules).put("imagePx",code.width).put("correction","H").put("patchModules",patch).put("decodeChecks",6))
            code.recycle()
        }
        System.getProperty("filmroom.qr.artifacts")?.let { File(it,"COMPACT_CAPACITY.json").writeText(rows.toString(2)) }
        println("COMPACT_CAPACITY $rows")
    }
    @Test fun boundedLogoDoesNotPreventDecodingAcrossQrVersionsAndMaximumDensity() {
        for(size in listOf(60,80,100,140,200,300,450,650,900,1200,1500,1800)) for(seed in 0..3) {
            val text="FILMROOM:2:"+buildString { val r=java.util.Random(size.toLong()*31+seed);repeat(size-11) { append("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[r.nextInt(36)]) } }
            val code=try { PresetQrImage.render(app,text) } catch(failure:Exception) { throw AssertionError("render length=$size seed=$seed",failure) }
            assertEquals("length=$size",text,PresetQrImage.decode(code));code.recycle()
        }
    }
    @Test fun displayKeepsWholeModulePixelsWithoutFillingCommonScreens() {
        assertEquals(228,PresetQrImage.displayWidth(456,320,1f))
        assertEquals(306,PresetQrImage.displayWidth(1224,320,1f))
        assertEquals(513,PresetQrImage.displayWidth(456,640,2f))
        assertEquals(242,PresetQrImage.displayWidth(968,264,1f))
    }
}
