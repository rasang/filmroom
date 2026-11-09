package dev.filmroom.effects

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectMotionCpuBlurFallbackTest {
    @Test fun rejectedGpuStillProducesDifferentBlurredFramesFromCurrentVideo() {
        val policy=EffectMotionBlurPolicy()
        val outputs=mutableListOf<Int>()
        for(color in listOf(Color.RED,Color.BLUE)) {
            val current=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            try {
                policy.render({error("simulated shader compile failure")},{},{
                    val blur=EffectTextures.defocused(current)
                    try {outputs+=blur.getPixel(16,12)}finally{blur.recycle()}
                })
                assertEquals(color,current.getPixel(16,12))
            }finally{current.recycle()}
        }
        assertEquals(listOf(Color.RED,Color.BLUE),outputs)
    }
}
