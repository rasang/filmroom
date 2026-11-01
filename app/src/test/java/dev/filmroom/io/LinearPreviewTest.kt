package dev.filmroom.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production spool and PhotoSource APIs; no substitute resampler or fake native renderer. */
class LinearPreviewTest {
    private fun input(root:File,value:Float=5.734f):File = File(root,"${java.util.UUID.randomUUID()}.rgb").apply {
        val data=ByteBuffer.allocate(4*4*12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16){data.putFloat(value).putFloat(-0.02f).putFloat(0.1234567f)}
        writeBytes(data.array())
    }
    private fun photo(raw:File)=PhotoSource(raw,"test","image/x-adobe-dng",4,4,raw.length(),linearFile=raw)

    @Test fun directPreviewRetainsHdrNegativesAndFloatPrecisionAndMatchesExportResampler()=runBlocking {
        val root=kotlin.io.path.createTempDirectory(prefix="preview-linear-").toFile()
        try {
            val raw=input(root);val preview=LinearSpool.linearPreview(raw,4,4,2,coroutineContext)
            assertEquals(2,preview.width);assertEquals(2,preview.height)
            val bytes=preview.buffer();assertTrue(bytes.isDirect);assertTrue(bytes.isReadOnly);assertEquals(ByteOrder.nativeOrder(),bytes.order())
            val export=File(root,"export");LinearSpool.resize(raw,4,4,export,PhotoLimits.Dimensions(2,2),coroutineContext)
            val expected=ByteBuffer.wrap(export.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            while(expected.hasRemaining())assertEquals(expected.float,bytes.float,0f)
            val again=preview.buffer();assertEquals(0,again.position());assertEquals(5.734f,again.float,0f);assertEquals(-0.02f,again.float,0f)
            assertThrows(ReadOnlyBufferException::class.java){again.putFloat(0f)}
            assertEquals(0,preview.buffer().position())
        }finally{root.deleteRecursively()}
    }
    @Test fun sourceCacheIsPerIdentityAndEdgeAndNeverWritesBackToRaw()=runBlocking {
        val root=kotlin.io.path.createTempDirectory(prefix="preview-cache-").toFile()
        try {
            val raw=input(root);val before=raw.readBytes();val a=photo(raw);val b=photo(input(root,2.5f))
            val first=a.linearPreview(4)!!
            repeat(8){assertSame(first,a.linearPreview(4))}
            assertNotSame(first,b.linearPreview(4));assertEquals(2.5f,b.linearPreview(4)!!.buffer().float,0f)
            assertNotSame(first,a.linearPreview(2));assertEquals(2,a.linearPreview(2)!!.width)
            assertSame(first,a.linearPreview(4)) // Fast/detail alternation must not reread the full RAW.
            assertArrayEquals(before,raw.readBytes())
            a.clearLinearPreview();assertNotSame(first,a.linearPreview(4))
            assertTrue(raw.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationDoesNotPublishBufferOrPoisonNextAttempt()=runBlocking {
        val root=kotlin.io.path.createTempDirectory(prefix="preview-cancel-").toFile()
        try {
            val raw=input(root);val source=photo(raw)
            repeat(8){
                val job=Job().apply{cancel()}
                try{withContext(job){source.linearPreview(4)};fail()}catch(_:CancellationException){}
                val result=source.linearPreview(4)!!
                assertEquals(5.734f,result.buffer().float,0f)
                assertTrue(raw.exists())
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun previewBoundAndInvalidFloatAreRejectedBeforeNative()=runBlocking {
        val root=kotlin.io.path.createTempDirectory(prefix="preview-bounds-").toFile()
        try {
            val raw=input(root)
            for(edge in listOf(0,769,Int.MAX_VALUE))assertThrows(IllegalArgumentException::class.java){LinearSpool.linearPreview(raw,4,4,edge,coroutineContext)}
            assertThrows(IllegalArgumentException::class.java){LinearSpool.linearPreview(input(root,Float.NaN),4,4,4,coroutineContext)}
            val preview=LinearSpool.linearPreview(raw,4,4,768,coroutineContext)
            assertTrue(preview.buffer().capacity()<=768*768*12);assertEquals(4,preview.width)
        }finally{root.deleteRecursively()}
    }
    @Test fun detailReadsMoreSourcePixelsWithoutClippingOrUpscaling()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("preview-detail-").toFile()
        try {
            val raw=File(root,"source.rgb")
            val row=ByteBuffer.allocate(1536*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(1536){row.putFloat(5.734f).putFloat(-0.02f).putFloat(0.1234567f)}
            raw.outputStream().use{out->repeat(16){out.write(row.array())}}
            val source=PhotoSource(raw,"test","image/x-adobe-dng",1536,16,raw.length(),linearFile=raw)
            val quick=source.linearPreview(512)!!;val detail=source.linearPreview(768)!!
            assertEquals(512,quick.width);assertEquals(768,detail.width)
            assertSame(quick,source.linearPreview(512));assertSame(detail,source.linearPreview(768))
            for(preview in listOf(quick,detail)) {
                val data=preview.buffer()
                while(data.hasRemaining()) {assertEquals(5.734f,data.float,0.00001f);assertEquals(-0.02f,data.float,0.00001f);assertEquals(0.1234567f,data.float,0.00001f)}
                val exported=File(root,"export-${preview.width}")
                LinearSpool.resize(raw,1536,16,exported,PhotoLimits.Dimensions(preview.width,preview.height),coroutineContext)
                val expected=ByteBuffer.wrap(exported.readBytes()).order(ByteOrder.LITTLE_ENDIAN);val actual=preview.buffer()
                while(expected.hasRemaining())assertEquals(expected.float,actual.float,0f)
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationInsideRowsCannotReturnPartialPreview()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("preview-mid-cancel-").toFile()
        try {
            val raw=input(root);val delegate=Job();var checks=0
            // Deterministically cancel after allocation and the first produced row.
            val context=object:kotlin.coroutines.CoroutineContext by kotlin.coroutines.EmptyCoroutineContext {
                @Suppress("UNCHECKED_CAST")
                override fun <E:kotlin.coroutines.CoroutineContext.Element> get(key:kotlin.coroutines.CoroutineContext.Key<E>):E? {
                    if(key!=Job)return null
                    if(++checks==3)delegate.cancel()
                    return delegate as E
                }
            }
            try {LinearSpool.linearPreview(raw,4,4,4,context);fail("partial preview returned")}
            catch(_:CancellationException){}
            assertEquals(3,checks);assertTrue(raw.exists())
            assertEquals(5.734f,LinearSpool.linearPreview(raw,4,4,4,coroutineContext).buffer().float,0f)
        }finally{root.deleteRecursively()}
    }
    @Test fun releasedCacheCannotRepublishAndBorrowedBufferStaysValid()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("preview-release-").toFile()
        try {
            val source=photo(input(root));val borrowed=source.linearPreview(4)!!.buffer()
            source.clearLinearPreview()
            assertEquals(5.734f,borrowed.float,0f)
            val afterRelease=source.linearPreview(4)
            assertNotSame(afterRelease,source.linearPreview(4))
        }finally{root.deleteRecursively()}
    }
}
