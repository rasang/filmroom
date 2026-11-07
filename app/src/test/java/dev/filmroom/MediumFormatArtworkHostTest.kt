package dev.filmroom

import android.graphics.BitmapFactory
import dev.filmroom.ui.FilmStockArtwork
import dev.filmroom.border.MediumFormatStocks
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatArtworkHostTest {
    @Test fun approvedPhysicalArtworkHasExactHashesAndCorrectCapability() {
        val assets=RuntimeEnvironment.getApplication().assets
        val manifest=JSONObject(assets.open("film-rolls/medium-format-manifest.json").bufferedReader().use{it.readText()}).getJSONArray("entries")
        assertEquals(15,manifest.length())
        var pairs=0;var singles=0
        for(i in 0 until manifest.length()) {
            val item=manifest.getJSONObject(i);val profile=item.getString("profile_id")
            val bytes=assets.open(checkNotNull(FilmStockArtwork.assetPath(profile))).use{it.readBytes()}
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            assertEquals(profile,item.getString("sha256"),hash)
            assertEquals(item.getString("physical_stock_id"),FilmStockArtwork.physicalStockByProfile[profile])
            val paired=item.getString("form")=="135 + 120"
            assertEquals(profile,paired,MediumFormatStocks.available(profile))
            if(paired)pairs++ else singles++
            val bitmap=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            assertEquals(240,bitmap.width);assertEquals(240,bitmap.height);assertTrue(bitmap.hasAlpha());bitmap.recycle()
        }
        assertEquals(12,pairs);assertEquals(3,singles)
    }
}
