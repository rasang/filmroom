package dev.filmroom.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NegativePreviewStoreTest {
    private fun temp(block:(File)->Unit) {val dir=Files.createTempDirectory("negative-preview").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    @Test fun matchesOnlyCurrentAttemptAndHonorsPendingReadyAndTimeout()=temp {dir->
        val id=ScanPreviewStore.begin(dir);val status=File(dir,"negative-preview-$id.status")
        status.writeText("pending\n");val image=File(dir,"negative-preview-$id.png");image.writeText("pixels")
        val now=System.currentTimeMillis();status.setLastModified(now)
        assertTrue(NegativePreviewStore.pending(dir,now));assertNull(NegativePreviewStore.select(dir))
        assertTrue(NegativePreviewStore.pending(dir,now+60_000))
        assertTrue(NegativePreviewStore.pending(dir,now+NegativePreviewStore.PENDING_GRACE_MS))
        assertFalse(NegativePreviewStore.pending(dir,now+NegativePreviewStore.PENDING_GRACE_MS+1))
        status.writeText("ready\n");assertEquals(image,NegativePreviewStore.select(dir))
        assertTrue(NegativePreviewStore.current(dir,image))
        ScanPreviewStore.begin(dir);assertFalse(NegativePreviewStore.current(dir,image))
        image.writeText("late old attempt");status.writeText("ready")
        assertNull(NegativePreviewStore.select(dir))
    }
    @Test fun earlyPromotesToExactAndAcceptedExactNeverDowngrades()=temp {dir->
        val id=ScanPreviewStore.begin(dir)
        val early=File(dir,"negative-preview-early-$id.png");val exact=File(dir,"negative-preview-$id.png")
        File(dir,"negative-preview-early-$id.status").writeText("pending")
        File(dir,"negative-preview-$id.status").writeText("pending")
        assertTrue(NegativePreviewStore.pending(dir))
        early.writeText("physical low resolution");File(dir,"negative-preview-early-$id.status").writeText("ready")
        assertEquals(early,NegativePreviewStore.select(dir))
        exact.writeText("full image density samples");File(dir,"negative-preview-$id.status").writeText("ready")
        assertEquals(exact,NegativePreviewStore.select(dir,early.name))
        assertEquals(exact,NegativePreviewStore.select(dir,exact.name))
        assertTrue(NegativePreviewStore.current(dir,early));assertTrue(NegativePreviewStore.current(dir,exact))
        assertTrue(NegativePreviewStore.retain(dir,early));assertTrue(NegativePreviewStore.retain(dir,exact))
        exact.delete()
        assertNull(NegativePreviewStore.select(dir,exact.name))
        assertEquals(early,NegativePreviewStore.select(dir,early.name))
        ScanPreviewStore.clear(dir);assertFalse(early.exists());assertFalse(exact.exists())
    }
    @Test fun laterQualityUpgradeKeepsEarliestAvailabilityAndCorruptCandidateCanFallBackBeforeAcceptance()=temp {dir->
        val id=ScanPreviewStore.begin(dir)
        val early=File(dir,"negative-preview-early-$id.png").apply{writeText("early");setLastModified(1000)}
        File(dir,"negative-preview-early-$id.status").apply{writeText("ready");setLastModified(1000)}
        assertEquals(1000L,NegativePreviewStore.readyAtMillis(dir))
        val exact=File(dir,"negative-preview-$id.png").apply{writeText("bad PNG handled by decoder");setLastModified(2000)}
        File(dir,"negative-preview-$id.status").apply{writeText("ready");setLastModified(2000)}
        assertEquals(listOf(exact,early),NegativePreviewStore.alternatives(dir,early.name))
        assertEquals(1000L,NegativePreviewStore.readyAtMillis(dir))
        assertEquals(listOf(exact),NegativePreviewStore.alternatives(dir,exact.name))
    }
    @Test fun gpuToCpuFallbackSelectsFreshFixedNegativeDespiteGpuStatuses()=temp {dir->
        val id=ScanPreviewStore.begin(dir)
        File(dir,"negative-preview-early-$id.status").writeText("pending")
        File(dir,"negative-preview-$id.status").writeText("unavailable")
        val cpu=File(dir,"negative-preview.png");cpu.writeText("fresh current-attempt CPU density")
        assertEquals(cpu,NegativePreviewStore.select(dir))
        assertEquals(cpu,NegativePreviewStore.select(dir,cpu.name))
        assertTrue(NegativePreviewStore.current(dir,cpu))
        assertTrue(NegativePreviewStore.alternatives(dir).contains(cpu))
        val early=File(dir,"negative-preview-early-$id.png");early.writeText("late early GPU preview")
        File(dir,"negative-preview-early-$id.status").writeText("ready")
        assertEquals(cpu,NegativePreviewStore.select(dir,cpu.name))
        assertTrue(NegativePreviewStore.current(dir,cpu))
        assertTrue(NegativePreviewStore.alternatives(dir).containsAll(listOf(cpu,early)))
        ScanPreviewStore.begin(dir)
        assertFalse(cpu.exists());assertFalse(NegativePreviewStore.current(dir,cpu))
    }
    @Test fun cancellationInvalidatesAndCpuFixedNameIsClearedOnlyAtNewAttempt()=temp {dir->
        ScanPreviewStore.begin(dir);val legacy=File(dir,"negative-preview.png");legacy.writeText("cpu")
        assertEquals(legacy,NegativePreviewStore.select(dir))
        ScanPreviewStore.clear(dir);assertTrue(legacy.exists())
        ScanPreviewStore.begin(dir);assertFalse(legacy.exists())
    }
    @Test fun restartDoesNotWaitForOrphanPendingAndInvalidFilesAreIgnored()=temp {dir->
        val id="12345678-1234-1234-1234-123456789abc"
        File(dir,"scan-preview-attempt").writeText(id)
        File(dir,"negative-preview-$id.status").writeText("pending")
        assertFalse(NegativePreviewStore.pending(dir))
        File(dir,"negative-preview-$id.status").writeText("unavailable")
        assertNull(NegativePreviewStore.select(dir))
        File(dir,"negative-preview-$id.png").writeBytes(ByteArray(524289))
        File(dir,"negative-preview-$id.status").writeText("ready")
        assertNull(NegativePreviewStore.select(dir))
    }
}
