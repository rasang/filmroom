package dev.filmroom.effects

import android.graphics.Color
import android.graphics.Rect
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectMotionMappingYuvRestoreTest {
    @Test fun mappingBoundsAndQuarterTurns() {
        for (r in listOf(0,90,180,270,-90,450)) {
            val m=EffectMotionSurfaceMapping.create(4000,3000,r,null)
            assertEquals(720,maxOf(m.width,m.height))
            assertEquals(540,minOf(m.width,m.height))
            assertEquals(.2,m.uprightPoint(.2,.3).first,1e-12)
            assertEquals(.3,m.uprightPoint(.2,.3).second,1e-12)
        }
        assertEquals(.3 to .8,EffectMotionSurfaceMapping.create(4,3,90,null).rawPoint(.2,.3))
        assertEquals(.8 to .7,EffectMotionSurfaceMapping.create(4,3,180,null).rawPoint(.2,.3))
        assertEquals(.7 to .2,EffectMotionSurfaceMapping.create(4,3,270,null).rawPoint(.2,.3))
    }
    @Test fun mappingDoesNotUpscaleAndRejectsInvalidRotation() {
        val m=EffectMotionSurfaceMapping.create(4,3,0,null)
        assertEquals(4,m.width);assertEquals(3,m.height)
        assertThrows(IllegalArgumentException::class.java){EffectMotionSurfaceMapping.create(4,3,45,null)}
        assertThrows(IllegalArgumentException::class.java){EffectMotionSurfaceMapping.create(0,3,0,null)}
    }
    private fun planes(y:Int,u:Int=128,v:Int=128):List<EffectMotionYuvConverter.Plane> = listOf(
        EffectMotionYuvConverter.Plane(ByteBuffer.wrap(ByteArray(8){y.toByte()}),4,1),
        EffectMotionYuvConverter.Plane(ByteBuffer.wrap(byteArrayOf(u.toByte(),0,u.toByte(),0)),4,2),
        EffectMotionYuvConverter.Plane(ByteBuffer.wrap(byteArrayOf(v.toByte(),0,v.toByte(),0)),4,2))
    @Test fun limitedAndFullNeutralRangeEndpoints() {
        for (standard in listOf(1,4,6)) for ((range,black,white) in listOf(Triple(1,0,255),Triple(2,16,235))) {
            for ((y,expected) in listOf(black to 0,white to 255)) {
                val b=EffectMotionYuvConverter(720,0).convert(Rect(0,0,4,2),planes(y),range,standard)
                assertEquals(expected,Color.red(b.getPixel(0,0)));assertEquals(expected,Color.green(b.getPixel(3,1)));assertEquals(expected,Color.blue(b.getPixel(2,0)));b.recycle()
            }
        }
    }
    @Test fun cropStrideBufferPositionAndRotation() {
        val p=planes(100).toMutableList()
        val y=ByteBuffer.wrap(byteArrayOf(0,16,16,235.toByte(),235.toByte(),16,16,235.toByte(),235.toByte()))
        y.position(1);p[0]=EffectMotionYuvConverter.Plane(y,4,1)
        val b=EffectMotionYuvConverter(720,90).convert(Rect(2,0,4,2),p,2,1)
        assertEquals(2,b.width);assertEquals(2,b.height);assertFalse(b.isRecycled)
        assertEquals(255,Color.red(b.getPixel(0,0)));assertEquals(1,y.position());b.recycle()
    }
    @Test fun downsampleKeepsAspectAndConverterCanBeReused() {
        val c=EffectMotionYuvConverter(2,0)
        repeat(2){val b=c.convert(Rect(0,0,4,2),planes(128),1,1);assertEquals(2,b.width);assertEquals(1,b.height);b.recycle()}
    }
}
