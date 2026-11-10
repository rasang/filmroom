package dev.filmroom.livephoto.core

/**
 * Admission rules observed in DJI Mimo 2.12.8, not gallery acceptance evidence.
 * Honor calls LivePhotoUtils.isSupportLivePhotoPlay (SDK > 33, exact HONOR).
 * Samsung uses ManufacturerUtils.f (case-insensitive samsung) and SDK >= 34.
 * Mimo's Samsung writer is OLive; Filmroom's SEF writer is deliberately unchanged.
 * A missing environment denotes a remote/unknown target and must not borrow local gates.
 */
object LocalExportPolicy {
    const val OPLUS_OLIVE_FEATURE = "oplus.software.gallery.olive"
    fun readOplusFeature(query: (String) -> Boolean): Boolean? = try {
        query(OPLUS_OLIVE_FEATURE)
    } catch (_: Exception) { null } catch (_: LinkageError) { null }

    fun blockedReason(brand: TargetBrand, environment: TargetEnvironment?): String? {
        val env = environment ?: return null
        if (brand == TargetBrand.OPLUS) {
            val recognized = listOf("OPPO", "ONEPLUS", "REALME").any { env.manufacturer.equals(it, true) }
            return when {
                !recognized -> "当前设备未通过厂商 Live Photo 导出条件检查，请导出视频。"
                env.oplusOliveFeature == false -> "系统未提供 OLive 相册能力，请导出视频。"
                else -> null // Query failure is unknown, not a confirmed negative feature result.
            }
        }
        if (brand != TargetBrand.HONOR && brand != TargetBrand.SAMSUNG) return null
        if (env.sdkInt < 34) return "当前 Live Photo 导出策略要求 Android 14 或以上，请导出视频。"
        val recognized = when (brand) {
            TargetBrand.HONOR -> env.manufacturer == "HONOR"
            TargetBrand.SAMSUNG -> env.manufacturer.equals("samsung", ignoreCase = true)
            else -> true
        }
        return if (recognized) null else "当前设备未通过厂商 Live Photo 导出条件检查，请导出视频。"
    }
}
