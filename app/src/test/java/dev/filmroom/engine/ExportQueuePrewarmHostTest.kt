package dev.filmroom.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExportQueuePrewarmHostTest {
    @Test fun queueActivityCancelsAnotherEngineOwnerAndBlocksFreshIdleAdmission()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val editorEngine=FilmEngine(app);val exportEngine=FilmEngine(app)
        val generation=FilmEngine::class.java.getDeclaredField("prewarmGeneration").apply{isAccessible=true}
        fun token(engine:FilmEngine)=(generation.get(engine) as AtomicLong).get()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val memory={NativeMemorySnapshot(4L*1024*1024*1024,0,false)}
        val before=token(editorEngine)
        val idle=launch(Dispatchers.Default) {
            NativeWorkReservations.tryRunIdle(64L*NativeMemoryPolicy.MIB,memory,editorEngine::cancelPrewarm) {
                started.complete(Unit);release.await();"done"
            }
        }
        try {
            withTimeout(5000){started.await()}
            exportEngine.cancelPrewarm()
            assertEquals("Per-engine cancellation alone cannot reach the editor",before,token(editorEngine))
            ExportWorkAdmissions.withQueueActivity {
                assertEquals(before+1,token(editorEngine))
                assertTrue(ExportWorkAdmissions.prewarmBlocked);assertFalse(ExportWorkAdmissions.active.value)
                assertFalse("Cancellation must not release a still-running native lease",NativeWorkReservations.detailRetryIdle())
                release.complete(Unit);withTimeout(5000){idle.join()}
                assertTrue(NativeWorkReservations.detailRetryIdle())
                assertNull(NativeWorkReservations.tryRunIdle(64L*NativeMemoryPolicy.MIB,memory,{}){error("fresh idle started during warm export")})
            }
            assertFalse(ExportWorkAdmissions.prewarmBlocked)
        } finally {release.complete(Unit);idle.cancelAndJoin();editorEngine.close();exportEngine.close()}
    }
}
