package dev.filmroom.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuScienceFrameDelegateTest {
    private fun frame(operations: Set<GpuRenderOperation> = setOf(GpuRenderOperation.FILM_EXPOSURE)) =
        GpuScienceSessionPool.openFrame(RuntimeEnvironment.getApplication(), operations, null, true)
    @Test fun lazyFrameCreationAndZeroGpuFinishNeedNoControllerOrLease() = runBlocking {
        val f = frame()
        assertNull(f.controller); assertFalse(f.sealed)
        f.finishFrame()
        assertTrue(f.sealed); assertTrue(f.awaitSafeTermination()); assertNull(f.controller)
        var cleanups = 0
        f.whenSafeToRelease { cleanups++ }
        assertEquals(1, cleanups)
    }
    @Test fun cancellingBeforeBeginNeverStartsOrAdmitsNativeWork() = runBlocking {
        val f = frame()
        f.retireOwnedSession()
        assertTrue(f.cancelled); assertNull(f.controller)
        val rejected = JSONObject(f.beginSessionStage("not even parsed"))
        assertFalse(rejected.getBoolean("accepted")); assertEquals("START_REVOKED", rejected.getString("code"))
        assertNull(f.controller); assertTrue(f.awaitSafeTermination())
        val poll = JSONObject(f.pollUncertainSessionStage(99))
        assertEquals(1, poll.getInt("schemaVersion")); assertEquals("not_started", poll.getString("status"))
        assertEquals("NO_JOB", poll.getString("code")); assertEquals(0L, poll.getLong("totalPixels"))
        assertEquals(99L, poll.getLong("token"))
    }
    @Test fun operationSnapshotAndGenerationsDoNotAliasCallerMutableState() {
        val operations = mutableSetOf(GpuRenderOperation.FILM_EXPOSURE)
        val first = frame(operations)
        operations.clear(); operations.add(GpuRenderOperation.FILM_GRAIN)
        val second = frame(operations)
        assertEquals(setOf(GpuRenderOperation.FILM_EXPOSURE), first.allowedOperations)
        assertEquals(setOf(GpuRenderOperation.FILM_GRAIN), second.allowedOperations)
        assertTrue(second.generation > first.generation)
        assertNull(first.controller); assertNull(second.controller)
    }
}
