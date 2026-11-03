package dev.filmroom.codec

import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.diagnostics.SafeInfo
import dev.filmroom.io.PhotoException
import dev.filmroom.io.enrichImportFailure
import dev.filmroom.io.formats.PhotoFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class NativeHandleFailureTest {
    @Test fun zeroNativeHandleHasSpecificSafeCategoryWithoutEnteringDecodeOrDestroy()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("native-create-failure").toFile()
        try {
            val input=File(root,"private-name.arw").apply{writeBytes(byteArrayOf(1,2,3))}
            var creates=0
            val api=object:CodecNativeApi {
                override val available=true
                override fun nativeCreate(root:String):Long{creates++;return 0L}
                override fun nativeProbe(handle:Long,input:String,budget:String):String=error("probe must not run")
                override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String=error("decode must not run")
                override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String=error("encode must not run")
                override fun nativeCancel(handle:Long){error("zero handle must not be cancelled")}
                override fun nativeDestroy(handle:Long){error("zero handle must not be destroyed")}
            }
            val error=try{PhotoCodecs(root,api).probe(input);fail("expected zero-handle failure");throw AssertionError()}catch(e:PhotoException){e}
            assertEquals(1,creates);assertEquals(PhotoException.Reason.UNREADABLE,error.reason)
            assertEquals(CodecErrorCode.NATIVE_HANDLE_CREATE_FAILED,error.codecCode)
            assertEquals(CodecStage.NATIVE_CREATE,error.codecStage)
            val enriched=enrichImportFailure(error,CodecStage.INPUT_PROBE,PhotoFormat.TIFF)
            assertEquals(error.codecCode,enriched.codecCode);assertEquals(error.codecStage,enriched.codecStage)
            val fields=SafeInfo(codecCode=enriched.codecCode,codecStage=enriched.codecStage).fields()
            assertFalse(fields.toString().contains(input.name));assertFalse(fields.toString().contains(root.path))
        }finally{root.deleteRecursively()}
    }
}
