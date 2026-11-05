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
    @Test fun earlyAndExactAreIndependentAndAcceptedChoiceCanStayFrozen()=temp {dir->
        val id=ScanPreviewStore.begin(dir)
        val early=File(dir,"negative-preview-early-$id.png");val exact=File(dir,"negative-preview-$id.png")
        File(dir,"negative-preview-early-$id.status").writeText("pending")
        File(dir,"negative-preview-$id.status").writeText("pending")
        assertTrue(NegativePreviewStore.pending(dir))
        early.writeText("physical low resolution");File(dir,"negative-preview-early-$id.status").writeText("ready")
        assertEquals(early,NegativePreviewStore.select(dir))
        exact.writeText("full image density samples");File(dir,"negative-preview-$id.status").writeText("ready")
        assertEquals(early,NegativePreviewStore.select(dir,early.name))
        assertEquals(exact,NegativePreviewStore.select(dir,exact.name))
        assertTrue(NegativePreviewStore.current(dir,early));assertTrue(NegativePreviewStore.current(dir,exact))
        assertTrue(NegativePreviewStore.retain(dir,early));assertTrue(NegativePreviewStore.retain(dir,exact))
        ScanPreviewStore.clear(dir);assertFalse(early.exists());assertFalse(exact.exists())
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
