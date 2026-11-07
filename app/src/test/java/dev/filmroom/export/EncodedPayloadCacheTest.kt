package dev.filmroom.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class EncodedPayloadCacheTest {
    private fun fixture(block:(File,EncodedPayloadCache,File)->Unit) {
        val root=Files.createTempDirectory("encoded-cache-test").toFile()
        try {val input=File(root,"encoded").apply{writeBytes(ByteArray(8193){(it*37).toByte()})};block(root,EncodedPayloadCache.get(root),input)}finally{root.deleteRecursively()}
    }
    private val key=EncodedPayloadCache.key("source","science","presentation","format")
    private fun publish(cache:EncodedPayloadCache,source:File)=checkNotNull(cache.stage(key,source,"verified-receipt",100,{Long.MAX_VALUE}){}).commit{}
    @Test fun immutableBytesCopyToFreshOwnedDestination()=fixture {root,cache,input->
        val bytes=input.readBytes();publish(cache,input);input.writeText("mutated source")
        cache.acquire(key)!!.use{lease->val out=File(root,"job-copy");lease.copyTo(out){};assertArrayEquals(bytes,out.readBytes());out.writeText("job metadata")}
        cache.acquire(key)!!.use{lease->val out=File(root,"second-copy");lease.copyTo(out){};assertArrayEquals(bytes,out.readBytes())}
    }
    @Test fun keyLengthDelimitersAndNullAreUnambiguous() {
        assertNotEquals(EncodedPayloadCache.key("a:b","c"),EncodedPayloadCache.key("a","b:c"))
        assertNotEquals(EncodedPayloadCache.key(null),EncodedPayloadCache.key("null"))
        assertNotEquals(EncodedPayloadCache.key(""),EncodedPayloadCache.key(null))
    }
    @Test fun stagedAndCancelledCandidatesNeverHit()=fixture {_,cache,input->
        val ticket=cache.stage(key,input,"receipt",100,{Long.MAX_VALUE}){}!!
        assertNull(cache.acquire(key));ticket.close();assertNull(cache.acquire(key))
        assertThrows(CancellationException::class.java){cache.stage(key,input,"receipt",100,{Long.MAX_VALUE}){throw CancellationException()}}
        assertNull(cache.acquire(key))
    }
    @Test fun cancelledCommitAndLateCommitDoNotPublish()=fixture {_,cache,input->
        val ticket=cache.stage(key,input,"receipt",100,{Long.MAX_VALUE}){}!!
        assertThrows(CancellationException::class.java){ticket.commit{throw CancellationException()}}
        assertNull(cache.acquire(key))
        val retired=cache.stage(key,input,"receipt",100,{Long.MAX_VALUE}){}!!
        cache.trimForReserve(100){0};retired.commit{};assertNull(cache.acquire(key))
    }
    @Test fun corruptSameLengthArtifactIsMissAndPartialJobCopyRemoved()=fixture {root,cache,input->
        publish(cache,input);val payload=File(root,"encoded-payload-v1/payload")
        val corrupt=payload.readBytes();corrupt[4]=(corrupt[4].toInt() xor 3).toByte();payload.writeBytes(corrupt)
        val output=File(root,"copy");cache.acquire(key)!!.use{assertThrows(Exception::class.java){it.copyTo(output){}}}
        assertFalse(output.exists());assertNull(cache.acquire(key))
    }
    @Test fun cancelledCopyDoesNotLeaveJobFile()=fixture {root,cache,input->
        publish(cache,input);val out=File(root,"cancelled");var checks=0
        cache.acquire(key)!!.use{assertThrows(CancellationException::class.java){it.copyTo(out){if(++checks==3)throw CancellationException()}}}
        assertFalse(out.exists())
    }
    @Test fun evictionRespectsLiveBorrowerAndReclaimsIdleBytes()=fixture {root,cache,input->
        publish(cache,input);val lease=cache.acquire(key)!!;cache.trimForReserve(200){0}
        assertNotNull(cache.acquire(key)?.also{it.close()});assertNull(cache.stage(key,input,"receipt",0,{Long.MAX_VALUE}){})
        val out=File(root,"borrowed");lease.copyTo(out){};assertArrayEquals(input.readBytes(),out.readBytes());lease.close()
        cache.trimForReserve(200){0};assertNull(cache.acquire(key));assertFalse(File(root,"encoded-payload-v1/payload").exists())
    }
    @Test fun diskAdmissionAndCapacityNeverConsumeNormalReserve()=fixture {root,cache,input->
        assertNull(cache.stage(key,input,"receipt",100,{100+input.length()-1}){})
        val huge=File(root,"huge");java.io.RandomAccessFile(huge,"rw").use{it.setLength(EncodedPayloadCache.MAX_BYTES+1)}
        assertNull(cache.stage(key,huge,"receipt",0,{Long.MAX_VALUE}){})
        assertNull(cache.acquire(key));assertFalse(File(root,"encoded-payload-v1/payload.part").exists())
    }
    @Test fun idleCacheIsEvictedBeforeNormalAdmissionWouldFail()=fixture {root,cache,input->
        publish(cache,input);val payload=File(root,"encoded-payload-v1/payload")
        val required=10000L;val filesystemFree={required-payload.length()}
        assertTrue(filesystemFree()<required);cache.trimForReserve(required,filesystemFree)
        assertEquals(required,filesystemFree());assertNull(cache.acquire(key))
    }
    @Test fun truncatedAndSymlinkedArtifactsNeverBecomeReadableHits()=fixture {root,cache,input->
        publish(cache,input);val payload=File(root,"encoded-payload-v1/payload");payload.writeText("short")
        assertNull(cache.acquire(key));publish(cache,input)
        payload.delete();val foreign=File(root,"foreign-target").apply{writeText("do not touch")}
        Files.createSymbolicLink(payload.toPath(),foreign.toPath());assertNull(cache.acquire(key))
        assertEquals("do not touch",foreign.readText())
    }
    @Test fun destinationCollisionAndUnknownFilesAreNeverDeleted()=fixture {root,cache,input->
        publish(cache,input);val out=File(root,"foreign").apply{writeText("user file")}
        cache.acquire(key)!!.use{assertThrows(Exception::class.java){it.copyTo(out){}}};assertEquals("user file",out.readText())
        val other=File(root,"other").apply{mkdir()};val cacheRoot=File(other,"encoded-payload-v1").apply{mkdir()}
        val unknown=File(cacheRoot,"unknown").apply{writeText("preserve")}
        assertNull(EncodedPayloadCache.get(other).stage(key,input,"receipt",0,{Long.MAX_VALUE}){});assertEquals("preserve",unknown.readText())
    }
    @Test fun sourceHashRejectsSymlinksLargeAndChangedContent()=fixture {root,_,input->
        val first=EncodedPayloadCache.hashFile(input,EncodedPayloadCache.MAX_SOURCE_BYTES){}
        assertNull(EncodedPayloadCache.hashFile(input,input.length()-1){})
        val link=File(root,"link");Files.createSymbolicLink(link.toPath(),input.toPath());assertNull(EncodedPayloadCache.hashFile(link,Long.MAX_VALUE){})
        input.writeBytes(input.readBytes().also{it[0]=(it[0].toInt() xor 1).toByte()})
        assertNotEquals(first,EncodedPayloadCache.hashFile(input,EncodedPayloadCache.MAX_SOURCE_BYTES){})
    }
    @Test fun streamingCandidateUsesSameLeaseAndOnlyPublishesAfterCompleteCommit()=fixture {root,cache,input->
        val bytes=input.readBytes();var admitted=0L
        val stream=cache.beginStream(key,"png-receipt"){n,write->admitted+=n;write();true}!!
        stream.write(bytes);assertNull(cache.acquire(key));assertEquals(bytes.size.toLong(),admitted)
        val ticket=stream.finish{}!!;assertNull(cache.acquire(key));ticket.commit{}
        cache.acquire(key)!!.use{it.copyTo(File(root,"png-job")){};assertArrayEquals(bytes,File(root,"png-job").readBytes())}
        stream.close();assertNotNull(cache.acquire(key)?.also{it.close()})
    }
    @Test fun streamingCapacityAndDeniedAdmissionDropSideOutputWithoutThrowing()=fixture {root,cache,_->
        val stream=cache.beginStream(key,"receipt"){_,write->write();true}!!
        val chunk=ByteArray(64*1024)
        repeat((EncodedPayloadCache.MAX_BYTES/chunk.size).toInt()+1){stream.write(chunk)}
        assertNull(stream.finish{});assertFalse(File(root,"encoded-payload-v1/payload.part").exists())
        val denied=cache.beginStream(key,"receipt"){_,_->false}!!
        denied.write(chunk);assertNull(denied.finish{});assertNull(cache.acquire(key))
        val failed=cache.beginStream(key,"receipt"){_,_->throw java.io.IOException("disk full")}!!
        failed.write(chunk);assertNull(failed.finish{});assertFalse(File(root,"encoded-payload-v1/payload.part").exists())
    }
    @Test fun streamTrimCancelAndLateWritesCannotPublishOrDeleteReplacement()=fixture {root,cache,input->
        val stream=cache.beginStream(key,"receipt"){_,write->write();true}!!;stream.write(input.readBytes())
        cache.trimForReserve(100){0};assertNull(stream.finish{})
        publish(cache,input);stream.write(byteArrayOf(1,2,3));stream.close()
        cache.acquire(key)!!.use{it.copyTo(File(root,"after-trim")){};assertArrayEquals(input.readBytes(),File(root,"after-trim").readBytes())}
        val cancelled=cache.beginStream(key,"receipt"){_,write->write();true}!!;cancelled.write(input.readBytes())
        assertThrows(CancellationException::class.java){cancelled.finish{throw CancellationException()}}
        assertFalse(File(root,"encoded-payload-v1/payload.part").exists());assertNull(cache.acquire(key))
    }
    @Test fun streamingRespectsBorrowersAndCompleteRemainingReserveAtEveryWrite()=fixture {root,cache,input->
        publish(cache,input);cache.acquire(key)!!.use{assertNull(cache.beginStream(key,"receipt"){_,write->write();true})}
        val part=File(root,"encoded-payload-v1/payload.part");val totalFree=110L;val reserve=100L
        val stream=cache.beginStream(key,"receipt"){n,write->if(totalFree-part.length()<reserve+n)false else{write();true}}!!
        stream.write(ByteArray(10));assertEquals(10L,part.length());stream.write(1)
        assertNull(stream.finish{});assertFalse(part.exists());assertNull(cache.acquire(key))
    }

}
