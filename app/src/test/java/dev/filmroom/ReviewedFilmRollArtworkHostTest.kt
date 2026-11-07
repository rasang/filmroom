package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.Switch
import dev.filmroom.border.BorderCatalog
import dev.filmroom.border.MediumFormat
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.model.EditHistory
import dev.filmroom.model.FilmProfileCapabilities
import dev.filmroom.ui.FilmStockArtwork
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.time.Duration

/** Approved physical packaging illustrations are independent of frame-strip capabilities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewedFilmRollArtworkHostTest {
    private val reviewed=linkedMapOf(
        "kodak_tri_x_400" to "40c888bcad47459a8d1f7158fd3924fb84c69b25f7070f12e00c11bce7ed7914",
        "kodak_tmax_100" to "f88d04f961b119f7514bbcff8e2c10dce4006e1b1ebd0bf56f15a1303ddab1de",
        "fujifilm_neopan_acros_100_ii" to "bf3f8a2378c9940500b5a1d4bb4ee397e221e9bfe187beb5b5c81e6b84447b79",
        "ilford_hp5_plus_400" to "2425dc1190deb1b0ce8091d86d5326fff08de93e54465e0878334586a5fd2103",
        "cinestill_800t" to "1c0ae26f4bc61c63daf320fc33e639923966f4e845f53901096d9d8df67de690")
    private val reviewed120=reviewed.keys
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("Reviewed film artwork activity timeout")}
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}

    @Test fun completeManifestAndFivePackagedThumbnailsMatchTheApprovedBytes() {
        val app=RuntimeEnvironment.getApplication()
        assertEquals("Fujifilm Neopan\n100 Acros II",FilmStockArtwork.cardLabel("fujifilm_neopan_acros_100_ii","Fujifilm Neopan 100 Acros II"))
        val manifest=JSONObject(app.assets.open("film-rolls/manifest.json").bufferedReader().use{it.readText()})
        val profiles=manifest.getJSONArray("profiles")
        val rows=(0 until profiles.length()).map{profiles.getJSONObject(it)}
        assertEquals(FilmStockArtwork.physicalStockByProfile.keys,rows.map{it.getString("profile_id")}.toSet())
        assertEquals(27,rows.size)
        val assets=manifest.getJSONArray("assets")
        assertEquals(25,assets.length())
        assertEquals(FilmStockArtwork.physicalStockByProfile.values.toSet(),(0 until assets.length()).map{assets.getJSONObject(it).getString("physical_stock_id")}.toSet())
        for(i in 0 until assets.length()) {
            val row=assets.getJSONObject(i);val bytes=app.assets.open("film-rolls/${row.getString("path")}").use{it.readBytes()}
            assertEquals(row.getInt("bytes"),bytes.size);assertEquals(row.getString("sha256"),sha(bytes))
        }
        assertEquals(FilmProfileCapabilities.spektraLabFilms,reviewed.keys)
        for((stock,hash) in reviewed) {
            val path=FilmStockArtwork.assetPath(stock)
            assertEquals("film-rolls/$stock.webp",path)
            val bytes=app.assets.open(path!!).use{it.readBytes()};assertEquals(stock,hash,sha(bytes))
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            try {
                assertEquals(240,bitmap.width);assertEquals(240,bitmap.height);assertTrue(bitmap.hasAlpha())
                var minX=240;var minY=240;var maxX=-1;var maxY=-1
                for(y in 0 until 240)for(x in 0 until 240)if(bitmap.getPixel(x,y) ushr 24>0){minX=minOf(minX,x);minY=minOf(minY,y);maxX=maxOf(maxX,x);maxY=maxOf(maxY,y)}
                assertEquals(listOf(47,39,197,208),listOf(minX,minY,maxX+1,maxY+1))
                assertTrue(FilmStockArtwork.drawable(app,stock) is BitmapDrawable)
            } finally {bitmap.recycle()}
            val row=rows.single{it.getString("profile_id")==stock}
            assertEquals(listOf("135","120"),(0 until row.getJSONArray("illustrated_formats").length()).map{row.getJSONArray("illustrated_formats").getString(it)})
            assertFalse(row.getBoolean("grants_frame_support"))
        }
        assertNotEquals(FilmStockArtwork.assetPath("kodak_tri_x_400"),FilmStockArtwork.assetPath("kodak_trix"))
        assertNotEquals(FilmStockArtwork.assetPath("cinestill_800t"),FilmStockArtwork.assetPath("kodak_vision3_500t"))
        for(id in listOf("kodak_2302","ilford_multigrade_iv_rc","unknown_stock"))assertNull(FilmStockArtwork.assetPath(id))
    }

    @Test fun reviewedFrameCapabilitiesAreIndependentFromTheUnchangedPackagingBytes() {
        for(stock in reviewed.keys)for(route in listOf("direct","print")) {
            val p=EditorState(filmStock=stock,paperStock=if(stock=="cinestill_800t")"kodak_2383" else "ilford_multigrade_iv_rc",outputRoute=route,filmFormatMm=60f)
            assertTrue(stock,p.frameFormats.has135)
            assertEquals(stock,stock in reviewed120,p.frameFormats.has120)
            assertTrue(stock,p.negativeWhiteHolesSupported)
            assertEquals(stock in reviewed120,p.selectFilmBorder(true).filmBorder)
            assertEquals(60f,p.selectFilmBorder(true).filmFormatMm,0f)
            if(stock in reviewed120)assertEquals(listOf(null)+MediumFormat.entries.toList(),p.frameFormats.formats)
            else {assertTrue(p.frameFormats.formats.isEmpty());assertNull(p.selectFilmBorder(true).effectiveMediumFormat)}
        }
    }

    @Test fun actualFiveFilmClicksKeepDistinctArtworkUndoAndFrameCapabilities() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication();dev.filmroom.i18n.L10n.choose(app,"zh")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val target=File("build/ui-host-renders/five-reviewed-rolls").apply{mkdirs()}
        try {Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
            @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false
            (MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView).disableDetail()
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800);shadowOf(Looper.getMainLooper()).idle()}
            fun strip()=all(root).filterIsInstance<HorizontalScrollView>().single{it.tag=="strip:films"}
            fun selected(stock:String) {
                val card=all(strip()).filterIsInstance<Button>().single{it.isSelected}
                assertEquals(stock,card.tag);assertEquals(stock,flow.value.parameters.filmStock)
                val art=card.compoundDrawables.filterIsInstance<BitmapDrawable>().single()
                val expected=app.assets.open(FilmStockArtwork.assetPath(stock)!!).use{BitmapFactory.decodeStream(it)}
                try{assertTrue(stock,art.bitmap.sameAs(expected))}finally{expected.recycle()}
            }
            for(stock in reviewed.keys) {
                val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
                val baseline=EditorState(filmStock="kodak_portra_400",paperStock="kodak_2383",filmFormatMm=60f,brightness=.43f,grain=.4f)
                    .selectOutputRoute("direct").selectMediumFormat(MediumFormat.MF67,true).selectFilmBorder(true)
                EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,EditHistory(baseline))
                flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"reviewed-$stock"),"fixture.jpg","image/jpeg",6000,4000,0),original=original,
                    rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=baseline,renderedParameters=baseline)
                await{all(root).any{it.tag==stock}};layout()
                val card=all(strip()).filterIsInstance<Button>().single{it.tag==stock}
                strip().isSmoothScrollingEnabled=false;strip().scrollTo(card.left,0);layout()
                val x=strip().scrollX
                card.performClick();await{flow.value.parameters.filmStock==stock && flow.value.canExport};layout();selected(stock)
                assertEquals("Selection must not recenter",x,strip().scrollX)
                val p=flow.value.parameters
                assertEquals(.43f,p.brightness,0f);assertEquals(.4f,p.grain,0f);assertEquals(60f,p.filmFormatMm,0f);assertEquals("direct",p.outputRoute)
                vm.undo();await{flow.value.parameters==baseline && flow.value.canExport};layout();selected(baseline.filmStock)
                vm.redo();await{flow.value.parameters==p && flow.value.canExport};layout();selected(stock)
                val toggle=all(root).filterIsInstance<Switch>().single{it.tag=="film-border-switch"}
                assertEquals(stock in reviewed120,toggle.isEnabled);assertEquals(stock in reviewed120,toggle.isChecked)
                if(stock in reviewed120) {
                    assertEquals(MediumFormat.MF67,p.effectiveMediumFormat)
                    all(root).single{it.tag=="composition"}.performClick()
                    await{flow.value.compositionEditing && all(root).any{it.tag=="composition-format:MF67"}};layout()
                    assertEquals(6,all(root).count{(it.tag as? String)?.startsWith("composition-format:")==true})
                    assertTrue(all(root).any{it.tag=="composition-format:135"})
                    all(root).single{it.tag=="crop-confirm"}.performClick();await{flow.value.canExport};layout()
                } else {toggle.performClick();assertFalse(flow.value.parameters.filmBorder);assertNull(p.effectiveMediumFormat)}
                assertFalse((all(root).single{it.tag=="negative-white-holes-switch"} as Switch).isEnabled)
                val beforeRepeat=flow.value.parameters
                all(strip()).filterIsInstance<Button>().single{it.tag==stock}.performClick();layout();assertEquals(beforeRepeat,flow.value.parameters)
                all(root).filterIsInstance<Button>().first{it.text.toString()=="调整"}.performClick();layout()
                all(root).filterIsInstance<Button>().first{it.text.toString()=="胶片"}.performClick();layout();selected(stock)
                val screenshot=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                try{root.draw(Canvas(screenshot));File(target,"$stock.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screenshot.recycle()}
                println("REVIEWED ROLL UI PASS stock=$stock distinctArtwork=true has135=true has120=${stock in reviewed120}")
            }
        }} finally {app.getSharedPreferences("local-session",0).edit().clear().commit()}
    }
}
