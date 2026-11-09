package dev.filmroom.effects

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.diagnostics.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectMotionInputDiagnosticsTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun file(bytes:ByteArray)=File.createTempFile("private-fixture-", ".jpg",context.cacheDir).apply{writeBytes(bytes)}
    private fun live()=requireNotNull(javaClass.getResourceAsStream("/motion-input/live.jpg")).use{it.readBytes()}
    private fun reports(uri:String?,check:()->Unit={}):Pair<File?,MotionInputDiagnosticInfo> {
        val result=mutableListOf<MotionInputDiagnosticInfo>()
        val video=EffectMotionInput.prepareWithDiagnostics(context,uri,check){result+=it}
        assertEquals(1,result.size)
        return video to result.single()
    }
    @Test fun missingAndUnreadableSourceHaveTypedReasons() {
        assertEquals(MotionInputStatus.SOURCE_MISSING,reports(null).second.status)
        val missing=File(context.cacheDir,"does-not-exist-private-name.jpg")
        assertEquals(MotionInputStatus.READ_FAILED,reports(Uri.fromFile(missing).toString()).second.status)
    }
    @Test fun distinguishesNonJpegAndUnrecognizedJpeg() {
        for((bytes,status) in listOf(byteArrayOf(1,2,3) to MotionInputStatus.NON_JPEG,byteArrayOf(-1,-40,1,2,3) to MotionInputStatus.NOT_RECOGNIZED)) {
            val input=file(bytes)
            try { val r=reports(Uri.fromFile(input).toString());assertNull(r.first);assertEquals(status,r.second.status) } finally {input.delete()}
        }
    }
    @Test fun successContainsOnlyBoundedByteCountsAndNoIdentifiers() {
        val input=file(live())
        try {
            val (video,info)=reports(Uri.fromFile(input).toString())
            try {
                assertEquals(MotionInputStatus.SUCCESS,info.status)
                assertEquals(input.length(),info.copiedBytes);assertEquals(video!!.length(),info.videoBytes)
                assertEquals(setOf("motionInputStatus","motionInputCopiedBytes","motionInputVideoBytes"),SafeInfo(motionInput=info).fields().keys)
                assertFalse(SafeInfo(motionInput=info).fields().values.any{it.contains(input.name)||it.contains("content:")||it.contains("file:")})
            } finally {video?.delete()}
        } finally {input.delete()}
    }
    @Test fun oversizedInputReportsLimitWithoutRetainedTemporaryFiles() {
        val input=file(byteArrayOf(-1,-40))
        RandomAccessFile(input,"rw").use{it.setLength(134217729)}
        try {
            val (video,info)=reports(Uri.fromFile(input).toString())
            assertNull(video);assertEquals(MotionInputStatus.INPUT_TOO_LARGE,info.status)
            assertTrue(info.copiedBytes in 2..134217728)
        } finally {input.delete()}
    }
    @Test fun extractionFailureIsNotReportedAsUnrecognized() {
        val input=file(live())
        try {
            val (video,info)=reports(Uri.fromFile(input).toString()) {
                if(File(context.cacheDir,"effect-motion").listFiles().orEmpty().any{it.name.startsWith("video-")})throw IOException("private error must not be logged")
            }
            assertNull(video);assertEquals(MotionInputStatus.EXTRACTION_FAILED,info.status)
            assertNull(info.videoBytes)
        } finally {input.delete()}
    }
    @Test fun cancellationAndMemoryRemainDistinctAndDiagnosticFailuresAreIsolated() {
        val input=file(live());val seen=mutableListOf<MotionInputDiagnosticInfo>()
        try {
            assertThrows(CancellationException::class.java) {
                EffectMotionInput.prepareWithDiagnostics(context,Uri.fromFile(input).toString(),{throw CancellationException("private")}){seen+=it}
            }
            assertEquals(MotionInputStatus.CANCELLED,seen.single().status)
            assertEquals(MotionInputStatus.MEMORY_LIMIT,reports(Uri.fromFile(input).toString()){throw OutOfMemoryError()}.second.status)
            assertNull(EffectMotionInput.prepareWithDiagnostics(context,null,{}){throw IllegalStateException("sink failure")})
        } finally {input.delete()}
    }
}
