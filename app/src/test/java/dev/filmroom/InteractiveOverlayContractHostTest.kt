package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveOverlayContractHostTest {
    @Test fun encodedRasterIsNotGammaEncodedTwiceAndOnlyNarrowWindowBandIsTransparent(){
        val r=object:BorderRaster {
            override val stock="fixture";override val version="overlay-test"
            override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=.2f;rgb[1]=.4f;rgb[2]=.6f}
        }
        val s=BorderSnapshot.fixed135("fixture",r.version,96,64)
        val layer=InteractiveBorderPreview.create(s,r)
        try{
            assertEquals(0xff336699.toInt(),layer.getPixel(0,0))
            assertEquals(0,layer.getPixel(layer.width/2,layer.height/2))
            assertEquals(InteractiveBorderPreview.key(s),InteractiveBorderPreview.key(s.copy(composition=CompositionTransform(.5,.5,2.0))))
        }finally{layer.recycle()}
    }
    @Test fun aestheticWhiteMatteIsVersionPinnedAndLegacyRemainsBlackAndWhite(){
        val app=RuntimeEnvironment.getApplication()
        for(stock in listOf("kodak_ektar_100","kodak_gold_200")){
            val current=CandidateBorderDefinitions.definitions.single{it.spec.stock==stock}
            val legacy=CandidateBorderDefinitions.legacyDefinitions.filter{it.spec.stock==stock}
            assertEquals(setOf(0,0xffffff),legacy.map{it.spec.holeMatteSrgb}.toSet())
            assertEquals(0xffffff,current.spec.holeMatteSrgb)
            for(d in listOf(current)+legacy){
                val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
                val s=BorderSnapshot.fixed135(stock,d.spec.version,1440,960)
                val rgb=FloatArray(3)
                // KS perforation center in the physical top rail; not the transparent photo window.
                raster.sample(s,100.0,136.0,rgb)
                val wanted=if(d.spec.holeMatteSrgb==0)0f else 1f
                rgb.forEach{assertEquals(wanted,it,.001f)}
            }
        }
    }
}
