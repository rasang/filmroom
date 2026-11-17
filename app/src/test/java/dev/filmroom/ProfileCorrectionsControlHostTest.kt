package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.CodecNativeApi
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoRepository
import dev.filmroom.io.PhotoSource
import dev.filmroom.io.RawDevelopmentCache
import dev.filmroom.model.EditorState
import dev.filmroom.presets.PresetQrBinary
import dev.filmroom.presets.PresetQrCodec
import dev.filmroom.presets.PresetQrParameters
import dev.filmroom.presets.SavedPresetStore
import dev.filmroom.ui.SettledChoiceSelector
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = FilmroomApplication::class, qualifiers = "zh-rCN-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileCorrectionsControlHostTest {
    private val app get() = RuntimeEnvironment.getApplication() as FilmroomApplication

    @Before fun setUp() {
        resetAndroidViewModelFactoryForHostTest()
        L10n.choose(app, "zh")
        app.getSharedPreferences("local-session", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        L10n.choose(app, "zh")
    }

    private fun all(v: View): List<View> =
        listOf(v) + (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())

    private fun root(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun field(a: MainActivity, n: String) = MainActivity::class.java.getDeclaredField(n).apply { isAccessible = true }.get(a)
    private fun vm(a: MainActivity) = MainActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }.invoke(a) as EditorViewModel
    private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        assertTrue("Bounded editor state wait", done())
    }
    private fun layout(a: MainActivity, w: Int = 360, h: Int = 780) {
        val r = root(a)
        repeat(2) {
            r.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            r.layout(0, 0, w, h)
            idle()
        }
    }

    @Test(timeout = 60000) fun adjustTabPlacesProfileCorrectionsLastAndFollowsRawCapability() {
        assertNotNull("Custom vector icon must load", app.getDrawable(R.drawable.ic_tool_profile_corrections))
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val a = c.get()
            val model = vm(a)
            await { (EditorViewModel::class.java.getDeclaredField("operationJob").apply { isAccessible = true }.get(model) as? Job)?.isCompleted != false && model.state.value.startupReady }
            model.localDetailEnabled = false
            model.borderMemorySnapshot = { NativeMemorySnapshot(4L * 1024 * 1024 * 1024, 128L * 1024 * 1024, false) }
            dev.filmroom.border.BorderCatalog.initialize(a.assets) { model.borderMemorySnapshot() }
            model.renderPreview = { source, _, _, _ -> source.copy(Bitmap.Config.ARGB_8888, false) }
            model.detailPreviewEdge = { 96 }
            @Suppress("UNCHECKED_CAST")
            val flow = EditorViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as MutableStateFlow<EditorUiState>
            val bmp = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff527682.toInt()) }

            // Non-RAW photo (including HEIC-style linearFile with null rawProfileCorrections): disabled + RAW-only hint.
            val heicLike = PhotoSource(
                File(a.cacheDir, "heic-fixture.heic"), "fixture.heic", "image/heic", 640, 480, 1024,
                linearFile = File(a.cacheDir, "heic-linear.rgb"), rawProfileCorrections = null
            )
            assertFalse(heicLike.isRawSource)
            flow.value = EditorUiState(photo = heicLike, original = bmp, rendered = bmp, parameters = EditorState())
            layout(a)

            all(field(a, "categoryBar") as View).single { it.tag == "adjust" }.performClick()
            layout(a)

            val toolsView = field(a, "tools") as LinearLayout
            val toolButtons = all(toolsView.getChildAt(0)).filterIsInstance<Button>()
            assertEquals(
                listOf("filmExposure", "brightness", "contrast", "warmth", "saturation", "profileCorrections"),
                toolButtons.map { it.tag }
            )
            assertTrue(toolButtons.last().text.toString().contains("配置文件校正"))

            toolButtons.last().performClick()
            layout(a)

            val disabledSelector = all(toolsView).filterIsInstance<SettledChoiceSelector>().single()
            assertFalse("Non-RAW photo must disable profileCorrections selector", disabledSelector.isEnabled)
            assertTrue(disabledSelector.contentDescription.toString().contains("启用"))
            assertTrue(all(toolsView).filterIsInstance<TextView>().any { it.text.toString() == L10n.text(R.string.profile_corrections_raw_only) })

            // Switch to RAW photo: tool strip rebuilds automatically, selector enables, hint disappears, tapping 关闭 commits false.
            val rawPhoto = PhotoSource(
                File(a.cacheDir, "raw-fixture.dng"), "fixture.dng", "image/x-adobe-dng", 640, 480, 2048,
                linearFile = File(a.cacheDir, "raw-linear.rgb"), rawProfileCorrections = true
            )
            assertTrue(rawPhoto.isRawSource)
            flow.value = flow.value.copy(photo = rawPhoto)
            layout(a)

            val enabledSelector = all(toolsView).filterIsInstance<SettledChoiceSelector>().single()
            assertTrue("RAW photo must enable profileCorrections selector", enabledSelector.isEnabled)
            assertFalse(all(toolsView).filterIsInstance<TextView>().any { it.text.toString() == L10n.text(R.string.profile_corrections_raw_only) })

            val optionLabels = all(enabledSelector).filterIsInstance<TextView>().filter { it.text.toString() in setOf("关闭", "启用") }
            assertEquals(listOf("关闭", "启用"), optionLabels.map { it.text.toString() })
            optionLabels.first { it.text.toString() == "关闭" }.performClick()
            idle()
            assertEquals(false, model.state.value.parameters.profileCorrections)
        }
    }

    @Test fun fourLocalesEngineJsonAndQrExtensionBehaveLosslessly() {
        val titles = mutableSetOf<String>()
        val onLabels = mutableSetOf<String>()
        val hints = mutableSetOf<String>()
        for (tag in L10n.supported) {
            L10n.choose(app, tag)
            titles += L10n.text(R.string.tool_profile_corrections)
            onLabels += L10n.text(R.string.profile_corrections_on)
            hints += L10n.text(R.string.profile_corrections_raw_only)
            assertTrue(L10n.text(R.string.tool_profile_corrections_help).isNotBlank())
        }
        assertEquals(4, titles.size)
        assertEquals(4, onLabels.size)
        assertEquals(4, hints.size)
        L10n.choose(app, "zh")

        val disabled = EditorState(profileCorrections = false)
        assertFalse("Engine JSON must not contain presentation/RAW-stage profileCorrections",
            JSONObject(disabled.json(includePresentation = false)).has("profileCorrections"))
        assertEquals(false, JSONObject(disabled.json(includePresentation = true)).getBoolean("profileCorrections"))
        assertEquals(disabled, EditorState.fromJson(disabled.json(includePresentation = true)))

        // Default true omits profileCorrections from V2 binary extension; false encodes it losslessly.
        val defaultQr = PresetQrCodec.encode("默认", EditorState())
        val defaultExt = PresetQrBinary.decode(PresetQrBinary.encode("默认", JSONObject(SavedPresetStore.snapshot(EditorState())))).second
        assertFalse("Default profileCorrections=true must omit extension field", defaultExt.has("profileCorrections"))
        assertEquals(EditorState(), PresetQrCodec.decode(defaultQr).parameters)

        // Disabled false travels in V2 extension and round-trips losslessly.
        val disabledExt = PresetQrBinary.decode(PresetQrBinary.encode("关闭校正", JSONObject(SavedPresetStore.snapshot(disabled)))).second
        assertEquals(false, disabledExt.getBoolean("profileCorrections"))
        val disabledQr = PresetQrCodec.encode("关闭校正", disabled)
        assertTrue("Disabled profileCorrections=false must add extension payload", disabledQr.length > PresetQrCodec.encode("关闭校正", EditorState()).length)
        assertEquals(disabled, PresetQrCodec.decode(disabledQr).parameters)

        // Invalid types in extension fail closed.
        for (bad in listOf<Any>(JSONObject.NULL, "false", 0, 1)) {
            val mutated = JSONObject(SavedPresetStore.snapshot(disabled)).put("profileCorrections", bad)
            try {
                PresetQrParameters.read(mutated, frozenNulls = true)
                fail("Expected PresetQrParameters to reject invalid profileCorrections=$bad")
            } catch (_: PresetQrCodec.Failure) {}
            try {
                PresetQrBinary.decode(PresetQrBinary.encode("bad", mutated)).second.let { PresetQrParameters.read(it, frozenNulls = true) }
                fail("Expected binary QR read to reject invalid profileCorrections=$bad")
            } catch (_: PresetQrCodec.Failure) {}
        }
    }

    private class VariantRawApi : CodecNativeApi {
        override val available = true
        val probeFlags = mutableListOf<Boolean>()
        val decodeFlags = mutableListOf<Boolean>()
        override fun nativeCreate(root: String) = 1L
        override fun nativeCancel(handle: Long) {}
        override fun nativeDestroy(handle: Long) {}

        private fun response(profileCorrections: Boolean): String {
            val policy = if (profileCorrections) "adobe-dng-sdk-1.7.1-lensfun-v1" else "adobe-dng-sdk-1.7.1-uncorrected-v1"
            val warnings = JSONArray().apply {
                if (profileCorrections) {
                    put(JSONObject().put("code", "LENSFUN_VIGNETTING_APPLIED").put("message", "vignetting"))
                    put(JSONObject().put("code", "LENSFUN_DISTORTION_APPLIED").put("message", "distortion"))
                }
            }
            return JSONObject("{\"ok\":true,\"width\":8,\"height\":8,\"outputOrientation\":1,\"schemaVersion\":1,\"orientationApplied\":true,\"outputBytes\":768,\"outputValid\":true,\"format\":\"DNG\"}")
                .put("developmentPolicy", policy)
                .put("warnings", warnings)
                .toString()
        }

        override fun nativeProbe(handle: Long, input: String, budget: String): String {
            val enabled = JSONObject(budget).optBoolean("profileCorrections", true)
            probeFlags += enabled
            return response(enabled)
        }

        override fun nativeDecode(handle: Long, input: String, output: String, budget: String): String {
            val enabled = JSONObject(budget).optBoolean("profileCorrections", true)
            decodeFlags += enabled
            val value = if (enabled) 0.75f else 0.2f
            val pixels = ByteBuffer.allocate(8 * 8 * 12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8 * 8 * 3) { pixels.putFloat(value) }
            File(output).writeBytes(pixels.array())
            return response(enabled)
        }

        override fun nativeEncode(handle: Long, input: String, output: String, parameters: String) =
            "{\"ok\":false,\"code\":\"INVALID_INPUT\"}"
    }

    @Test(timeout = 60000) fun togglingProfileCorrectionsRedevelopsRawSupportsUndoRedoResetAndSessionReuse() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val api = VariantRawApi()
        val codecs = PhotoCodecs(
            context.cacheDir,
            api,
            memorySnapshot = { NativeMemorySnapshot(4L * 1024 * 1024 * 1024, 128L * 1024 * 1024, false) }
        )
        val repo = PhotoRepository(context, codecs)
        val uri = Uri.parse("content://dev.filmroom.synthetic-test/profile-corrections.dng")
        val tiffHeader = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0)
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(tiffHeader))

        val vm = EditorViewModel(app)
        try {
            val operation = EditorViewModel::class.java.getDeclaredField("operationJob").apply { isAccessible = true }
            await { (operation.get(vm) as? Job)?.isCompleted != false && vm.state.value.startupReady }
            EditorViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true; set(vm, repo) }
            @Suppress("UNCHECKED_CAST")
            val flow = EditorViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<EditorUiState>
            vm.localDetailEnabled = false
            vm.borderMemorySnapshot = { NativeMemorySnapshot(4L * 1024 * 1024 * 1024, 128L * 1024 * 1024, false) }
            dev.filmroom.border.BorderCatalog.initialize(app.assets) { vm.borderMemorySnapshot() }
            vm.renderPreview = { source, _, _, _ -> source.copy(Bitmap.Config.ARGB_8888, false) }
            vm.detailPreviewEdge = { 8 }

            vm.importPhoto(uri)
            await { (operation.get(vm) as? Job)?.isCompleted == true && vm.state.value.photo != null }
            flow.value = flow.value.copy(engineUnavailable = null)
            vm.parameters(flow.value.parameters)
            await { vm.state.value.canExport && !vm.state.value.updating }

            val initialPhoto = checkNotNull(vm.state.value.photo)
            val initialSpool = checkNotNull(initialPhoto.linearFile)
            val initialRecord = RawDevelopmentCache.recordFile(initialSpool)
            assertEquals(true, initialPhoto.rawProfileCorrections)
            assertTrue(initialSpool.isFile)
            assertTrue(initialRecord.isFile)
            assertTrue(initialPhoto.developmentWarning.orEmpty().contains("畸变校正"))
            val brightCenter = checkNotNull(vm.state.value.rendered).getPixel(4, 4) and 0xff
            assertEquals(listOf(true), api.decodeFlags)

            // Toggle profileCorrections -> false: re-develops RAW, cleans old spool, keeps localFile.
            vm.parameters(vm.state.value.parameters.copy(profileCorrections = false), true)
            await { vm.state.value.photo?.rawProfileCorrections == false && vm.state.value.canExport && !vm.state.value.updating }

            val uncorrectedPhoto = checkNotNull(vm.state.value.photo)
            val uncorrectedSpool = checkNotNull(uncorrectedPhoto.linearFile)
            assertEquals(initialPhoto.localFile, uncorrectedPhoto.localFile)
            assertTrue("Original imported DNG must remain intact", uncorrectedPhoto.localFile.isFile)
            assertFalse("Superseded corrected spool must be cleaned up", initialSpool.exists())
            assertFalse("Superseded corrected record must be cleaned up", initialRecord.exists())
            assertTrue(uncorrectedSpool.isFile)
            assertNull("Warnings from disabled corrections must clear", uncorrectedPhoto.developmentWarning)
            val darkCenter = checkNotNull(vm.state.value.rendered).getPixel(4, 4) and 0xff
            assertTrue("Uncorrected linear spool must update preview pixels ($darkCenter < $brightCenter)", darkCenter < brightCenter)
            assertEquals(listOf(true, false), api.decodeFlags)

            // Session reopen with profileCorrections = false reuses cached uncorrected spool without re-decoding.
            runBlocking {
                val reopened = repo.reopen(
                    uncorrectedPhoto.cacheKey,
                    uncorrectedPhoto.name,
                    512,
                    uncorrectedPhoto.rawCacheKey,
                    profileCorrections = false
                )
                try {
                    assertEquals(uncorrectedSpool, reopened.source.linearFile)
                    assertEquals(false, reopened.source.rawProfileCorrections)
                    assertEquals(listOf(true, false), api.decodeFlags)
                } finally {
                    reopened.preview.recycle()
                }
            }

            // Undo -> true, Redo -> false, Reset -> true.
            vm.undo()
            await { vm.state.value.photo?.rawProfileCorrections == true && vm.state.value.canExport && !vm.state.value.updating }
            assertTrue(vm.state.value.photo?.developmentWarning.orEmpty().contains("暗角校正"))

            vm.redo()
            await { vm.state.value.photo?.rawProfileCorrections == false && vm.state.value.canExport && !vm.state.value.updating }
            assertNull(vm.state.value.photo?.developmentWarning)

            vm.reset()
            await { vm.state.value.parameters.profileCorrections && vm.state.value.photo?.rawProfileCorrections == true && vm.state.value.canExport && !vm.state.value.updating }
            assertTrue(vm.state.value.photo?.developmentWarning.orEmpty().contains("畸变校正"))
        } finally {
            vm.state.value.photo?.let { repo.discard(it) }
            EditorViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        }
    }
}
