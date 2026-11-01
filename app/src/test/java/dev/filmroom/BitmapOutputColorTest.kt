package dev.filmroom
import dev.filmroom.engine.requireBitmapOutputColor
import org.junit.Test
class BitmapOutputColorTest {
    @Test fun srgbBitmapIsAllowed(){requireBitmapOutputColor("sRGB")}
    @Test(expected=IllegalArgumentException::class) fun p3CannotBeMislabeledSrgb(){requireBitmapOutputColor("Display P3")}
    @Test(expected=IllegalArgumentException::class) fun adobeCannotBeMislabeledSrgb(){requireBitmapOutputColor("Adobe RGB (1998)")}
}
