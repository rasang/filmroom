package dev.filmroom.io

import dev.filmroom.engine.NativeMemorySnapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DetailGridCacheHostTest {
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,0,false)}
    private class Fixture {
        val root=Files.createTempDirectory("detail-grid-cache").toFile().canonicalFile
        val original=File(root,"original.raw").apply{writeText("owned original")}
        val linear=File(root,"linear.f32le").apply {
            val bytes=ByteBuffer.allocate(8*4*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(32){bytes.putFloat(-.5f).putFloat(2f).putFloat(.25f)};writeBytes(bytes.array())
        }
        val photo=PhotoSource(original,"source","image/heif",8,4,original.length(),linearFile=linear)
        var builds=0
        fun factory()=DetailSourceFactory(root,{NativeMemorySnapshot(8L*1024*1024*1024,0,false)}){builds++;it.writeText("filmroom-detail-v1")}
        fun grid(source:DetailSource)=File(root,"cache/detail-preview/${source.relativePath}")
        suspend fun close(){try{photo.clearLinearPreview();photo.detailGridCache.awaitRetirements()}finally{root.deleteRecursively()}}
    }
    @Test fun newFactorySamePhotoHitsSameGridAndKeepsExactHashes()=runBlocking {
        val f=Fixture()
        try {
            val first=f.factory().prepare(f.photo);val bytes=f.grid(first).readBytes();first.close()
            val second=f.factory().prepare(f.photo)
            assertFalse(first.cacheHit);assertTrue(second.cacheHit)
            assertEquals(1,f.builds);assertEquals(first.relativePath,second.relativePath)
            assertEquals(first.sourceRevision,second.sourceRevision);assertEquals(first.mappingRevision,second.mappingRevision)
            assertArrayEquals(bytes,f.grid(second).readBytes());second.close()
            assertTrue(f.grid(second).exists());f.photo.clearLinearPreview();f.photo.detailGridCache.awaitRetirements();assertFalse(f.grid(second).exists())
            assertTrue(f.original.exists());assertTrue(f.linear.exists())
        }finally{f.close()}
    }
    @Test fun maxEdgeWithSameActualGridSharesButDifferentGridReplacesSlot()=runBlocking {
        val f=Fixture()
        try {
            val first=f.factory().prepare(f.photo,1536)
            val same=f.factory().prepare(f.photo,768);assertEquals(first.relativePath,same.relativePath);same.close()
            val small=f.factory().prepare(f.photo,4);assertNotEquals(first.relativePath,small.relativePath);assertEquals(2,f.builds)
            assertTrue(f.grid(first).exists());first.close();f.photo.detailGridCache.awaitRetirements();assertFalse(f.grid(first).exists());small.close()
        }finally{f.close()}
    }
    @Test fun sameFilesInDifferentPhotoObjectsNeverShareCache()=runBlocking {
        val f=Fixture();val other=PhotoSource(f.original,"other","image/heif",8,4,f.original.length(),linearFile=f.linear)
        try {
            val first=f.factory().prepare(f.photo);val second=f.factory().prepare(other)
            assertNotEquals(first.relativePath,second.relativePath);assertNotEquals(first.mappingRevision,second.mappingRevision)
            assertEquals(first.sourceRevision,second.sourceRevision);assertEquals(2,f.builds)
            first.close();second.close()
        }finally{other.clearLinearPreview();other.detailGridCache.awaitRetirements();f.close()}
    }
    @Test fun samePhotoDifferentTrustedRootUsesDifferentGridAndOldReaderSurvives()=runBlocking {
        val outer=Files.createTempDirectory("detail-cache-roots").toFile().canonicalFile
        val root=File(outer,"inner").apply{mkdir()}
        val original=File(root,"original").apply{writeText("owned")};val linear=File(root,"linear").apply{writeBytes(ByteArray(12))}
        val photo=PhotoSource(original,"source","image/heif",1,1,original.length(),linearFile=linear)
        try {
            val first=DetailSourceFactory(root,memory).prepare(photo)
            val second=DetailSourceFactory(outer,memory).prepare(photo)
            val old=File(root,"cache/detail-preview/${first.relativePath}")
            assertNotEquals(first.relativePath,second.relativePath);assertTrue(old.exists())
            first.close();photo.detailGridCache.awaitRetirements();assertFalse(old.exists());second.close()
        }finally{photo.clearLinearPreview();photo.detailGridCache.awaitRetirements();outer.deleteRecursively()}
    }
    @Test fun inputModificationWithRestoredMtimeAndSameSizeInvalidatesSnapshotCache()=runBlocking {
        val f=Fixture()
        try {
            val first=f.factory().prepare(f.photo);first.close()
            val time=Files.getLastModifiedTime(f.linear.toPath());val bytes=f.linear.readBytes()
            bytes[0]=(bytes[0].toInt() xor 1).toByte();f.linear.writeBytes(bytes);Files.setLastModifiedTime(f.linear.toPath(),time)
            val next=f.factory().prepare(f.photo)
            assertNotEquals(first.relativePath,next.relativePath);assertNotEquals(first.sourceRevision,next.sourceRevision)
            assertEquals(2,f.builds);f.photo.detailGridCache.awaitRetirements();assertFalse(f.grid(first).exists());next.close()
        }finally{f.close()}
    }
    @Test fun inputReplacementWithSameLengthAndMtimeInvalidatesFileIdentity()=runBlocking {
        val f=Fixture()
        try {
            val first=f.factory().prepare(f.photo);first.close()
            val time=Files.getLastModifiedTime(f.linear.toPath())
            val replacement=File(f.root,"replacement").apply{writeBytes(f.linear.readBytes())}
            Files.setLastModifiedTime(replacement.toPath(),time)
            Files.move(replacement.toPath(),f.linear.toPath(),StandardCopyOption.REPLACE_EXISTING)
            val next=f.factory().prepare(f.photo)
            assertNotEquals(first.relativePath,next.relativePath);assertEquals(first.sourceRevision,next.sourceRevision)
            assertEquals(2,f.builds);next.close()
        }finally{f.close()}
    }
    @Test fun missingTruncatedAndModifiedGridRebuildWithoutReusingRecordedGridHash()=runBlocking {
        val f=Fixture()
        try {
            var previous=f.factory().prepare(f.photo).also{it.close()}
            for(mode in listOf("missing","truncated","same-size")) {
                val grid=f.grid(previous)
                when(mode){
                    "missing"->assertTrue(grid.delete())
                    "truncated"->grid.writeBytes(byteArrayOf(1))
                    else->{val time=Files.getLastModifiedTime(grid.toPath());val bytes=grid.readBytes();bytes[0]=(bytes[0].toInt() xor 1).toByte();grid.writeBytes(bytes);Files.setLastModifiedTime(grid.toPath(),time)}
                }
                val next=f.factory().prepare(f.photo)
                assertNotEquals(previous.relativePath,next.relativePath)
                assertEquals(previous.sourceRevision,next.sourceRevision);assertEquals(previous.mappingRevision,next.mappingRevision)
                next.close();previous=next
            }
            assertEquals(4,f.builds)
        }finally{f.close()}
    }
    @Test fun replacedOwnershipMarkerOrLeaseInvalidatesCachedGrid()=runBlocking {
        for(name in listOf(".owned-detail-source-v1",".lease")) {
            val f=Fixture()
            try {
                val first=f.factory().prepare(f.photo);first.close()
                val file=File(f.grid(first).parentFile,name)
                val replacement=File(f.root,"replacement").apply{writeBytes(file.readBytes())}
                Files.move(replacement.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)
                val next=f.factory().prepare(f.photo)
                assertNotEquals(first.relativePath,next.relativePath);assertEquals(2,f.builds);next.close()
            }finally{f.close()}
        }
    }
    @Test fun inputSymlinkAndHardlinkAreRejectedBeforeCacheHit()=runBlocking {
        val f=Fixture()
        try {
            f.factory().prepare(f.photo).close()
            val saved=File(f.root,"saved");Files.move(f.linear.toPath(),saved.toPath())
            Files.createSymbolicLink(f.linear.toPath(),saved.toPath())
            try{f.factory().prepare(f.photo);fail()}catch(_:IllegalArgumentException){}
            Files.delete(f.linear.toPath());Files.createLink(f.linear.toPath(),saved.toPath())
            try{f.factory().prepare(f.photo);fail()}catch(_:IllegalArgumentException){}
            assertEquals(1,f.builds);assertTrue(saved.exists())
        }finally{f.close()}
    }
    @Test fun cachedGridSymlinkAndHardlinkCannotBeConsumed()=runBlocking {
        for(hardlink in listOf(false,true)) {
            val f=Fixture()
            try {
                val first=f.factory().prepare(f.photo);first.close();val grid=f.grid(first)
                val outside=File(f.root,"outside").apply{writeBytes(grid.readBytes())};grid.delete()
                if(hardlink)Files.createLink(grid.toPath(),outside.toPath())else Files.createSymbolicLink(grid.toPath(),outside.toPath())
                try{f.factory().prepare(f.photo);fail()}catch(_:IllegalArgumentException){}
                assertTrue(outside.exists());assertEquals(1,f.builds)
            }finally{f.close()}
        }
    }
    @Test fun clearAndDiscardReleaseOwnerButKeepActiveGridUntilLastReader()=runBlocking {
        for(discard in listOf(false,true)) {
            val f=Fixture()
            try {
                val first=f.factory().prepare(f.photo);val second=f.factory().prepare(f.photo);val grid=f.grid(first)
                if(discard)f.photo.discardWhenUnused()else f.photo.clearLinearPreview()
                assertTrue(grid.exists());first.close();first.close();assertTrue(grid.exists())
                assertEquals(!discard,f.original.exists());assertEquals(!discard,f.linear.exists())
                second.close();f.photo.detailGridCache.awaitRetirements();assertFalse(grid.exists())
                try{f.factory().prepare(f.photo);fail()}catch(_:IllegalStateException){}
            }finally{f.close()}
        }
    }
    @Test fun cacheOwnerKeepsOrphanLeaseWithoutAnActiveSessionReader()=runBlocking {
        val f=Fixture()
        try {
            val first=f.factory().prepare(f.photo);first.close();val grid=f.grid(first)
            f.factory().cleanupOrphans();assertTrue(grid.exists())
            f.photo.clearLinearPreview();f.photo.detailGridCache.awaitRetirements();f.factory().cleanupOrphans();assertFalse(grid.exists())
        }finally{f.close()}
    }
    @Test fun asyncCleanupFailureReleasesFileLockAndLeavesRecoverableOwnershipProof()=runBlocking {
        val f=Fixture()
        try {
            val factory=f.factory();var cleanupCalls=0
            factory.beforeGridDeleteForTest={cleanupCalls++;throw java.io.IOException("Injected unlink failure")}
            val source=factory.prepare(f.photo);val grid=f.grid(source);source.close()
            f.photo.clearLinearPreview();f.photo.detailGridCache.awaitRetirements()
            assertEquals(1,cleanupCalls);assertTrue(grid.exists())
            assertEquals("filmroom-detail-v1",File(grid.parentFile,".owned-detail-source-v1").readText())
            java.io.RandomAccessFile(File(grid.parentFile,".lease"),"rw").use { lease ->
                val lock=checkNotNull(lease.channel.tryLock()){ "Retirement must release original lock despite cleanup failure" }
                lock.release()
            }
            factory.cleanupOrphans();assertFalse(grid.exists())
            f.photo.clearLinearPreview();f.photo.detailGridCache.awaitRetirements();assertEquals(1,cleanupCalls)
            assertTrue(f.original.exists());assertTrue(f.linear.exists())
        }finally{f.close()}
    }
    @Test fun failedBuildDoesNotPublishAndRetryCanSucceed()=runBlocking {
        val f=Fixture()
        try {
            val failed=DetailSourceFactory(f.root,memory){throw java.io.IOException("marker failure")}
            try{failed.prepare(f.photo);fail()}catch(_:java.io.IOException){}
            assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            f.factory().prepare(f.photo).close();assertEquals(1,f.builds)
            assertTrue(f.original.exists());assertTrue(f.linear.exists())
        }finally{f.close()}
    }
    @Test fun sourceMutationDuringColdBuildFailsWithoutPublishing()=runBlocking {
        val f=Fixture()
        try {
            val factory=DetailSourceFactory(f.root,memory){marker->
                marker.writeText("filmroom-detail-v1")
                val time=Files.getLastModifiedTime(f.linear.toPath());val bytes=f.linear.readBytes()
                bytes[0]=(bytes[0].toInt() xor 1).toByte();f.linear.writeBytes(bytes)
                Files.setLastModifiedTime(f.linear.toPath(),time)
            }
            try{factory.prepare(f.photo);fail()}catch(_:IllegalStateException){}
            f.photo.detailGridCache.awaitRetirements()
            assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            f.factory().prepare(f.photo).close();assertEquals(1,f.builds)
        }finally{f.close()}
    }
    @Test fun cancelledDispatcherReturnRollsBackNewOwnerAndReader()=runBlocking {
        val f=Fixture();val queue=LinkedBlockingQueue<Runnable>();val direct=AtomicBoolean(false)
        val dispatcher=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){if(direct.get())block.run()else queue.put(block)}}
        var published=false
        val worker=CoroutineScope(dispatcher).launch{f.factory().prepare(f.photo).use{published=true}}
        try {
            checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            val result=checkNotNull(queue.poll(10,TimeUnit.SECONDS))
            assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isNotEmpty())
            worker.cancel();result.run()
            while(!worker.isCompleted)checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            worker.join();f.photo.detailGridCache.awaitRetirements();assertFalse(published)
            assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            f.factory().prepare(f.photo).close();assertEquals(2,f.builds)
        }finally{
            direct.set(true);worker.cancel();while(true){val task=queue.poll() ?: break;task.run()}
            withTimeout(10000){worker.join()};f.close()
        }
    }
    @Test fun unsupportedOptionalMetadataFallsBackToOriginalUncachedPath()=runBlocking {
        for(unavailable in listOf<(java.nio.file.Path,java.nio.file.attribute.BasicFileAttributes)->Pair<String,java.nio.file.attribute.FileTime>?>(
            {_,_->null},{_,_->throw UnsupportedOperationException("ctime unavailable")},{_,_->throw IllegalArgumentException("unknown ctime attribute")}
        )) {
            val f=Fixture()
            try {
                val factory=f.factory().apply{cacheMetadataReader=unavailable}
                val first=factory.prepare(f.photo);val firstBytes=f.grid(first).readBytes()
                assertFalse(first.cacheHit);first.close();assertFalse(f.grid(first).exists())
                val second=factory.prepare(f.photo);assertFalse(second.cacheHit);assertNotEquals(first.relativePath,second.relativePath)
                assertArrayEquals(firstBytes,f.grid(second).readBytes());assertEquals(2,f.builds);second.close()
            }finally{f.close()}
        }
    }
    @Test fun optionalMetadataFallbackNeverBypassesScopeOrHardlinkRejection()=runBlocking {
        val f=Fixture();var metadataCalls=0
        try {
            val factory=f.factory().apply{cacheMetadataReader={_,_->metadataCalls++;null}}
            val outside=File(f.root.parentFile,"outside-${f.root.name}").apply{writeBytes(ByteArray(12))}
            try {
                val bad=PhotoSource(outside,"bad","image/heif",1,1,12,linearFile=outside)
                try{factory.prepare(bad);fail()}catch(_:IllegalArgumentException){}
                assertEquals(0,metadataCalls)
                val link=File(f.root,"hardlink");Files.createLink(link.toPath(),f.original.toPath())
                try{factory.prepare(f.photo);fail()}catch(_:IllegalArgumentException){}
                assertEquals(0,metadataCalls);assertEquals(0,f.builds)
            }finally{outside.delete()}
        }finally{f.close()}
    }

    @Test fun cancelledCacheHitDispatcherReturnKeepsOwnerForNextHit()=runBlocking {
        val f=Fixture();val queue=LinkedBlockingQueue<Runnable>();val direct=AtomicBoolean(false)
        val dispatcher=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){if(direct.get())block.run()else queue.put(block)}}
        val first=f.factory().prepare(f.photo);first.close()
        var published=false
        val worker=CoroutineScope(dispatcher).launch{f.factory().prepare(f.photo).use{published=true}}
        try {
            checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            val result=checkNotNull(queue.poll(10,TimeUnit.SECONDS))
            assertEquals(1,f.builds);assertTrue(f.grid(first).exists())
            worker.cancel();result.run()
            while(!worker.isCompleted)checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            worker.join();f.photo.detailGridCache.awaitRetirements();assertFalse(published)
            f.factory().cleanupOrphans();assertTrue(f.grid(first).exists())
            val next=f.factory().prepare(f.photo)
            try {
                assertTrue(next.cacheHit);assertEquals(1,f.builds)
                assertEquals(first.relativePath,next.relativePath)
                assertEquals(first.sourceRevision,next.sourceRevision);assertEquals(first.mappingRevision,next.mappingRevision)
            }finally{next.close()}
            f.photo.clearLinearPreview();f.photo.detailGridCache.awaitRetirements()
            assertFalse(f.grid(first).exists());assertTrue(f.original.exists());assertTrue(f.linear.exists())
        }finally{
            direct.set(true);worker.cancel();while(true){val task=queue.poll() ?: break;task.run()}
            withTimeout(10000){worker.join()};f.close()
        }
    }
    @Test fun discardDuringColdBuildRetainsInputsUntilBuilderExitsWithoutLatePublication()=runBlocking {
        val f=Fixture();var discardedInsideBuild=false
        try {
            val originalBytes=f.original.readBytes();val linearBytes=f.linear.readBytes()
            val factory=DetailSourceFactory(f.root,memory){marker->
                f.builds++;marker.writeText("filmroom-detail-v1")
                f.photo.discardWhenUnused();discardedInsideBuild=true
                // Both outer acquisition and cold builder still own transient source leases.
                assertTrue(f.original.exists());assertTrue(f.linear.exists())
                assertArrayEquals(originalBytes,f.original.readBytes());assertArrayEquals(linearBytes,f.linear.readBytes())
            }
            try{factory.prepare(f.photo).use{fail("Discarded source must not publish a late grid")};fail()}
            catch(_:IllegalStateException){}
            assertTrue(discardedInsideBuild);assertEquals(1,f.builds)
            f.photo.detailGridCache.awaitRetirements()
            assertFalse(f.original.exists());assertFalse(f.linear.exists())
            assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            try{factory.prepare(f.photo).use{fail("Discarded source must reject new readers")};fail()}
            catch(_:IllegalStateException){}
            assertEquals(1,f.builds)
        }finally{f.close()}
    }
    @Test fun optionalCacheMetadataIoFailurePropagatesWithoutUncachedFallback()=runBlocking {
        // Cover initial input metadata and the cached grid validator, not only missing capability.
        for(warm in listOf(false,true)) {
            val f=Fixture();var metadataFailures=0
            try {
                val factory=f.factory()
                if(warm)factory.prepare(f.photo).close()
                val ordinaryReader=factory.cacheMetadataReader
                val failure=java.io.IOException("Injected cache metadata read failure")
                factory.cacheMetadataReader={path,attrs->
                    if(!warm || path.fileName.toString()=="grid.f32le") {
                        metadataFailures++;throw failure
                    }else ordinaryReader(path,attrs)
                }
                try{factory.prepare(f.photo).use{fail("I/O error must not fall back to uncached detail")};fail()}
                catch(error:java.io.IOException){assertEquals(failure.message,error.message)}
                assertEquals(1,metadataFailures);assertEquals(if(warm)1 else 0,f.builds)
                f.photo.detailGridCache.awaitRetirements()
                assertTrue(File(f.root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
                assertTrue(f.original.exists());assertTrue(f.linear.exists())
            }finally{f.close()}
        }
    }
}
