package dev.filmroom.roi

import android.graphics.Bitmap
import android.graphics.Color

/** Obvious coordinate fixture, NOT film-effect correctness or an approximation of film output. */
class CoordinateFixtureProvider : TileProvider {
    override fun render(key: RenderKey, outputRect: PixelRect, cancelled: () -> Boolean): TileResult {
        val sample = outputRect.sampleRect(key)
        val pixels = IntArray(sample.width * sample.height)
        for(y in 0 until sample.height) {
            check(!cancelled()) { "cancelled fixture" }
            for(x in 0 until sample.width) pixels[y*sample.width+x] = color(sample.left+x, sample.top+y)
        }
        val bitmap = Bitmap.createBitmap(pixels,sample.width,sample.height,Bitmap.Config.ARGB_8888)
        return TileResult(key,outputRect,sample,bitmap,TileQuality.ApproximateTestOnly)
    }
    companion object {
        fun color(x: Int, y: Int) = if (x%256==0 || y%256==0) Color.WHITE
            else Color.rgb(x%256,y%256,if((x/256+y/256)%2==0)64 else 192)
    }
}
