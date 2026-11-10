package dev.filmroom.livephoto.core

/** Device/SDK hints, never evidence that a specific Filmroom output played in a gallery. */
data class VivoCapability(val status: SupportStatus, val evidence: Evidence, val readState: ReadState, val declaredValue: Int? = null) {
    enum class Evidence { DECLARED_SUPPORTED, DECLARED_UNSUPPORTED, SDK_MODEL_MATCH, UNKNOWN }
    enum class ReadState { VALUE, MISSING, INVALID, UNREADABLE }
    val blocksExport get() = evidence == Evidence.DECLARED_UNSUPPORTED
    val diagnostic get() = "vivo capability: $evidence; property=$readState; value=${declaredValue ?: "unknown"}"

    companion object {
        const val SUPPORT_PROPERTY = "ro.vivo.camera.livephoto.support"
        const val MODEL_PROPERTY = "ro.product.model.bbk"
        // DeviceSupport in vivo SDK 2.4.2 bundled with Mimo 2.12.8, not a future-device deny list.
        internal val SDK_MODELS = setOf(
            "PD2203", "PD2190", "PD2207", "PD2244", "PD2239", "PD2245",
            "PD2282", "PD2283", "PD2285", "PD2284", "PD2323", "PD2334",
            "PD2344", "PD2241", "PD2242", "PD2227", "PD2309", "PD2324",
            "PD2324HA", "PD2359", "PD2366", "PD2303", "PD2337", "PD2307",
            "PD2329", "PD2362", "PD2364", "PD2405", "PD2406", "PD2415", "PD2419",
            "PD2266", "PD2338", "PD2339", "PD2339FA", "PD2403"
        )
        fun read(reader: (String) -> String?): VivoCapability {
            var state = ReadState.VALUE
            val value = try {
                val raw = reader(SUPPORT_PROPERTY)
                if (raw.isNullOrEmpty()) { state = ReadState.MISSING; null }
                else raw.toIntOrNull().also { if (it == null) state = ReadState.INVALID }
            } catch (_: Exception) { state = ReadState.UNREADABLE; null }
              catch (_: LinkageError) { state = ReadState.UNREADABLE; null }
            if (value == 1) return VivoCapability(SupportStatus.SUPPORTED, Evidence.DECLARED_SUPPORTED, state, value)
            if (value == 2) return VivoCapability(SupportStatus.UNSUPPORTED, Evidence.DECLARED_UNSUPPORTED, state, value)
            val matched = try { reader(MODEL_PROPERTY) in SDK_MODELS }
                catch (_: Exception) { false } catch (_: LinkageError) { false }
            return if (matched) VivoCapability(SupportStatus.SUPPORTED, Evidence.SDK_MODEL_MATCH, state, value)
            else VivoCapability(SupportStatus.UNKNOWN, Evidence.UNKNOWN, state, value)
        }
    }
}
