package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectVideoFrameScheduleTest {
    @Test fun allEffectsAndSlowPlaybackExportExactly90Frames() {
        for(kind in EffectKind.values()) for(slow in listOf(false,true)) {
            val duration = EffectTimeline.durationMs(kind,slow)
            assertEquals(3000L,duration)
            assertEquals(90,EffectVideoExporter.frameCount(duration))
        }
    }
    @Test fun timestampsAreMonotonicAndTheNominalSampleWindowIsThreeSeconds() {
        val timestamps = (0 until 90).map(EffectVideoExporter::presentationTimeNanos)
        assertEquals(0L,timestamps.first())
        assertEquals(2_966_666_666L,timestamps.last())
        assertTrue(timestamps.zipWithNext().all { (a,b) -> b>a && b-a in 33_333_333L..33_333_334L })
        // Frame 89 starts at 2.9667s. Its nominal 1/30s sample extends to 3s.
        // This checks frame scheduling, not a device's actual muxed MP4 duration.
        assertEquals(3_000_000_000L,EffectVideoExporter.presentationTimeNanos(90))
    }
    @Test fun lastEncodedFrameIncludesTheCompletedEffectState() {
        for(kind in EffectKind.values()) for(slow in listOf(false,true)) {
            val duration=EffectTimeline.durationMs(kind,slow)
            val count=EffectVideoExporter.frameCount(duration)
            assertEquals(0L,EffectVideoExporter.timelineTimeMs(0))
            val sampleMs=EffectVideoExporter.timelineTimeMs(count-1)
            assertEquals(2966L,sampleMs)
            val state=EffectTimeline.at(kind,sampleMs,slow)
            assertEquals(EffectTimeline.at(kind,duration,slow),state)
            if(kind==EffectKind.LIGHT_TABLE) assertEquals(1f,state.gain,0.00001f)
            else { assertTrue(state.showResult);assertEquals(0f,state.leak,0f) }
        }
    }
}
