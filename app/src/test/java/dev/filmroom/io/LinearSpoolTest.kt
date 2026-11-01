package dev.filmroom.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class LinearSpoolTest {
    @Test fun resizeKeepsFloatPrecisionAndRange()=runBlocking {
        val root=createTempDir(prefix="linear-test-");val input=File(root,"input");val output=File(root,"out")
        try{
            val bytes=ByteBuffer.allocate(4*4*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(16){bytes.putFloat(0.1234567f).putFloat(1.25f).putFloat(-0.01f)};input.writeBytes(bytes.array())
            LinearSpool.resize(input,4,4,output,PhotoLimits.Dimensions(2,2),coroutineContext)
            assertEquals(48L,output.length());val result=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4){assertEquals(0.1234567f,result.float,1e-7f);assertEquals(1.25f,result.float,1e-7f);assertEquals(-0.01f,result.float,1e-7f)}
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationDeletesOnlyNewOutput()=runBlocking {
        val root=createTempDir(prefix="linear-test-");val input=File(root,"input");val output=File(root,"out")
        try{
            input.writeBytes(ByteArray(4*4*12));val job=Job();job.cancel()
            try{LinearSpool.resize(input,4,4,output,PhotoLimits.Dimensions(2,2),job);fail()}catch(_:CancellationException){}
            assertTrue(input.exists());assertFalse(output.exists())
        }finally{root.deleteRecursively()}
    }
}
