package dev.filmroom.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProcessExitApi29HostTest {
    @Test fun exitReasonIsUnknownOnAndroid10() {
        val dir = Files.createTempDirectory("exit-api29").toFile()
        try {
            val run = "a".repeat(32)
            dir.resolve("process.marker").writeText("$run\t123\t1000\n")
            val tracker = ProcessExitTracker(ApplicationProvider.getApplicationContext<Application>(), dir)
            assertEquals(ProcessExitReason.UNKNOWN, tracker.previous(run, 1000, 1200, 2000))
        } finally { dir.deleteRecursively() }
    }
}
