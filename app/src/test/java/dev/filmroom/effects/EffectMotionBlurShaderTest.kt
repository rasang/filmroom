package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectMotionBlurShaderTest {
    @Test fun generatedOffsetsAreFloatLiteralsForGles2() {
        val shader=EffectMotionGpuBlur.fragmentSource()
        val offsets=Regex("uv\\+d\\*([^,]+),").findAll(shader).map { it.groupValues[1] }.toList()
        assertEquals(21, offsets.size)
        assertEquals((-10..10).map { it.toFloat().toString() }, offsets)
        assertTrue(offsets.all { it.contains('.') })
    }
}
