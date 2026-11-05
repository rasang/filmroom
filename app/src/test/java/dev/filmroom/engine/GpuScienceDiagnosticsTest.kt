package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuScienceDiagnosticsTest {
    private fun frame() = GpuScienceSessionPool.openFrame(RuntimeEnvironment.getApplication(), emptySet(), null, true)
    private fun stats(epoch: Long = 1, pipelines: Long = 2, buffers: Long = 3, clears: Long = 4) =
        GpuScienceSessionStats(epoch, 1000, 100, 500, 4, 200, pipelines, buffers, clears)
    @Test fun observationsUseDeltasAndFreshEpochResetsOnlyTheBaseline() {
        val f = frame(); val first = stats()
        f.recordSessionStats(null, first)
        f.recordSessionStats(first, stats(pipelines = 5, buffers = 8, clears = 9))
        f.recordSessionStats(null, stats(epoch = 2, pipelines = 1, buffers = 2, clears = 3))
        f.recordSessionRecycle()
        val report = f.sessionDiagnostics()
        assertEquals(setOf("pipelineCacheHits", "bufferCacheHits", "clearSubmissions", "sessionRecycles"), report.keys().asSequence().toSet())
        assertEquals(6L, report.getLong("pipelineCacheHits")); assertEquals(10L, report.getLong("bufferCacheHits"))
        assertEquals(12L, report.getLong("clearSubmissions")); assertEquals(1L, report.getLong("sessionRecycles"))
        assertNull(f.controller)
    }
    @Test fun duplicateSealCannotAddObservationsTwice() {
        val f = frame(); val life = GpuScienceSessionLifetime(1)
        life.requestHelper(); life.acknowledgeStartup(1); life.attemptOpen(1); life.opened(1)
        life.attemptBeginFrame(1, 1); life.frameOpened(1, 1)
        val ticket = GpuScienceStageTicket(1, 1, 1, 1, "a".repeat(64))
        assertTrue(life.attemptStage(ticket))
        repeat(2) { if (life.stageSealed(ticket)) f.recordSessionStats(null, stats()) }
        assertEquals(2L, f.sessionDiagnostics().getLong("pipelineCacheHits"))
    }
    @Test fun absentOrInvalidObservationsAreNotEstimated() {
        val f = frame()
        assertEquals(0L, f.sessionDiagnostics().getLong("sessionRecycles"))
        assertEquals(0L, f.sessionDiagnostics().getLong("pipelineCacheHits"))
        assertTrue(runCatching { f.recordSessionStats(stats(), stats(pipelines = 1)) }.isFailure)
        assertTrue(runCatching { f.recordSessionStats(stats(), stats(epoch = 2)) }.isFailure)
        assertEquals(0L, f.sessionDiagnostics().getLong("bufferCacheHits"))
    }
}
