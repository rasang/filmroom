package dev.filmroom.border

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Host stage timings are diagnostic only, never a handset latency guarantee. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhiteHolePreviewLatencyHostTest {
    @Test fun reportWhiteHoleStagesWithActualAssets() {
        val app=RuntimeEnvironment.getApplication()
        for(stock in listOf("kodak_portra_400","cinestill_800t","kodak_doublex")) {
            val defs=(CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+
                Reviewed135Definitions.definitions+BwBorderDefinitions.definitions).filter{it.spec.stock==stock}
            var loads=0
            val current=defs.first()
            val registry=LazyBorderRegistry(defs,mapOf(stock to current.key)){d,active->
                loads++;StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)},active)
            }
            for(edge in listOf(512,768,1536)) {
                val photo=Bitmap.createBitmap(edge,edge*2/3,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff74889a.toInt())}
                try {
                    repeat(4){iteration->
                        val white=iteration%2==0
                        val context=FrameAppearanceContext.forProfile(stock,"print",white)
                        val d=when(stock){
                            in Reviewed135Definitions.stocks->Reviewed135Definitions.selected(stock,context)
                            "kodak_doublex"->BwBorderDefinitions.selected(stock,context)
                            else->CandidateBorderDefinitions.selectedDefinition(stock,white)
                        }
                        val s=BorderSnapshot.authored(stock,d.spec.version,photo.width,photo.height,photo.width,photo.height,CompositionTransform(),d.spec.featherTotalAt3072).copy(appearance=context)
                        val before=loads;val t0=System.nanoTime();val raster=registry.resolve(s);val t1=System.nanoTime()
                        val overlay=InteractiveBorderPreview.create(s,raster);val t2=System.nanoTime()
                        val result=BorderPreview.compose(photo,s,raster);val t3=System.nanoTime()
                        val fused=System.getProperty("filmroom.fusedPreview","true").toBoolean()
                        val t4=System.nanoTime()
                        val combinedOverlay=if(fused)Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888) else null
                        val combined=if(fused)BorderPreview.composeWithOverlay(photo,s,raster,null,checkNotNull(combinedOverlay)) else null
                        val t5=System.nanoTime()
                        if(fused){assertTrue("Exact result changed: $stock/$edge/$white",result.sameAs(combined));assertTrue("Gesture overlay changed: $stock/$edge/$white",overlay.sameAs(combinedOverlay))}
                        val pixels=IntArray(result.width*result.height);result.getPixels(pixels,0,result.width,0,0,result.width,result.height)
                        val digest=java.security.MessageDigest.getInstance("SHA-256")
                        for(pixel in pixels)for(shift in 24 downTo 0 step 8)digest.update((pixel ushr shift).toByte())
                        println("WHITE_HOLE_PIXELS stock=$stock edge=$edge white=$white sha256=${digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}}")
                        println("WHITE_HOLE_STAGE stock=$stock edge=$edge iteration=$iteration white=$white loads=${loads-before} resolveMs=${(t1-t0)/1e6} overlayMs=${(t2-t1)/1e6} composeMs=${(t3-t2)/1e6} fusedMs=${if(fused)(t5-t4)/1e6 else -1.0}")
                        assertEquals(s.outputWidth,result.width);assertEquals(s.outputHeight,overlay.height)
                        overlay.recycle();result.recycle();combined?.recycle();combinedOverlay?.recycle()
                    }
                } finally {photo.recycle()}
            }
        }
    }
}
