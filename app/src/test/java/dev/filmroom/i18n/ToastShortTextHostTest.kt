package dev.filmroom.i18n

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import dev.filmroom.EditorUiState
import dev.filmroom.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToastShortTextHostTest {
    @Test fun actualImageLimitsRemainDistinctFromMemoryFormatAndMetadata() {
        val expected=mapOf(
            "text_2673ef9ba447" to R.string.toast_file_limit,
            "text_5c55db3ba035" to R.string.toast_raw_pixel_limit,
            "text_56e47360aaca" to R.string.toast_raw_pixel_limit,
            "text_d591741a5a6e" to R.string.toast_raster_pixel_limit,
            "text_d6bc69a0a6a8" to R.string.toast_export_pixel_limit,
            "image_edge_limit_30000" to R.string.toast_edge_limit_30000,
            "image_edge_limit_32768" to R.string.toast_edge_limit_32768,
            "text_5c2432fd4e78" to R.string.toast_memory,
            "text_660bbeffe8d6" to R.string.toast_memory,
            "text_9ff6012fdde9" to R.string.toast_memory,
            "text_1351a2d16cbe" to R.string.toast_metadata_limit,
            "text_0105eb1c06a7" to R.string.toast_format,
            "text_199d9706e7a1" to R.string.toast_read)
        for((key,res) in expected) assertEquals(key,res,ToastText.resourceForKey(key))
        for(tag in L10n.supported) {
            L10n.choose(RuntimeEnvironment.getApplication(),tag)
            assertTrue(ToastText.forMessage(UiMessage.of(R.string.image_edge_limit_30000)).contains("30000"))
            assertTrue(ToastText.forMessage(UiMessage.of(R.string.image_edge_limit_32768)).contains("32768"))
            assertTrue(ToastText.forMessage(UiMessage.of(R.string.text_2673ef9ba447)).contains("512"))
        }
    }

    @org.junit.After fun restoreChinese(){L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    @Test fun acceptedCancellationAndUncertainStatesRemainDistinct(){
        for(key in listOf("text_bb8990972dce","text_834295cc897b","text_420e35e0302a"))
            assertTrue(ToastText.resourceForKey(key) in setOf(R.string.toast_queued,R.string.toast_queued_check))
        assertEquals(R.string.toast_cancel_requested,ToastText.resourceForKey("text_afb4ebeb2c42"))
        assertEquals(R.string.toast_background,ToastText.resourceForKey("text_f9957f03a610"))
        assertEquals(R.string.toast_uncertain,ToastText.resourceForKey("text_9f9e68d602d6"))
        for(key in listOf("text_aeb3f82f0b35","text_11ee5fdc4a0b"))assertEquals(R.string.toast_add_failed,ToastText.resourceForKey(key))
    }
    @Test fun unknownArgumentsAndRawDiagnosticsNeverEnterToastAndFullStateIsPreserved(){
        val secret="content://private/photo/secret-name.jpg ${"RAW-DIAGNOSTIC".repeat(300)}"
        for(tag in L10n.supported){
            L10n.choose(RuntimeEnvironment.getApplication(),tag)
            val unknown=UiMessage("future-key",listOf(secret))
            val state=EditorUiState(message=secret,messageUi=unknown,messageIsDiagnostic=true)
            assertEquals(secret,state.message)
            assertTrue(state.messageDisplay!!.contains(secret))
            assertEquals(L10n.text(R.string.toast_failed),ToastText.forMessage(state.messageUi))
            assertEquals(L10n.text(R.string.toast_failed),ToastText.forMessage(null))
            assertEquals(L10n.text(R.string.toast_failed),ToastText.forMessage(UiMessage("text_bb8990972dce",listOf(secret))))
            val detailed=UiMessage.of(R.string.text_bb8990972dce)
            val before=detailed.render()
            ToastText.forMessage(detailed)
            assertEquals(before,detailed.render())
            assertNotEquals(before,ToastText.forMessage(detailed))
        }
    }
    @Test fun safeDiagnosticCodesRemainReadableThroughExistingShareLog()=kotlinx.coroutines.runBlocking {
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.diagnostics.Diagnostics.initialize(app)
        val secret="PRIVATE-PHOTO-path-and-native-details"
        val failure=dev.filmroom.io.PhotoException(dev.filmroom.io.PhotoException.Reason.UNSUPPORTED,secret,
            codecCode=dev.filmroom.diagnostics.CodecErrorCode.UNSUPPORTED_DNG_OPCODE,
            codecStage=dev.filmroom.diagnostics.CodecStage.DEVELOP)
        try { dev.filmroom.OperationTrace.run(dev.filmroom.diagnostics.Operation.IMPORT){throw failure} }
        catch(caught:dev.filmroom.io.PhotoException){assertSame(failure,caught)}
        val state=EditorUiState(message=failure.message,messageIsDiagnostic=true)
        assertEquals(secret,state.message)
        assertEquals(L10n.text(R.string.toast_failed),ToastText.forMessage(state.messageUi))
        val share=dev.filmroom.diagnostics.Diagnostics.prepareShare(15)
        val log=share.file.readText()
        assertTrue(log.contains("UNSUPPORTED_DNG_OPCODE"))
        assertTrue(log.contains("UNSUPPORTED_FORMAT"))
        assertTrue(log.contains("develop"))
        assertFalse(log.contains(secret))
    }
    /** 200dp text width reserves icon/padding on a 320dp device; enlarged 1.3x text too.
     * This is host Skia text rendering, not a guarantee about every OEM Toast window. */
    @Test fun allFourLocalesFitTwoLinesAtSmallAndWideWidthsWithoutTruncation(){
        val fields=R.string::class.java.fields.filter{it.name.startsWith("toast_")}.sortedBy{it.name}
        assertTrue(fields.size>=34)
        val output=File("build/toast-host-renders").apply{mkdirs()}
        for(tag in L10n.supported)for(width in listOf(200,280)){
            L10n.choose(RuntimeEnvironment.getApplication(),tag)
            val image=Bitmap.createBitmap(width+24,fields.size*88,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image);canvas.drawColor(Color.rgb(40,40,40))
            fields.forEachIndexed{index,field->
                val value=L10n.text(field.getInt(null));assertTrue(value.isNotBlank())
                assertFalse(value.contains("\n"));assertFalse(value.contains("%"))
                for(scale in listOf(1f,1.3f)){
                    val paint=TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=14f*scale}
                    val layout=StaticLayout.Builder.obtain(value,0,value.length,paint,width).setIncludePad(false).build()
                    assertTrue("$tag ${field.name} $width at $scale: ${layout.lineCount} lines: $value",layout.lineCount<=2)
                    for(line in 0 until layout.lineCount)assertTrue("$tag ${field.name} width=$width scale=$scale visible=${layout.getLineMax(line)}: $value",layout.getLineMax(line)<=width+1)
                    if(scale==1.3f){canvas.save();canvas.translate(12f,index*88f+8);layout.draw(canvas);canvas.restore()}
                }
            }
            File(output,"$tag-toast-$width.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
    }
}
