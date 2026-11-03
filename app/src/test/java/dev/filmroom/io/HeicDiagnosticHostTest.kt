package dev.filmroom.io

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import dev.filmroom.OperationTrace
import dev.filmroom.R
import dev.filmroom.diagnostics.*
import dev.filmroom.i18n.ToastText
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicDiagnosticHostTest {
    private val info=HeicContainer.Info(2,2,0,4,4)
    private fun fails(code:CodecErrorCode,block:()->Unit):PhotoException {
        try{block();fail("Expected $code")}catch(e:PhotoException){
            assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)
            assertEquals(code,e.codecCode);assertNotNull(e.codecStage)
            assertEquals(code,OperationTrace.failureInfo(e,1).codecCode)
            return e
        }
        error("Unreachable")
    }
    @Test fun sourceColorReasonsRemainDistinctWithoutWeakeningChecks() {
        fails(CodecErrorCode.HEIC_SOURCE_NOT_RGB){HeicColorPolicy.requireSource(null)}
        fails(CodecErrorCode.HEIC_SOURCE_RANGE){HeicColorPolicy.requireSource(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))}
        fails(CodecErrorCode.HEIC_SOURCE_TRANSFER_MISMATCH){HeicColorPolicy.requireSource(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))}
        fails(CodecErrorCode.HEIC_SOURCE_MATRIX_MISMATCH){HeicColorPolicy.requireSource(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))}
        val root=listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic")).first{it.isDirectory}
        val rounded=HeicIccProfile.inspectProfile(File(root,"original-rounded-p3.icc").readBytes())
        fails(CodecErrorCode.HEIC_SOURCE_TRANSFER_MISMATCH){HeicColorPolicy.requireSource(ColorSpace.get(ColorSpace.Named.DISPLAY_P3),rounded)}
        HeicColorPolicy.requireSource(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    @Test fun headersAndOutputHaveFixedReasonsAndOriginalGuardSemantics() {
        HeicPlatformPolicy.requireHeader("image/heif",false,2,2,info)
        fails(CodecErrorCode.HEIC_HEADER_MIME){HeicPlatformPolicy.requireHeader("secret-file-name",false,2,2,info)}
        fails(CodecErrorCode.HEIC_HEADER_ANIMATED){HeicPlatformPolicy.requireHeader("image/heic",true,2,2,info)}
        fails(CodecErrorCode.HEIC_HEADER_SIZE){HeicPlatformPolicy.requireHeader("image/heic",false,1,2,info)}
        val right=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val size=Bitmap.createBitmap(1,2,Bitmap.Config.ARGB_8888)
        val config=Bitmap.createBitmap(2,2,Bitmap.Config.RGB_565)
        val color=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888,true,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        try {
            HeicPlatformPolicy.requireOutput(right,info)
            fails(CodecErrorCode.HEIC_OUTPUT_SIZE){HeicPlatformPolicy.requireOutput(size,info)}
            fails(CodecErrorCode.HEIC_OUTPUT_CONFIG){HeicPlatformPolicy.requireOutput(config,info)}
            fails(CodecErrorCode.HEIC_OUTPUT_COLOR){HeicPlatformPolicy.requireOutput(color,info)}
        }finally{listOf(right,size,config,color).forEach{it.recycle()}}
    }
    @Test fun gainmapUnknownAndUnsupportedDirectionsStayFailClosed() {
        HeicSdrBasePolicy.requireSdrDirection(0)
        fails(CodecErrorCode.HEIC_GAINMAP_DIRECTION_UNAVAILABLE){HeicSdrBasePolicy.requireSdrDirection(null)}
        for(direction in listOf(1,-1,2))fails(CodecErrorCode.HEIC_GAINMAP_DIRECTION_UNSUPPORTED){HeicSdrBasePolicy.requireSdrDirection(direction)}
    }
    @Test fun nativeDecoderExceptionUsesOnlyFixedCodeAndLocalizedMessage() {
        val file=File.createTempFile("private-heic-diagnostic-",".heic")
        try {
            file.writeText("NOT_AN_IMAGE_PRIVATE_NAME")
            try{AndroidHeicBackend.decode(file,info,kotlin.coroutines.EmptyCoroutineContext);fail("Malformed bytes decoded")}
            catch(e:PhotoException){
                assertEquals(PhotoException.Reason.UNREADABLE,e.reason)
                assertEquals(CodecErrorCode.HEIC_PLATFORM_DECODE_FAILED,e.codecCode)
                assertEquals(CodecStage.DECODE,e.codecStage)
                val fields=OperationTrace.failureInfo(e,1).fields().toString()
                assertFalse(fields.contains(file.name));assertFalse(fields.contains("NOT_AN_IMAGE_PRIVATE_NAME"))
            }
        }finally{file.delete()}
    }
    @Test fun fixedCodesSurvivePersistenceAndShareWithoutFreeText()=runBlocking {
        val app=RuntimeEnvironment.getApplication();Diagnostics.initialize(app)
        val secret="PRIVATE path/content://photo/ICC_NAME_AND_RAW_EXCEPTION"
        for(code in CodecErrorCode.entries.filter{it.name.startsWith("HEIC_")}){
            val info=SafeInfo(codecCode=code,codecStage=CodecStage.VALIDATE)
            assertEquals(code,SafeInfo.parse(info.fields()).codecCode)
            val e=PhotoException(PhotoException.Reason.UNSUPPORTED,secret,codecCode=code,codecStage=CodecStage.VALIDATE)
            try{OperationTrace.run(Operation.IMPORT){throw e}}catch(actual:PhotoException){assertSame(e,actual)}
        }
        val log=Diagnostics.prepareShare(5).file.readText()
        for(code in CodecErrorCode.entries.filter{it.name.startsWith("HEIC_")})assertTrue(code.name,log.contains(code.name))
        assertTrue(log.contains("UNSUPPORTED_FORMAT"));assertFalse(log.contains(secret))
        assertEquals(CodecErrorCode.UNKNOWN,SafeInfo.parse(mapOf("codecCode" to secret)).codecCode)
    }
    @Test fun knownHeicMessagesNeverUseGenericFailureOrWrongFilmAdvice() {
        for(key in listOf("text_58a07dbdf8d7","text_986742c74398","text_dfe135051811"))
            assertEquals(R.string.toast_format,ToastText.resourceForKey(key))
        for(key in listOf("text_8b96428c13f4","text_1e6cd3983af1"))
            assertEquals(R.string.toast_read,ToastText.resourceForKey(key))
        assertEquals(R.string.toast_failed,ToastText.resourceForKey("untrusted-free-text"))
    }
}
