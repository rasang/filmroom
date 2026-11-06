package dev.filmroom.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class EncodedSourceHashTest {
    private fun fixture(block:(File)->Unit) {
        val dir=Files.createTempDirectory("source-hash").toFile()
        try {block(File(dir,"input").apply{writeBytes(ByteArray(12){it.toByte()})})}
        finally {dir.deleteRecursively()}
    }
    @Test fun largeLinearAdmissionUsesExactExistingImageBounds() {
        for(kind in listOf("LINEAR","LINEAR_SOURCE","RAW")) {
            assertEquals(600_000_000L,EncodedSourceHash.sourceLimit(kind,10000,5000,600_000_000L))
            assertNull(EncodedSourceHash.sourceLimit(kind,10000,5000,600_000_001L))
            assertEquals(1_800_000_000L,EncodedSourceHash.sourceLimit(kind,15000,10000,1_800_000_000L))
            assertNull(EncodedSourceHash.sourceLimit(kind,15001,10000,1_800_120_000L))
            assertNull(EncodedSourceHash.sourceLimit(kind,Int.MAX_VALUE,Int.MAX_VALUE,12))
            assertNull(EncodedSourceHash.sourceLimit(kind,0,1,0))
        }
        assertNull(EncodedSourceHash.sourceLimit("ENCODED",10000,5000,600_000_000L))
        assertNull(EncodedSourceHash.sourceLimit("UNKNOWN",1,1,12))
        assertEquals(32L*1024*1024,EncodedPayloadCache.MAX_BYTES)
    }
    @Test fun above64MiBLinearSourceHashesEveryByteWithoutCopy()=fixture {file->
        val bytes=3072L*2048*12
        java.io.RandomAccessFile(file,"rw").use { output->
            output.setLength(bytes)
            for(offset in listOf(0L,EncodedPayloadCache.MAX_SOURCE_BYTES+17,bytes-1)) {
                output.seek(offset);output.write(123)
            }
        }
        assertTrue(file.length()>EncodedPayloadCache.MAX_SOURCE_BYTES)
        assertNull(EncodedPayloadCache.hashFile(file,EncodedPayloadCache.MAX_SOURCE_BYTES){})
        val independent=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input->
            val buffer=ByteArray(64*1024)
            while(true) {val count=input.read(buffer);if(count<0)break;independent.update(buffer,0,count)}
        }
        val expected=independent.digest().joinToString(""){"%02x".format(it)}
        val hasher=EncodedSourceHash("LINEAR",3072,2048,{0})
        val first=hasher.hash(file){}
        assertEquals(expected,first)
        // Mutation beyond the old cap must affect the digest, not just metadata admission.
        java.io.RandomAccessFile(file,"rw").use { output->
            output.seek(EncodedPayloadCache.MAX_SOURCE_BYTES+17);output.write(124)
        }
        assertNotEquals(first,hasher.hash(file){})
        assertEquals(bytes,file.length())
    }
    @Test fun completeHashesMatchAndContentChangesInvalidate()=fixture {file->
        val hasher=EncodedSourceHash("LINEAR",1,1,{0})
        val first=hasher.hash(file){}
        assertEquals(EncodedPayloadCache.hashFile(file,12){},first)
        file.writeBytes(ByteArray(12){(it+1).toByte()})
        assertNotEquals(first,hasher.hash(file){})
    }
    @Test fun deadlineDropsPartialDigestAndStaysExhausted()=fixture {file->
        var clock=0L
        val hasher=EncodedSourceHash("LINEAR",1,1,{clock})
        var checks=0
        assertNull(hasher.hash(file){if(++checks==4)clock=EncodedSourceHash.HASH_BUDGET_NANOS})
        assertTrue(checks>=4)
        assertNull(hasher.hash(file){})
    }
    @Test fun timeBetweenHashesDoesNotConsumeBudgetButHashingDoes()=fixture {file->
        var clock=0L
        val hasher=EncodedSourceHash("LINEAR",1,1,{clock})
        assertNotNull(hasher.hash(file){clock+=50_000_000})
        clock+=100_000_000_000L
        assertNotNull(hasher.hash(file){clock+=50_000_000})
        assertNull(hasher.hash(file){clock+=200_000_000})
    }
    @Test fun cancellationAndSymlinkStillFailClosed()=fixture {file->
        val hasher=EncodedSourceHash("LINEAR",1,1,{0})
        assertThrows(CancellationException::class.java){hasher.hash(file){throw CancellationException()}}
        val link=File(file.parentFile,"link")
        Files.createSymbolicLink(link.toPath(),file.toPath())
        assertNull(hasher.hash(link){})
    }
}
