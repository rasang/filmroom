package dev.filmroom.presets

import android.app.Application
import dev.filmroom.border.MediumFormat
import dev.filmroom.imprint.ImprintSettings
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.CropMode
import dev.filmroom.model.CropSettings
import dev.filmroom.model.EditorState
import org.json.JSONArray
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

/** Independent of the JSON writer: newly added nested fields must acquire a legal test probe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SavedPresetCoverageHostTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Probe(val path: String, val change: (EditorState) -> EditorState)
    private fun probe(path: String, change: (EditorState) -> EditorState) = Probe(path, change)

    // Do not generate this list from JSON keys: that would repeat a serialization omission.
    // Each constructor/copy call validates its nested value before it reaches the serializer.
    private val probes = listOf(
        probe("filmStock") { it.copy(filmStock = "kodak_gold_200") },
        probe("paperStock") { it.copy(paperStock = "kodak_2393") },
        probe("brightness") { it.copy(brightness = .75f) },
        probe("filmExposure") { it.copy(filmExposure = -.5f) },
        probe("contrast") { it.copy(contrast = 1.2f) },
        probe("warmth") { it.copy(warmth = -.25f) },
        probe("saturation") { it.copy(saturation = 1.3f) },
        probe("grain") { it.copy(grain = .4f) },
        probe("grainSize") { it.copy(grainSize = 1.7f) },
        probe("halation") { it.copy(halation = .6f) },
        probe("halationSize") { it.copy(halationSize = 1.4f) },
        probe("diffusionFilter") { it.copy(diffusionFilter = "glimmerglass") },
        probe("diffusionStrength") { it.copy(diffusionStrength = .8f) },
        probe("diffusionSize") { it.copy(diffusionSize = 1.2f) },
        probe("printYellow") { it.copy(printYellow = 12f) },
        probe("printMagenta") { it.copy(printMagenta = -8f) },
        probe("preflash") { it.copy(preflash = .12f) },
        probe("scanSharpenAmount") { it.copy(scanSharpenAmount = .7f) },
        probe("scanSharpenRadius") { it.copy(scanSharpenRadius = 1.1f) },
        probe("filmFormatMm") { it.copy(filmFormatMm = 56f) },
        probe("lensBlurUm") { it.copy(lensBlurUm = 15f) },
        probe("scanBlur") { it.copy(scanBlur = .9f) },
        probe("scatterAmount") { it.copy(scatterAmount = .5f) },
        probe("scatterSize") { it.copy(scatterSize = 1.6f) },
        probe("halationBounces") { it.copy(halationBounces = 4) },
        probe("halationDecay") { it.copy(halationDecay = .35f) },
        probe("halationNormalize") { it.copy(halationNormalize = false) },
        probe("dirAmount") { it.copy(dirAmount = .8f) },
        probe("dirDiffusionUm") { it.copy(dirDiffusionUm = 30f) },
        probe("dirSameLayer") { it.copy(dirSameLayer = .7f) },
        probe("dirInterLayer") { it.copy(dirInterLayer = 1.4f) },
        probe("grainSublayers") { it.copy(grainSublayers = false) },
        probe("grainLayers") { it.copy(grainLayers = 3) },
        probe("grainBlur") { it.copy(grainBlur = .45f) },
        probe("grainDyeCloud") { it.copy(grainDyeCloud = 1.2f) },
        probe("grainUniformity") { it.copy(grainUniformity = .85f) },
        probe("outputRoute") { it.copy(outputRoute = "direct") },
        probe("useGpu") { it.copy(useGpu = false) },
        probe("outputColorSpace") { it.copy(outputColorSpace = "Display P3") },
        probe("outputGamut") { it.copy(outputGamut = false) },
        probe("printExposureEv") { it.copy(printExposureEv = .6f) },
        probe("printIlluminant") { it.copy(printIlluminant = "D65") },
        probe("printCompensation") { it.copy(printCompensation = false) },
        probe("normalizePrintExposure") { it.copy(normalizePrintExposure = false) },
        probe("preflashYellow") { it.copy(preflashYellow = 4f) },
        probe("preflashMagenta") { it.copy(preflashMagenta = -6f) },
        probe("scanWhiteCorrection") { it.copy(scanWhiteCorrection = true) },
        probe("scanBlackCorrection") { it.copy(scanBlackCorrection = true) },
        probe("scanWhiteLevel") { it.copy(scanWhiteLevel = .93f) },
        probe("scanBlackLevel") { it.copy(scanBlackLevel = .04f) },
        probe("autoExposure") { it.copy(autoExposure = true) },
        probe("metering") { it.copy(metering = "matrix") },
        probe("highlightBoostEv") { it.copy(highlightBoostEv = 1.2f) },
        probe("highlightBoostRange") { it.copy(highlightBoostRange = .6f) },
        probe("highlightProtectEv") { it.copy(highlightProtectEv = 2.5f) },
        probe("scanGlarePercent") { it.copy(scanGlarePercent = 2f) },
        probe("scanGlareRoughness") { it.copy(scanGlareRoughness = .4f) },
        probe("scanGlareBlur") { it.copy(scanGlareBlur = 1.5f) },
        probe("grainClumpSizeUm") { it.copy(grainClumpSizeUm = 3f) },
        probe("grainRedScale") { it.copy(grainRedScale = 1.4f) },
        probe("grainGreenScale") { it.copy(grainGreenScale = .8f) },
        probe("grainBlueScale") { it.copy(grainBlueScale = 1.7f) },
        probe("halationColor") { it.copy(halationColor = "amber") },
        probe("grainClumpStrengthNm") { it.copy(grainClumpStrengthNm = 75f) },
        probe("printDiffusionFilter") { it.copy(printDiffusionFilter = "black_pro_mist") },
        probe("printDiffusionStrength") { it.copy(printDiffusionStrength = .9f) },
        probe("printDiffusionSize") { it.copy(printDiffusionSize = 1.3f) },
        probe("neutralFiltersFromDatabase") { it.copy(neutralFiltersFromDatabase = false) },
        probe("neutralCyan") { it.copy(neutralCyan = 15f) },
        probe("neutralMagenta") { it.copy(neutralMagenta = 75f) },
        probe("neutralYellow") { it.copy(neutralYellow = 45f) },
        probe("filmGamma") { it.copy(filmGamma = 1.15f) },
        probe("paperGamma") { it.copy(paperGamma = .95f) },
        probe("paperGammaFast") { it.copy(paperGammaFast = 1.25f) },
        probe("paperGammaSlow") { it.copy(paperGammaSlow = .85f) },
        probe("paperGammaRed") { it.copy(paperGammaRed = 1.1f) },
        probe("paperGammaGreen") { it.copy(paperGammaGreen = .9f) },
        probe("paperGammaBlue") { it.copy(paperGammaBlue = 1.3f) },
        probe("paperDeveloperExhaustion") { it.copy(paperDeveloperExhaustion = .2f) },
        probe("filmDevelopmentTime") { it.copy(filmDevelopmentTime = 6.5f) },
        probe("paperDevelopmentTime") { it.copy(paperDevelopmentTime = 5f) },
        probe("filmBorder") { it.copy(filmBorder = true) },
        probe("filmBorderStage") { it.copy(filmBorderStage = 1) },
        probe("mediumFormat") { it.copy(mediumFormat = MediumFormat.MF67) },
        probe("mediumPortrait") { it.copy(mediumPortrait = true) },
        probe("negativeWhiteHoles") { it.copy(negativeWhiteHoles = false) },
        probe("preferredOutputRoute") { it.copy(preferredOutputRoute = "direct") },
        probe("imprint.dateStyle") { it.copy(imprint = it.imprint.copy(dateStyle = "canon_luna")) },
        probe("imprint.corner") { it.copy(imprint = it.imprint.copy(corner = "top_left")) },
        probe("imprint.size") { it.copy(imprint = it.imprint.copy(size = 1.4)) },
        probe("imprint.glow") { it.copy(imprint = it.imprint.copy(glow = .3)) },
        probe("imprint.exposure") { it.copy(imprint = it.imprint.copy(exposure = true)) },
        probe("imprint.datePlacement") { it.copy(imprint = it.imprint.copy(datePlacement = "edge")) },
        probe("imprint.dateEnabled") { it.copy(imprint = it.imprint.copy(dateEnabled = true)) },
        probe("imprint.manualDateTime") { it.copy(imprint = it.imprint.copy(manualDateTime = "2024-02-29 23:58:47")) },
        probe("composition.centerX") { it.copy(composition = it.composition.copy(centerX = .35)) },
        probe("composition.centerY") { it.copy(composition = it.composition.copy(centerY = .65)) },
        probe("composition.zoom") { it.copy(composition = it.composition.copy(zoom = 2.25)) },
        probe("composition.targetAspect") { it.copy(composition = it.composition.copy(targetAspect = .8)) },
        probe("composition.straightenDegrees") { it.copy(composition = it.composition.copy(straightenDegrees = -7.3)) },
        probe("crop.mode") { it.copy(crop = it.crop.copy(mode = CropMode.FREE)) },
        probe("crop.transform.centerX") { it.copy(crop = it.crop.copy(transform = it.crop.transform.copy(centerX = .6))) },
        probe("crop.transform.centerY") { it.copy(crop = it.crop.copy(transform = it.crop.transform.copy(centerY = .4))) },
        probe("crop.transform.zoom") { it.copy(crop = it.crop.copy(transform = it.crop.transform.copy(zoom = 1.8))) },
        probe("crop.transform.targetAspect") { it.copy(crop = it.crop.copy(transform = it.crop.transform.copy(targetAspect = 1.23))) },
        probe("crop.transform.straightenDegrees") { it.copy(crop = it.crop.copy(transform = it.crop.transform.copy(straightenDegrees = 4.1))) },
    )

    // This is a fixed schema marker, not a user control. New exclusions require an explicit reason.
    private val fixedMetadata = mapOf<String, Any>("imprint.layoutVersion" to 1)
    private val fieldAliases = mapOf("brightness" to "brightnessEv", "filmExposure" to "filmExposureEv",
        "grain" to "grainAmount", "halation" to "halationAmount")
    private val nestedTypes = setOf(EditorState::class.java, ImprintSettings::class.java,
        CompositionTransform::class.java, CropSettings::class.java)

    private fun leaves(value: Any, prefix: String = ""): Map<String, Any?> = buildMap {
        value.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            val path = if (prefix.isEmpty()) field.name else "$prefix.${field.name}"
            val child = field.get(value)
            when {
                field.type in nestedTypes -> {
                    assertNotNull("Add an explicit fixture for nullable nested state $path", child)
                    putAll(leaves(child!!, path))
                }
                field.type.isPrimitive || field.type == String::class.java || field.type.isEnum ||
                    Number::class.java.isAssignableFrom(field.type) -> put(path, child)
                else -> fail("Unknown state type at $path: ${field.type.name}; recurse into it explicitly")
            }
        }
    }

    private fun changed(before: EditorState, after: EditorState): Set<String> {
        val left = leaves(before)
        val right = leaves(after)
        assertEquals(left.keys, right.keys)
        return left.keys.filterTo(linkedSetOf()) { left[it] != right[it] }
    }

    private fun jsonParent(json: JSONObject, path: String): Pair<JSONObject, String> {
        val segments = (fieldAliases[path] ?: path).split('.')
        var parent = json
        segments.dropLast(1).forEach { parent = parent.getJSONObject(it) }
        return parent to segments.last()
    }

    @Test fun everyRecursiveLeafHasExactlyOneNonDefaultLegalProbe() {
        val defaults = EditorState()
        val actual = leaves(defaults)
        assertEquals("Duplicate probe paths", probes.size, probes.map { it.path }.toSet().size)
        assertEquals("Add a probe for every new field, including nested state",
            actual.keys, probes.map { it.path }.toSet() + fixedMetadata.keys)
        fixedMetadata.forEach { (path, fixed) -> assertEquals("Schema marker changed: $path", fixed, actual[path]) }
        probes.forEach { probe ->
            assertEquals("Probe must change only ${probe.path}", setOf(probe.path), changed(defaults, probe.change(defaults)))
        }
    }

    @Test fun eachLeafPersistsIndividuallyWithItsExplicitJsonValue() {
        val defaults = EditorState()
        val file = File(temporary.newFolder(), "saved-presets.json")
        probes.forEach { probe ->
            val input = probe.change(defaults)
            val saved = SavedPresetStore(file).save(probe.path, input)
            val restored = SavedPresetStore(file).list().first { it.id == saved.id }
            assertEquals("Round trip omitted ${probe.path}", input, restored.parameters())
            assertEquals("Recursive values changed for ${probe.path}", leaves(input), leaves(restored.parameters()))
            val (parent, key) = jsonParent(JSONObject(saved.snapshot), probe.path)
            assertTrue("Snapshot must explicitly store nondefault ${probe.path}", parent.has(key))
            assertFalse("Nondefault ${probe.path} must not be null", parent.isNull(key))
        }
    }

    @Test fun deletingAnyProbedJsonLeafIsDetectedRatherThanPassingWithDefaults() {
        val defaults = EditorState()
        probes.forEach { probe ->
            val input = probe.change(defaults)
            val damaged = JSONObject(SavedPresetStore.snapshot(input))
            val (parent, key) = jsonParent(damaged, probe.path)
            assertTrue("Missing test target ${probe.path}", parent.has(key))
            parent.remove(key)
            val restored = runCatching { SavedPresetStore.Preset("test", "test", 0L, damaged.toString()).parameters() }
            assertTrue("The guard must detect removal of ${probe.path}", restored.isFailure || restored.getOrNull() != input)
        }
    }

    @Test fun allLeavesTogetherAndLatentPresentationChoicesSurvive() {
        val all = probes.fold(EditorState()) { state, probe -> probe.change(state) }
        val file = File(temporary.newFolder(), "saved-presets.json")
        for (input in listOf(all, all.copy(filmBorder = false, mediumFormat = null,
            imprint = all.imprint.copy(dateEnabled = false, exposure = false)))) {
            val saved = SavedPresetStore(file).save("Combined ${SavedPresetStore(file).list().size}", input)
            assertEquals(input, SavedPresetStore(file).list().first { it.id == saved.id }.parameters())
        }
    }

    @Test fun legacyOptionalFieldsAndImprintV1RemainReadableWithoutRewritingTheirSnapshot() {
        // Frozen older-format input, deliberately not produced by the current serializer.
        val snapshot = """{"schemaVersion":1,"filmStock":"kodak_portra_400","paperStock":"kodak_portra_endura","brightnessEv":0.75,"imprint":{"version":1,"dateStyle":"canon_luna","corner":"top_left","size":1.4,"glow":0.3,"exposure":true}}"""
        val file = legacyStore(snapshot)
        val bytes = file.readBytes()
        val old = SavedPresetStore(file).list().single()
        assertEquals(EditorState(brightness = .75f, imprint = ImprintSettings(dateStyle = "canon_luna",
            corner = "top_left", size = 1.4, glow = .3, exposure = true)), old.parameters())
        assertArrayEquals("Reading an older preset must not migrate its bytes", bytes, file.readBytes())
        SavedPresetStore(file).save("New preset", EditorState())
        assertEquals("Unrelated saves must retain older snapshot bytes", snapshot,
            SavedPresetStore(file).list().first { it.id == old.id }.snapshot)
    }

    private fun legacyStore(snapshot: String): File {
        val checksum = MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val row = JSONObject().put("id", "fddfa1d0-bc6c-4db6-8a34-0ed65c39e623")
            .put("name", "Legacy fixture").put("createdAt", 1L).put("parameters", snapshot).put("sha256", checksum)
        return File(temporary.newFolder(), "saved-presets.json").apply {
            writeText(JSONObject().put("version", 1).put("presets", JSONArray().put(row)).toString())
        }
    }
}
