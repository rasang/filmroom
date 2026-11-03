package dev.filmroom.engine

import android.app.Application
import android.os.Binder
import android.os.IBinder
import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.lifecycle.StartupRecoveryGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuHelperProcessLockTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun heldKernelLockTimesOutWithoutRecoveryAndRetrySucceedsAfterRelease() = runBlocking {
        val file = temporary.newFile("gpu-worker.lock")
        val oldHelper = GpuProcessFileGate.tryAcquire(file)!!
        var cleanups = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = StartupRecoveryGate(scope) {
                val permit = GpuProcessFileGate.await(file, 30) ?: return@StartupRecoveryGate PrivateWorkRecovery.Report(0, 1)
                try { cleanups++; PrivateWorkRecovery.Report(1, 0) } finally { permit.close() }
            }
            assertTrue(withTimeout(2000) { gate.awaitAttempt() } is StartupRecoveryGate.State.Failed)
            assertEquals(0, cleanups)
            oldHelper.close()
            assertTrue(gate.retry())
            assertTrue(withTimeout(2000) { gate.awaitAttempt() } is StartupRecoveryGate.State.Ready)
            assertEquals(1, cleanups)
            GpuProcessFileGate.tryAcquire(file)!!.close()
        } finally { scope.cancel() }
    }
    @Test fun secondHelperCannotAcquireUntilKernelLeaseCloses() {
        val file = temporary.newFile("gpu-worker.lock")
        val first = GpuProcessFileGate.tryAcquire(file)!!
        assertNull(GpuProcessFileGate.tryAcquire(file))
        first.close()
        GpuProcessFileGate.tryAcquire(file)!!.close()
    }
    @Test fun startupCleanupRemovesOnlyKnownGeneratedPacketAndPreservesHardlinkOriginal() {
        val cache = temporary.newFolder("cache")
        val source = File(cache, "original").apply { writeText("original data") }
        val packet = File(cache, "gpu-render/gpu-stage-91").apply { mkdirs() }
        Files.createLink(File(packet, "source-0.f64le").toPath(), source.toPath())
        File(packet, ".manifest.partial").writeText("partial")
        val candidate = File(packet, "candidate").apply { mkdir() }
        File(candidate, "plane-0.f64le").writeText("partial output")
        File(candidate, ".complete.partial").writeText("partial marker")
        val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
        try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
        assertFalse(packet.exists()); assertEquals("original data", source.readText())
    }
    @Test fun scannerRecoveryRemovesOnlyOwnedTablesAndCandidateAfterExclusiveGate() {
        val cache = temporary.newFolder("scanner-cache")
        val source = File(cache, "original").apply { writeText("original data") }
        val packet = File(cache, "gpu-render/gpu-stage-94").apply { mkdirs() }
        Files.createLink(File(packet, "source-0.f64le").toPath(), source.toPath())
        File(packet, "tables.f64le").writeText("private tables")
        File(packet, "manifest.json").writeText("ready")
        val candidate = File(packet, "candidate").apply { mkdir() }
        File(candidate, "candidate.f64le").writeText("partial logXYZ")
        File(candidate, ".complete.partial").writeText("not committed")
        val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
        try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
        assertFalse(packet.exists()); assertEquals("original data", source.readText())
    }
    @Test fun exposureRecoveryRemovesOnlyOwnedRgbSnapshotAndPreservesOriginal() {
        val cache = temporary.newFolder("exposure-cache")
        val source = File(cache, "original.rgb").apply { writeText("original rgb") }
        val packet = File(cache, "gpu-render/gpu-stage-95").apply { mkdirs() }
        Files.createLink(File(packet, "source.rgb.f32le").toPath(), source.toPath())
        File(packet, "tables.f64le").writeText("private tables")
        File(packet, ".manifest.partial").writeText("partial")
        val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
        try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
        assertFalse(packet.exists()); assertEquals("original rgb", source.readText())
    }
    @Test fun scientificRecoveryRecognizesMeterAndTailFilesWithoutFollowingLinks() {
        val cache=temporary.newFolder("science-cache")
        val outside=File(cache,"original").apply{writeText("keep original")}
        for((index,files) in listOf(listOf("source.meter.f32le" to "meter.f32le"),listOf("source.logxyz.f64le" to "candidate.rgb.f32le","source.glare.f64le" to "candidate.rgb.f32le")).withIndex()) {
            val packet=File(cache,"gpu-render/gpu-stage-${201+index}").apply{mkdirs()}
            val candidate=File(packet,"candidate").apply{mkdir()}
            File(packet,".manifest.partial").writeText("owned")
            for((source,output) in files){Files.createLink(File(packet,source).toPath(),outside.toPath());File(candidate,output).writeText("owned output")}
            File(candidate,".complete.partial").writeText("owned")
            val gate=GpuProcessFileGate.tryAcquire(File(cache,"gate"))!!
            try{assertEquals(0,GpuRenderRecovery.recover(cache).failed)}finally{gate.close()}
            assertFalse(packet.exists());assertEquals("keep original",outside.readText())
        }
        for((index,name) in listOf("source.meter.f32le","source.logxyz.f64le","source.glare.f64le").withIndex()) {
            val packet=File(cache,"gpu-render/gpu-stage-${210+index}").apply{mkdirs()}
            Files.createSymbolicLink(File(packet,name).toPath(),outside.toPath())
            val gate=GpuProcessFileGate.tryAcquire(File(cache,"gate"))!!
            try{GpuRenderRecovery.recover(cache)}finally{gate.close()}
            assertTrue(Files.isSymbolicLink(File(packet,name).toPath()));assertEquals("keep original",outside.readText())
        }
    }
    @Test fun exposurePartialCandidateDirectoryCrashIsRecoveredWithoutRemovingOriginal() {
        val cache = temporary.newFolder("exposure-partial-cache")
        val source = File(cache, "original.rgb").apply { writeText("original rgb") }
        val packet = File(cache, "gpu-render/gpu-stage-101").apply { mkdirs() }
        Files.createLink(File(packet, "source.rgb.f32le").toPath(), source.toPath())
        File(packet, "manifest.json").writeText("ready")
        val partial = File(packet, ".candidate.partial").apply { mkdir() }
        for (channel in 0..2) File(partial, "plane-$channel.f64le").writeText("partial output")
        File(partial, "complete.json").writeText("interrupted publication")
        val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
        try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
        assertFalse(packet.exists()); assertEquals("original rgb", source.readText())
    }
    @Test fun opticalAndDirTemporaryCompletionMarkersAreRecovered() {
        for ((index, stage) in listOf("optical", "dir").withIndex()) {
            val cache = temporary.newFolder("$stage-complete-cache")
            val source = File(cache, "original").apply { writeText("original data") }
            val packet = File(cache, "gpu-render/gpu-stage-${102 + index}").apply { mkdirs() }
            Files.createLink(File(packet, "source-0.f64le").toPath(), source.toPath())
            File(packet, "manifest.json").writeText("ready")
            val candidate = File(packet, "candidate").apply { mkdir() }
            File(candidate, "plane-0.f64le").writeText("complete plane")
            File(candidate, "complete.tmp").writeText("partial completion marker")
            val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
            try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
            assertFalse(packet.exists()); assertEquals("original data", source.readText())
        }
    }
    @Test fun opticalAndDirTemporaryManifestCrashesAreRecovered() {
        for ((index, stage) in listOf("optical", "dir").withIndex()) {
            val cache = temporary.newFolder("$stage-manifest-cache")
            val source = File(cache, "original").apply { writeText("original data") }
            val packet = File(cache, "gpu-render/gpu-stage-${104 + index}").apply { mkdirs() }
            Files.createLink(File(packet, "source-0.f64le").toPath(), source.toPath())
            File(packet, "manifest.tmp").writeText("partial manifest")
            val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
            try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
            assertFalse(packet.exists()); assertEquals("original data", source.readText())
        }
    }
    @Test fun newTemporaryNamesNeverFollowSymlinksOrSweepUnknownChildren() {
        val cache = temporary.newFolder("temporary-symlink-cache")
        val original = File(cache, "original").apply { writeText("keep original") }
        val outsideDirectory = File(cache, "outside").apply { mkdir() }
        val outsidePlane = File(outsideDirectory, "plane-0.f64le").apply { writeText("keep outside") }
        fun packet(token: Int) = File(cache, "gpu-render/gpu-stage-$token").apply { mkdirs() }.also {
            File(it, "source-0.f64le").writeText("keep packet")
        }
        val manifestLink = packet(106)
        Files.createSymbolicLink(File(manifestLink, "manifest.tmp").toPath(), original.toPath())
        val directoryLink = packet(107)
        Files.createSymbolicLink(File(directoryLink, ".candidate.partial").toPath(), outsideDirectory.toPath())
        val completionLink = packet(108)
        val candidate = File(completionLink, "candidate").apply { mkdir() }
        Files.createSymbolicLink(File(candidate, "complete.tmp").toPath(), original.toPath())
        val unknown = packet(109)
        // Even a valid sibling candidate must be preserved if the partial subtree is unknown.
        File(unknown, "candidate").mkdir()
        File(unknown, "candidate/plane-0.f64le").writeText("keep valid candidate")
        File(unknown, ".candidate.partial").mkdir()
        File(unknown, ".candidate.partial/unowned").writeText("keep unknown")
        val lock = GpuProcessFileGate.tryAcquire(File(cache, "gate"))!!
        try { assertEquals(0, GpuRenderRecovery.recover(cache).failed) } finally { lock.close() }
        for (packet in listOf(manifestLink, directoryLink, completionLink, unknown)) assertEquals("keep packet", File(packet, "source-0.f64le").readText())
        assertTrue(Files.isSymbolicLink(File(manifestLink, "manifest.tmp").toPath()))
        assertTrue(Files.isSymbolicLink(File(directoryLink, ".candidate.partial").toPath()))
        assertTrue(Files.isSymbolicLink(File(candidate, "complete.tmp").toPath()))
        assertEquals("keep valid candidate", File(unknown, "candidate/plane-0.f64le").readText())
        assertEquals("keep unknown", File(unknown, ".candidate.partial/unowned").readText())
        assertEquals("keep original", original.readText()); assertEquals("keep outside", outsidePlane.readText())
    }
    @Test fun startupCleanupNeverFollowsSymlinkOrSweepsUnknownPacketChildren() {
        val cache = temporary.newFolder("cache")
        val outside = File(cache, "original").apply { writeText("keep") }
        val packet = File(cache, "gpu-render/gpu-stage-92").apply { mkdirs() }
        Files.createSymbolicLink(File(packet, "source-0.f64le").toPath(), outside.toPath())
        val unknown = File(cache, "gpu-render/gpu-stage-93").apply { mkdirs() }
        File(unknown, "unknown").writeText("keep")
        GpuRenderRecovery.recover(cache)
        assertEquals("keep", outside.readText()); assertTrue(Files.isSymbolicLink(File(packet, "source-0.f64le").toPath()))
        assertTrue(File(unknown, "unknown").exists())
    }
    @Test fun residentPrefixRecoveryUsesFixedDepthSameTokenAndNeverSweepsUnknown(){
        val cache=temporary.newFolder("resident-prefix")
        val original=File(cache,"original").apply{writeText("keep source")}
        fun packet(token:Int)=File(cache,"gpu-render/gpu-stage-$token").apply{mkdirs();File(this,"manifest.json").writeText("owned")}
        val good=packet(120);val nested=File(good,"prefix/gpu-stage-120").apply{mkdirs()}
        Files.createLink(File(nested,"source-0.f64le").toPath(),original.toPath());File(nested,".manifest.partial").writeText("partial")
        val mismatch=packet(121);File(mismatch,"prefix/gpu-stage-122").mkdirs()
        val unknown=packet(123);File(unknown,"prefix/gpu-stage-123").mkdirs();File(unknown,"prefix/gpu-stage-123/unknown").writeText("keep")
        val link=packet(124);File(link,"prefix").mkdir();Files.createSymbolicLink(File(link,"prefix/gpu-stage-124").toPath(),nested.toPath())
        val linkedFile=packet(125);File(linkedFile,"prefix/gpu-stage-125").mkdirs();Files.createSymbolicLink(File(linkedFile,"prefix/gpu-stage-125/tables.f64le").toPath(),original.toPath())
        val lock=GpuProcessFileGate.tryAcquire(File(cache,"gate"))!!
        try{assertEquals(0,GpuRenderRecovery.recover(cache).failed)}finally{lock.close()}
        assertFalse(good.exists());assertEquals("keep source",original.readText())
        for(p in listOf(mismatch,unknown,link,linkedFile))assertEquals("owned",File(p,"manifest.json").readText())
        assertEquals("keep",File(unknown,"prefix/gpu-stage-123/unknown").readText())
    }
    private class Parent : Binder() {
        var alive = true
        private var recipient: IBinder.DeathRecipient? = null
        override fun isBinderAlive(): Boolean = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
        fun die() { alive = false; recipient?.binderDied() }
    }
    @Test fun parentDeathBetweenLockAndNativePermissionPreventsJni() {
        val parent = Parent(); var kills = 0; var nativeRuns = 0
        val watch = GpuHelperParentWatch { kills++ }
        assertTrue(watch.attach(parent))
        if (watch.claimNative { parent.die(); true }) nativeRuns++
        assertEquals(0, nativeRuns); assertEquals(1, kills); assertFalse(watch.isAlive())
    }
    @Test fun alreadyDeadParentNeverAcquiresGpuLock() {
        val parent = Parent().apply { alive = false }; var locks = 0
        val watch = GpuHelperParentWatch { }
        assertFalse(watch.attach(parent))
        assertFalse(watch.claimNative { locks++; true })
        assertEquals(0, locks)
    }
}
