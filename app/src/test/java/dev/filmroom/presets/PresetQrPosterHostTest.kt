package dev.filmroom.presets

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetQrPosterHostTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun posterKeepsPayloadPixelsAndReadsFromAlbumAcrossLanguages() {
        for(lang in listOf("zh","en","ja","ko")) {
            L10n.choose(app,lang)
            val name=when(lang){"en"->"Soft afternoon light and warm memories in the city";"ja"->"午後の暖かい光と街角の思い出";"ko"->"따뜻한 오후의 빛과 골목의 추억";else->"午后暖调"}
            val state=EditorState();val payload=PresetQrCodec.encode(name,state)
            val qr=PresetQrImage.render(app,payload)
            val poster=PresetQrPoster.render(app,qr,name,state)
            try {
                assertTrue(poster.width>=1080);assertTrue(poster.width.toLong()*poster.height<=2048L*2048)
                assertEquals(Color.rgb(25,25,25),poster.getPixel(0,0))
                assertEquals(payload,PresetQrImage.decode(poster))
                // Find exact white square and verify every QR pixel plus all four quiet edges.
                val x=(poster.width-qr.width)/2
                val top=(170 until poster.height-qr.height).first { y->poster.getPixel(x,y)==Color.WHITE && poster.getPixel(x+qr.width-1,y)==Color.WHITE }
                for(row in 0 until qr.height)for(col in 0 until qr.width)assertEquals(qr.getPixel(col,row),poster.getPixel(x+col,top+row))
                val dir=File(app.cacheDir,"preset-qr-shares").apply{mkdirs()};val file=File(dir,"poster-$lang.png")
                file.outputStream().use{poster.compress(Bitmap.CompressFormat.PNG,100,it)}
                assertEquals(payload,PresetQrImage.read(app,FileProvider.getUriForFile(app,(dev.filmroom.BuildConfig.APPLICATION_ID + ".presetqr"),file)))
                System.getProperty("filmroom.qr.artifacts")?.let{out->File(out).mkdirs();File(out,"poster-$lang.png").writeBytes(file.readBytes())}
            } finally {poster.recycle();qr.recycle()}
        }
    }
    @Test fun longestNamesAndDensePayloadStayReadableWithoutTruncatingCode() {
        val name="长名称测试".repeat(12)
        val frozen=checkNotNull(javaClass.getResourceAsStream("/presets/qr-v2-code55-all-fields.txt")).bufferedReader().use{it.readText()}
        val qr=PresetQrImage.render(app,frozen)
        val poster=PresetQrPoster.render(app,qr,name,"Kodak Professional Portra 400","Fujifilm Crystal Archive Type II",PresetQrPoster.copy("zh"))
        try {
            assertEquals(frozen,PresetQrImage.decode(poster))
            val scaled=Bitmap.createScaledBitmap(poster,poster.width/2,poster.height/2,false)
            try{assertEquals(frozen,PresetQrImage.decode(scaled))}finally{scaled.recycle()}
            System.getProperty("filmroom.qr.artifacts")?.let{out->File(out).mkdirs();File(out,"poster-dense.png").outputStream().use{poster.compress(Bitmap.CompressFormat.PNG,100,it)}}
        }finally{poster.recycle();qr.recycle()}
    }
}
