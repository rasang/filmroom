package dev.filmroom.io

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.engine.NativeWorkUnavailable
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class RefinedPreviewSourceOwnerTest {
    private fun photo()=PhotoSource(File("unused-${java.util.UUID.randomUUID()}"),"source","image/jpeg",6000,4000,0)
    private class Harness {
        var loads=0;var reserved=0L;var reject=false
        val pressure=mutableListOf<()->Unit>()
        val owner=RefinedPreviewSourceOwner({bytes,callback->
            if(reject)throw NativeWorkUnavailable()
            reserved+=bytes;pressure+=callback
            AutoCloseable{reserved-=bytes}
        },{loads++;RefinedPreviewInput(bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888))})
    }
    @Test fun oneInputIsReusedAndSourceChangeRetiresOldPixels()=runBlocking {
        val h=Harness();val photo=photo()
        val first=h.owner.acquire(photo);val image=first.input.bitmap!!;first.close()
        assertFalse(image.isRecycled);assertTrue(h.reserved>0)
        h.owner.acquire(photo).use{assertSame(image,it.input.bitmap)}
        assertEquals(1,h.loads)
        h.owner.acquire(photo()).use{assertNotSame(image,it.input.bitmap)}
        assertTrue(image.isRecycled);assertEquals(2,h.loads)
        h.owner.close();assertEquals(0L,h.reserved)
    }
    @Test fun pressurePinsBorrowedPixelsAndOldCallbackCannotRetireNewEntry()=runBlocking {
        val h=Harness();val first=h.owner.acquire(photo());val image=first.input.bitmap!!
        val old=h.pressure.last();old()
        assertFalse(image.isRecycled);assertTrue(h.reserved>0)
        first.close();first.close();assertTrue(image.isRecycled);assertEquals(0L,h.reserved)
        val next=h.owner.acquire(photo());val current=next.input.bitmap!!
        old();next.close();assertFalse(current.isRecycled);assertTrue(h.reserved>0)
        h.owner.close();assertTrue(current.isRecycled);assertEquals(0L,h.reserved)
    }
    @Test fun cacheDenialKeepsActiveBorrowButRetainsNothingAfterIt()=runBlocking {
        val h=Harness();h.reject=true;val photo=photo()
        val first=h.owner.acquire(photo);val image=first.input.bitmap!!
        assertFalse(image.isRecycled);assertEquals(0L,h.reserved)
        first.close();assertTrue(image.isRecycled)
        h.owner.acquire(photo).close();assertEquals(2,h.loads);h.owner.close()
    }
    @Test fun closeWaitsForReaderAndPreventsNewAcquisition()=runBlocking {
        val h=Harness();val photo=photo();val first=h.owner.acquire(photo);val image=first.input.bitmap!!
        h.owner.close();assertFalse(image.isRecycled);assertTrue(h.reserved>0)
        try{h.owner.acquire(photo);fail("closed owner admitted input")}catch(_:IllegalStateException){}
        first.close();assertTrue(image.isRecycled);assertEquals(0L,h.reserved)
    }
    @Test fun changedSourceCannotPublishNewCache()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("refined-revision-").toFile()
        try {
            val file=File(root,"source").apply{writeBytes(byteArrayOf(1))}
            val photo=PhotoSource(file,"source","image/jpeg",32,24,1)
            lateinit var image:Bitmap
            var reservations=0
            val owner=RefinedPreviewSourceOwner({_,_->reservations++;AutoCloseable{}},{
                image=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
                file.appendBytes(byteArrayOf(2));RefinedPreviewInput(bitmap=image)
            })
            try{owner.acquire(photo);fail("changed source published")}catch(_:IllegalStateException){}
            assertTrue(image.isRecycled);assertEquals(0,reservations);owner.close()
        }finally{root.deleteRecursively()}
    }
    @Test fun cancelledColdLoadCannotPublishOrLeakPixels()=runTest {
        lateinit var image:Bitmap
        var reservations=0
        val owner=RefinedPreviewSourceOwner({_,_->reservations++;AutoCloseable{}},{
            image=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
            withContext(NonCancellable){delay(100)}
            RefinedPreviewInput(bitmap=image)
        })
        val work=launch{owner.acquire(photo()).close()};runCurrent();work.cancel();advanceUntilIdle()
        assertTrue(image.isRecycled);assertEquals(0,reservations);owner.close()
    }
}
