package dev.filmroom

import android.app.Application
import dev.filmroom.ui.BetaBadgeDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
class BetaBadgeHostTest {
    @Test fun badgeStaysAtTopRightAndAbovePlayIconAtDifferentDensities() {
        for (density in listOf(1f, 2f, 3f)) {
            val drawable = BetaBadgeDrawable(density)
            drawable.setBounds(0, 0, (48 * density).toInt(), (48 * density).toInt())
            val badge = drawable.badgeBounds()
            assertEquals(48 * density, badge.right, .01f)
            assertEquals(0f, badge.top, .01f)
            assertTrue(badge.left >= 0f)
            // The play glyph begins at the button's 13 dp top padding.
            assertTrue(badge.bottom < 13 * density)
            drawable.setBounds(5, 7, (80 * density).toInt(), (48 * density).toInt())
            assertEquals(drawable.bounds.right.toFloat(), drawable.badgeBounds().right, .01f)
            assertEquals(7f, drawable.badgeBounds().top, .01f)
        }
    }
}
