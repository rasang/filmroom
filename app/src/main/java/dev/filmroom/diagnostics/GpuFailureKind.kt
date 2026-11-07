package dev.filmroom.diagnostics

/** Closed identifiers only; raw GPU/driver error strings never enter diagnostics. */
enum class GpuFailureKind(val id:String) {
    UNKNOWN_UNWIND("unknown_unwind"), SUBMIT_UNWIND("submit_unwind"), POLL_UNWIND("poll_unwind"), MAP_FAILED("map_failed"), MAP_TIMEOUT("map_timeout");
    companion object { fun fromId(id:String?):GpuFailureKind? = entries.firstOrNull { it.id == id } }
}
