package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewCacheLeaseTest {
    @Test fun pressureDuringAdmissionWaitReleasesBeforeNativeBorrow()=runBlocking {
        val limiter=NativeWorkLimiter()
        val mib=1024L*1024
        val memory={NativeMemorySnapshot(1024*mib,64*mib,false)}
        lateinit var owner:PreviewCacheLease
        var released=0
        val passive=limiter.retain(32*mib,memory()){owner.retire()}
        owner=PreviewCacheLease{released++;passive.close()}
        // Same owner state as FilmEngine waiting for active JNI admission: no borrow yet.
        val reduced={NativeMemorySnapshot(180*mib,64*mib,false)} // 26 MiB capacity, enough for ordinary work plus 16 MiB safety
        withTimeout(2000){limiter.run(1,reduced){assertEquals(1,released);assertFalse(owner.borrow())}}
        assertEquals(0,limiter.reservedBytes());assertEquals(0,limiter.activeCount())
    }
    @Test fun pressureDuringJniDefersReleaseAndRetiresExactlyOnce(){
        var released=0;val owner=PreviewCacheLease{released++}
        assertTrue(owner.borrow());owner.retire();owner.retire();assertEquals(0,released)
        owner.finish(true);assertEquals(1,released);assertFalse(owner.borrow());owner.finish(false);assertEquals(1,released)
    }
    @Test fun successfulFramesReuseButFailureAndCloseRelease(){
        var released=0;val owner=PreviewCacheLease{released++}
        assertTrue(owner.borrow());owner.finish(true);assertEquals(0,released)
        assertTrue(owner.borrow());owner.finish(false);assertEquals(1,released)
        owner.retire();assertEquals(1,released)
        val idle=PreviewCacheLease{released++};idle.retire();assertEquals(2,released)
    }
}
