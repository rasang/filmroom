package dev.filmroom.presets

import dev.filmroom.model.EditorState
import org.json.JSONObject

/** Additive fields use current defaults only when absent. Supplied fields are never overwritten. */
internal object PresetQrParameters {
    private val ranges = mapOf(
        "filmExposureEv" to -4.0..4.0,
        "brightnessEv" to -2.0..2.0,
        "contrast" to 0.5..1.5,
        "warmth" to -1.0..1.0,
        "saturation" to 0.0..2.0,
        "diffusionStrength" to 0.0..2.0,
        "diffusionSize" to 0.25..2.0,
        "halationAmount" to 0.0..2.0,
        "halationSize" to 0.25..3.0,
        "grainAmount" to 0.0..2.0,
        "grainSize" to 0.25..3.0,
        "printExposureEv" to -4.0..4.0,
        "printYellow" to -30.0..30.0,
        "printMagenta" to -30.0..30.0,
        "preflash" to 0.0..0.2,
        "scanSharpenAmount" to 0.0..2.0,
        "scanSharpenRadius" to 0.2..3.0,
        "filmFormatMm" to 8.0..120.0,
        "highlightBoostEv" to 0.0..3.0,
        "highlightBoostRange" to 0.0..1.0,
        "highlightProtectEv" to 0.0..4.0,
        "lensBlurUm" to 0.0..100.0,
        "scatterAmount" to 0.0..2.0,
        "scatterSize" to 0.25..3.0,
        "halationBounces" to 1.0..5.0,
        "halationDecay" to 0.0..1.0,
        "grainRedScale" to 0.25..3.0,
        "grainGreenScale" to 0.25..3.0,
        "grainBlueScale" to 0.25..3.0,
        "grainLayers" to 1.0..4.0,
        "grainBlur" to 0.0..2.0,
        "grainDyeCloud" to 0.0..3.0,
        "grainUniformity" to 0.5..1.0,
        "grainClumpSizeUm" to 0.0..100.0,
        "grainClumpStrengthNm" to 0.0..1000.0,
        "filmGamma" to 0.5..2.0,
        "dirAmount" to 0.0..2.0,
        "dirDiffusionUm" to 0.0..100.0,
        "dirSameLayer" to 0.0..2.0,
        "dirInterLayer" to 0.0..2.0,
        "printDiffusionStrength" to 0.0..2.0,
        "printDiffusionSize" to 0.25..2.0,
        "neutralCyan" to 0.0..200.0,
        "neutralMagenta" to 0.0..200.0,
        "neutralYellow" to 0.0..200.0,
        "paperGamma" to 0.5..2.0,
        "paperGammaFast" to 0.5..2.0,
        "paperGammaSlow" to 0.5..2.0,
        "paperGammaRed" to 0.5..2.0,
        "paperGammaGreen" to 0.5..2.0,
        "paperGammaBlue" to 0.5..2.0,
        "paperDeveloperExhaustion" to 0.0..1.0,
        "preflashYellow" to -30.0..30.0,
        "preflashMagenta" to -30.0..30.0,
        "scanBlur" to 0.0..3.0,
        "scanGlarePercent" to 0.0..5.0,
        "scanGlareRoughness" to 0.0..1.0,
        "scanGlareBlur" to 0.0..20.0,
        "scanWhiteLevel" to 0.5..1.0,
        "scanBlackLevel" to 0.0..0.2,
        "filmDevelopmentTime" to 0.0..60.0,
        "paperDevelopmentTime" to 0.0..60.0,
        "seed" to 42.0..42.0,
    )
    private val choices = mapOf(
        "diffusionFilter" to setOf("off", "glimmerglass", "black_pro_mist", "pro_mist", "cinebloom"),
        "printDiffusionFilter" to setOf("off", "glimmerglass", "black_pro_mist", "pro_mist", "cinebloom"),
        "outputRoute" to setOf("print", "direct"),
        "preferredOutputRoute" to setOf("print", "direct"),
        "outputColorSpace" to setOf("sRGB", "Display P3", "Adobe RGB (1998)"),
        "printIlluminant" to setOf("TH-KG3", "D50", "D55", "D65"),
        "metering" to dev.filmroom.diagnostics.MeteringMode.entries.map { it.id }.toSet(),
        "halationColor" to setOf("stock", "red", "amber", "neutral"),
        "mode" to setOf("preview"),
    )

    fun read(input: JSONObject, frozenNulls: Boolean = false): EditorState = try {
        input.optJSONObject("imprint")?.let { imprint ->
            // Earlier imprint readers intentionally ignore fields added in later versions.
            // External codes may omit those fields, but must not smuggle values that would be lost.
            val version = imprint.optInt("version", 3)
            if (version < 2) require(!imprint.has("datePlacement") && !imprint.has("layoutVersion"))
            if (version < 3) require(!imprint.has("dateEnabled") && !imprint.has("manualDateTime"))
        }
        val defaults = JSONObject(SavedPresetStore.snapshot(EditorState()))
        val filled = mergeForImport(input, defaults, frozenNulls)
        require(filled.getInt("schemaVersion") == 1)
        choices.forEach { (key, allowed) -> if (!filled.isNull(key)) require(filled.getString(key) in allowed) }
        ranges.forEach { (key, range) ->
            val number = filled.getDouble(key)
            require(number.isFinite() && number >= range.start.toFloat().toDouble() && number <= range.endInclusive.toFloat().toDouble())
        }
        for (key in listOf("halationBounces", "grainLayers", "seed")) require(filled.getDouble(key) == filled.getInt(key).toDouble())
        val value = EditorState.fromJson(filled.toString())
        // A valid preset must never need the legacy route normalizer to silently change a supplied value.
        if (input.has("outputRoute")) require(value.outputRoute == input.getString("outputRoute"))
        value
    } catch (error: PresetQrCodec.Failure) { throw error }
    catch (_: Exception) { throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE) }

    /** Pure seam also tests app-default changes without changing immutable wire constants. */
    internal fun mergeForImport(input: JSONObject, defaults: JSONObject, frozenNulls: Boolean): JSONObject {
        val filled = merge(input, defaults, "parameters", frozenNulls)
        if (defaults.has("filmBorderStage")) {
            // A rendering-stage migration is not a new cosmetic default. Missing means the
            // legacy post-composite stage, even when new editors default to pre-imaging.
            if (!input.has("filmBorderStage")) filled.put("filmBorderStage", 0)
            else {
                val stage = input.get("filmBorderStage")
                require((stage is Int || stage is Long) && (stage as Number).toLong() in 0L..1L)
            }
        }
        if (frozenNulls) {
            // The wire explicitly represented null, overriding any later non-null app default.
            // Existing EditorState / composition JSON represent these nulls by absence.
            if (filled.isNull("mediumFormat")) filled.remove("mediumFormat")
            for (objectValue in listOf(filled.getJSONObject("composition"), filled.getJSONObject("crop").getJSONObject("transform")))
                if (objectValue.isNull("targetAspect")) objectValue.remove("targetAspect")
        }
        return filled
    }

    private fun merge(input: JSONObject, defaults: JSONObject, path: String, frozenNulls: Boolean): JSONObject {
        val result = JSONObject(defaults.toString())
        for (key in input.keys()) {
            val value = input.get(key)
            val special = when {
                path == "parameters" && key == "preferredOutputRoute" -> "nullableString"
                path == "parameters.imprint" && key == "manualDateTime" -> "nullableString"
                path == "parameters" && key == "mediumFormat" -> "string"
                (path == "parameters.composition" || path == "parameters.crop.transform") && key == "targetAspect" -> "number"
                (path == "parameters.composition" || path == "parameters.crop.transform") && key == "straightenDegrees" -> "finiteNumber"
                else -> null
            }
            if (!defaults.has(key) && special == null) throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)
            val base = defaults.opt(key)
            when {
                special == "nullableString" -> require(value === JSONObject.NULL || value is String && value.length <= 128)
                special == "string" -> require(value is String || frozenNulls && value === JSONObject.NULL)
                special == "finiteNumber" -> require(value is Number && value.toDouble().isFinite())
                special == "number" -> require(value is Number && value.toDouble().isFinite() || frozenNulls && value === JSONObject.NULL)
                base is JSONObject -> { require(value is JSONObject); result.put(key, merge(value, base, "$path.$key", frozenNulls)); continue }
                base is Boolean -> require(value is Boolean)
                base is Number -> { require(value is Number && value.toDouble().isFinite() && value.toFloat().isFinite()); if (key in setOf("version", "schemaVersion", "layoutVersion")) require(value.toDouble() == value.toInt().toDouble()) }
                base is String -> require(value is String && value.length <= 128)
                base === JSONObject.NULL -> require(value === JSONObject.NULL || value is String && value.length <= 128)
                else -> throw IllegalArgumentException()
            }
            result.put(key, value)
        }
        return result
    }
}
