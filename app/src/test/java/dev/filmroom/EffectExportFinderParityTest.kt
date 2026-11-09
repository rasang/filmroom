package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.effects.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectExportFinderParityTest {
    private fun fitted(renderer:EffectGlRenderer):Boolean = renderer.javaClass
        .getDeclaredField("fitPreviewFinder").apply{isAccessible=true}.getBoolean(renderer)

    @Test fun exportDefaultAndAppPreviewBothFitLandscapeAndPortraitRails(){
        for(dim in listOf(intArrayOf(600,400,600,550),intArrayOf(400,600,550,600))){
            val src=Bitmap.createBitmap(dim[0],dim[1],Bitmap.Config.ARGB_8888)
            val dst=Bitmap.createBitmap(dim[2],dim[3],Bitmap.Config.ARGB_8888)
            try {
                assertTrue(fitted(EffectGlRenderer(EffectKind.OPTICAL_LEAK,src,dst)))
                assertTrue(fitted(EffectGlRenderer(EffectKind.OPTICAL_LEAK,src,dst,previewFinder=true)))
            } finally {src.recycle();dst.recycle()}
        }
    }
    @Test fun equalAspectAndLightTableRetainOriginalProgram(){
        val src=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
        val dst=Bitmap.createBitmap(600,550,Bitmap.Config.ARGB_8888)
        try{
            assertFalse(fitted(EffectGlRenderer(EffectKind.OPTICAL_LEAK,src,src)))
            assertFalse(fitted(EffectGlRenderer(EffectKind.LIGHT_TABLE,src,dst)))
        }finally{src.recycle();dst.recycle()}
    }
}
