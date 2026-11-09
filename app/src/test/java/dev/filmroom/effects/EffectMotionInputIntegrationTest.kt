package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class EffectMotionInputIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    private fun movie() = box("ftyp", "isom0000".toByteArray()) + box("moov", box("trak", box("mdia", box("hdlr", ByteArray(8) + "vide".toByteArray())))) + box("mdat", byteArrayOf(1, 2, 3))
    private fun jpeg(flag: Boolean): ByteArray {
        fun segment(marker: Int, p: ByteArray) = byteArrayOf(-1, marker.toByte(), ((p.size + 2) shr 8).toByte(), (p.size + 2).toByte()) + p
        return byteArrayOf(-1, -40) + segment(225, (if (flag) "GCamera:MotionPhoto=\"1\"" else "plain").toByteArray()) +
            segment(192, byteArrayOf(8, 0, 2, 0, 2, 1, 1, 17, 0)) + segment(218, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(1, -1, -39)
    }
    private fun fixture(bytes: ByteArray) = File.createTempFile("original-live-", ".jpg", context.cacheDir).apply { writeBytes(bytes) }
    @Test fun originalUriIsCopiedInspectedAndExtractedWithoutDeletingRetainedVideo() {
        val video = requireNotNull(javaClass.getResourceAsStream("/motion-input/video.mp4")).use { it.readBytes() }
        val original = requireNotNull(javaClass.getResourceAsStream("/motion-input/live.jpg")).use { it.readBytes() }
        val file = fixture(original)
        var result: File? = null
        try {
            result = EffectMotionInput.prepare(context, Uri.fromFile(file).toString())
            assertNotNull(result)
            assertTrue(result!!.isFile)
            assertArrayEquals(video, result.readBytes())
            assertArrayEquals(original, file.readBytes())
            File("build/motion-input-extracted.mp4").writeBytes(result.readBytes())
            assertFalse(File(context.cacheDir, "effect-motion").listFiles().orEmpty().any { it.name.startsWith("input-") })
        } finally { result?.delete(); file.delete() }
    }
    @Test fun unflaggedStillIsNotMisidentifiedAsLive() {
        val file = fixture(jpeg(false) + movie())
        try { assertNull(EffectMotionInput.prepare(context, Uri.fromFile(file).toString())) } finally { file.delete() }
    }
    @Test fun cancelledPreparationDoesNotSilentlyReturnStaticOrRetainInput() {
        val file = fixture(jpeg(true) + movie())
        try {
            assertThrows(CancellationException::class.java) {
                EffectMotionInput.prepare(context, Uri.fromFile(file).toString()) { throw CancellationException("cancel") }
            }
            assertFalse(File(context.cacheDir, "effect-motion").listFiles().orEmpty().any { it.name.startsWith("input-") })
        } finally { file.delete() }
    }
}
