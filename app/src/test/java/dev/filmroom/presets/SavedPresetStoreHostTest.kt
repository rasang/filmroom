package dev.filmroom.presets

import android.app.Application
import dev.filmroom.border.MediumFormat
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.CropMode
import dev.filmroom.model.CropSettings
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID

/** Real private-file persistence and Android JSON/AtomicFile contracts, without an engine or photo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SavedPresetStoreHostTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun target() = File(temporary.newFolder(), "saved-presets.json")

    @Test fun importCorrelationSurvivesSaveToApplyButIsNeverPersisted() {
        val file = target()
        val store = SavedPresetStore(file)
        val token = RecipeCorrelation.next()
        val item = store.save("Private recipe", EditorState(), token)
        assertEquals(token, RecipeCorrelation.forApply(item.id))
        val bytes = file.readText()
        assertFalse(bytes.contains("correlation", ignoreCase = true))
        assertFalse(bytes.contains(token.toString()))
        assertTrue(store.delete(item.id))
        assertNotEquals(token, RecipeCorrelation.forApply(item.id))
    }


    private fun fails(problem: SavedPresetStore.Problem, action: () -> Unit) {
        try {
            action()
            fail("Expected $problem")
        } catch (error: SavedPresetStore.Failure) {
            assertEquals(problem, error.problem)
        }
    }

    private fun digest(snapshot: String) = MessageDigest.getInstance("SHA-256")
        .digest(snapshot.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Change the first snapshot while retaining the store's integrity envelope. */
    private fun changeSnapshot(file: File, update: (JSONObject) -> Unit): String {
        val root = JSONObject(file.readText())
        val row = root.getJSONArray("presets").getJSONObject(0)
        val parameters = JSONObject(row.getString("parameters"))
        update(parameters)
        val snapshot = parameters.toString()
        row.put("parameters", snapshot).put("sha256", digest(snapshot))
        file.writeText(root.toString())
        return snapshot
    }

    /** Every EditorState field differs from its default, including temporarily inactive controls. */
    private fun fullState() = EditorState(
        filmStock = "kodak_gold_200",
        paperStock = "kodak_2393",
        brightness = .75f,
        filmExposure = -.5f,
        contrast = 1.2f,
        warmth = -.25f,
        saturation = 1.3f,
        grain = .4f,
        grainSize = 1.7f,
        halation = .6f,
        halationSize = 1.4f,
        diffusionFilter = "glimmerglass",
        diffusionStrength = .8f,
        diffusionSize = 1.2f,
        printYellow = 12f,
        printMagenta = -8f,
        preflash = .12f,
        scanSharpenAmount = .7f,
        scanSharpenRadius = 1.1f,
        filmFormatMm = 56f,
        lensBlurUm = 15f,
        scanBlur = .9f,
        scatterAmount = .5f,
        scatterSize = 1.6f,
        halationBounces = 4,
        halationDecay = .35f,
        halationNormalize = false,
        dirAmount = .8f,
        dirDiffusionUm = 30f,
        dirSameLayer = .7f,
        dirInterLayer = 1.4f,
        grainSublayers = false,
        grainLayers = 3,
        grainBlur = .45f,
        grainDyeCloud = 1.2f,
        grainUniformity = .85f,
        outputRoute = "direct",
        useGpu = false,
        outputColorSpace = "Display P3",
        outputGamut = false,
        printExposureEv = .6f,
        printIlluminant = "D65",
        printCompensation = false,
        normalizePrintExposure = false,
        preflashYellow = 4f,
        preflashMagenta = -6f,
        scanWhiteCorrection = true,
        scanBlackCorrection = true,
        scanWhiteLevel = .93f,
        scanBlackLevel = .04f,
        autoExposure = true,
        metering = "matrix",
        highlightBoostEv = 1.2f,
        highlightBoostRange = .6f,
        highlightProtectEv = 2.5f,
        scanGlarePercent = 2f,
        scanGlareRoughness = .4f,
        scanGlareBlur = 1.5f,
        grainClumpSizeUm = 3f,
        grainRedScale = 1.4f,
        grainGreenScale = .8f,
        grainBlueScale = 1.7f,
        halationColor = "amber",
        grainClumpStrengthNm = 75f,
        printDiffusionFilter = "black_pro_mist",
        printDiffusionStrength = .9f,
        printDiffusionSize = 1.3f,
        neutralFiltersFromDatabase = false,
        neutralCyan = 15f,
        neutralMagenta = 75f,
        neutralYellow = 45f,
        filmGamma = 1.15f,
        paperGamma = .95f,
        paperGammaFast = 1.25f,
        paperGammaSlow = .85f,
        paperGammaRed = 1.1f,
        paperGammaGreen = .9f,
        paperGammaBlue = 1.3f,
        paperDeveloperExhaustion = .2f,
        filmDevelopmentTime = 6.5f,
        paperDevelopmentTime = 5f,
        imprint = ImprintSettings(
            dateStyle = "canon_luna", corner = "top_left", size = 1.4, glow = .3,
            exposure = true, datePlacement = "edge", dateEnabled = true,
            manualDateTime = "2024-02-29 23:58:47",
        ),
        filmBorder = true,
        filmBorderStage = 1,
        mediumFormat = MediumFormat.MF67,
        mediumPortrait = true,
        negativeWhiteHoles = false,
        composition = CompositionTransform(.35, .65, 2.25, 4.0 / 5.0, -7.3),
        crop = CropSettings(CropMode.FREE, CompositionTransform(.6, .4, 1.8, 1.23, 4.1)),
        preferredOutputRoute = "direct",
    )

    @Test fun all90NonDefaultFieldsAndNestedPresentationRoundTripExactly() {
        val input = fullState()
        val defaults = EditorState()
        val fields = EditorState::class.java.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        assertEquals("Update this fixture when the editor gains a parameter", 90, fields.size)
        fields.forEach { field ->
            field.isAccessible = true
            assertNotEquals("Fixture must exercise ${field.name}", field.get(defaults), field.get(input))
        }
        val file = target()
        val saved = SavedPresetStore(file).save("金色 120 全参数", input)
        val restored = SavedPresetStore(file).list().single()
        assertEquals(saved, restored)
        assertEquals(input, restored.parameters())
        assertEquals(input.json(), restored.snapshot)
        assertEquals(input.imprint.manualDateTime, restored.parameters().imprint.manualDateTime)
        assertEquals(input.crop, restored.parameters().crop)
        assertEquals(input.composition, restored.parameters().composition)
        assertTrue("Stored correction preference is not its disabled direct-route value", restored.parameters().scanWhiteCorrection)
        assertFalse(restored.parameters().effectiveScanWhiteCorrection)
        assertTrue(JSONObject(restored.snapshot).has("preferredOutputRoute"))
    }

    @Test fun all120FormatsBothDirectionsAnd135LatentDirectionAreRetained() {
        val file = target()
        val store = SavedPresetStore(file)
        for (format in MediumFormat.entries) for (portrait in listOf(false, true)) {
            val input = fullState().copy(mediumFormat = format, mediumPortrait = portrait)
            val item = store.save("${format.name} $portrait", input)
            assertEquals(input, SavedPresetStore(file).list().first { it.id == item.id }.parameters())
        }
        // Direction remains a saved parameter even when no 120 frame is currently selected.
        for (portrait in listOf(false, true)) {
            val input = fullState().copy(mediumFormat = null, mediumPortrait = portrait)
            assertEquals(input, store.save("135 $portrait", input).parameters())
        }
    }

    @Test fun disabledImprintChoicesAndExplicitRouteIntentRemainStored() {
        val file = target()
        val input = fullState().copy(
            filmStock = "kodak_ektachrome_100", outputRoute = "direct", preferredOutputRoute = "print",
            filmBorder = false,
            imprint = fullState().imprint.copy(dateEnabled = false, exposure = false),
        )
        SavedPresetStore(file).save("以后启用", input)
        val restored = SavedPresetStore(file).list().single().parameters()
        assertEquals(input, restored)
        assertFalse(restored.imprint.enabled)
        assertEquals("canon_luna", restored.imprint.dateStyle)
        assertEquals("2024-02-29 23:58:47", restored.imprint.manualDateTime)
        assertEquals("print", restored.preferredOutputRoute)
    }

    @Test fun independentSnapshotsSurviveNewStoreInstancesAndDeletion() {
        val file = target()
        val original = fullState()
        val first = SavedPresetStore(file).save("第一卷", original)
        val changed = original.copy(brightness = -2f, crop = CropSettings(), imprint = ImprintSettings())
        val second = SavedPresetStore(file).save("第二卷", changed)
        assertNotEquals(first.id, second.id)
        UUID.fromString(first.id)
        assertTrue(first.createdAt > 0)
        assertEquals(listOf(second, first), SavedPresetStore(file).list())
        assertEquals(original, SavedPresetStore(file).list().last().parameters())
        assertEquals(changed, SavedPresetStore(file).list().first().parameters())
        assertTrue(SavedPresetStore(file).delete(first.id))
        assertEquals(listOf(second), SavedPresetStore(file).list())
        val bytes = file.readBytes()
        assertFalse(SavedPresetStore(file).delete(first.id))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(SavedPresetStore(file).delete(second.id))
        assertTrue(SavedPresetStore(file).list().isEmpty())
    }

    @Test fun emptyStoreReadAndDeletingMissingItemDoNotCreateAFile() {
        val file = target()
        val store = SavedPresetStore(file)
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete(UUID.randomUUID().toString()))
        assertFalse(file.exists())
    }

    @Test fun namesTrimNormalizeAndRejectCaseInsensitiveDuplicatesWithoutWriting() {
        val file = target()
        val store = SavedPresetStore(file)
        val item = store.save("  Cafe\u0301 胶卷  ", EditorState())
        assertEquals("Café 胶卷", item.name)
        val before = file.readBytes()
        fails(SavedPresetStore.Problem.DUPLICATE_NAME) { store.save("CAFÉ 胶卷", fullState()) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(item), SavedPresetStore(file).list())
    }

    @Test fun invalidNamesAreRejectedBeforeAnyDiskChange() {
        val file = target()
        val store = SavedPresetStore(file)
        store.save("保留", EditorState())
        val before = file.readBytes()
        for (name in listOf("", " \t\n ", "x".repeat(61), "a\nb", "a\u0000b", "a\u007fb")) {
            fails(SavedPresetStore.Problem.INVALID_NAME) { store.save(name, fullState()) }
            assertArrayEquals("Rejected name must not overwrite existing presets", before, file.readBytes())
        }
    }

    @Test fun nameLimitCountsUnicodeCodePointsRatherThanUtf16Units() {
        val file = target()
        val store = SavedPresetStore(file)
        val sixty = "🎞".repeat(SavedPresetStore.MAX_NAME_LENGTH)
        assertEquals(sixty, store.save(sixty, EditorState()).name)
        assertEquals(sixty, SavedPresetStore(file).list().single().name)
        val before = file.readBytes()
        fails(SavedPresetStore.Problem.INVALID_NAME) { store.save(sixty + "🎞", EditorState()) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun malformedStoreAndChecksumMismatchNeverGetOverwrittenBySaveOrDelete() {
        for (checksumMismatch in listOf(false, true)) {
            val file = target()
            val store = SavedPresetStore(file)
            val item = store.save("保留", fullState())
            if (checksumMismatch) {
                val root = JSONObject(file.readText())
                root.getJSONArray("presets").getJSONObject(0).put("sha256", "not-the-snapshot-hash")
                file.writeText(root.toString())
            } else file.writeText("{ interrupted invalid JSON")
            val before = file.readBytes()
            fails(SavedPresetStore.Problem.UNREADABLE) { store.list() }
            fails(SavedPresetStore.Problem.UNREADABLE) { store.save("新卷", EditorState()) }
            fails(SavedPresetStore.Problem.UNREADABLE) { store.delete(item.id) }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun futureTopLevelVersionIsRejectedWithoutOverwritingItsBytes() {
        val file = target()
        val store = SavedPresetStore(file)
        val item = store.save("未来版本", fullState())
        file.writeText(JSONObject(file.readText()).put("version", 2).put("futureOnly", "keep me").toString())
        val before = file.readBytes()
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { store.list() }
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { store.save("新卷", EditorState()) }
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { store.delete(item.id) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun futureParameterSchemaCanBeListedButNotPartiallyAppliedOrLostOnOtherEdits() {
        val file = target()
        val first = SavedPresetStore(file).save("新版参数", fullState())
        val snapshot = changeSnapshot(file) { it.put("schemaVersion", 2).put("futureControl", "exact value") }
        val restored = SavedPresetStore(file).list().single()
        assertEquals(first.id, restored.id)
        assertEquals(snapshot, restored.snapshot)
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { restored.parameters() }
        val second = SavedPresetStore(file).save("旧版可用", EditorState())
        assertEquals(snapshot, SavedPresetStore(file).list().first { it.id == first.id }.snapshot)
        assertTrue(SavedPresetStore(file).delete(second.id))
        assertEquals(snapshot, SavedPresetStore(file).list().single().snapshot)
        assertTrue("The user can explicitly delete an incompatible preset", SavedPresetStore(file).delete(first.id))
    }

    @Test fun unknownNestedVersionIsUnavailableButTheStoredSnapshotRemainsIntact() {
        val file = target()
        SavedPresetStore(file).save("新版印字", fullState())
        val snapshot = changeSnapshot(file) { it.getJSONObject("imprint").put("version", 4) }
        val before = file.readBytes()
        val saved = SavedPresetStore(file).list().single()
        assertEquals(snapshot, saved.snapshot)
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { saved.parameters() }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun numbersThatOverflowFloatAreRejectedBeforeApplyingTheSnapshot() {
        val file = target()
        SavedPresetStore(file).save("异常数值", fullState())
        val snapshot = changeSnapshot(file) { it.put("brightnessEv", 1e100) }
        val before = file.readBytes()
        val saved = SavedPresetStore(file).list().single()
        assertEquals(snapshot, saved.snapshot)
        fails(SavedPresetStore.Problem.INCOMPATIBLE) { saved.parameters() }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun atomicBackupRecoversBothAnInterruptedBaseAndAMissingBase() {
        for (baseMissing in listOf(false, true)) {
            val file = target()
            val item = SavedPresetStore(file).save("恢复原卷", fullState())
            val committed = file.readBytes()
            val backup = File(file.path + ".bak")
            backup.writeBytes(committed)
            if (baseMissing) assertTrue(file.delete()) else file.writeText("partial replacement")
            assertEquals(listOf(item), SavedPresetStore(file).list())
            assertArrayEquals(committed, file.readBytes())
            assertFalse(backup.exists())
            SavedPresetStore(file).save("恢复后保存", EditorState())
            assertEquals(item, SavedPresetStore(file).list().last())
        }
    }

    @Test fun failedWriteReportsFailureAndDoesNotCreateAPhantomPreset() {
        // A regular file cannot become a directory; this fails deterministically even as root.
        val parentFile = temporary.newFile("blocked-parent")
        parentFile.writeText("unrelated existing data")
        val file = File(parentFile, "saved-presets.json")
        val store = SavedPresetStore(file)
        assertTrue(store.list().isEmpty())
        fails(SavedPresetStore.Problem.WRITE_FAILED) { store.save("不能保存", fullState()) }
        assertEquals("unrelated existing data", parentFile.readText())
        assertFalse(file.exists())
        assertTrue(SavedPresetStore(file).list().isEmpty())
    }
}
