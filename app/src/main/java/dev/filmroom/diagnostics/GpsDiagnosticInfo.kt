package dev.filmroom.diagnostics

/** Presence and route only. No coordinate, URI, filename, authority or exception text. */
enum class GpsPresence { NONE, OTHER_TAGS, COORDINATE_TAGS, READ_FAILED }
enum class GpsAccessRoute { PICKER_SELECTED, SELECTED, ORIGINAL, SELECTED_FALLBACK }
enum class GpsOriginalFallback { NO_MEDIA_MAPPING, MAPPING_DENIED, MAPPING_UNSUPPORTED, MAPPING_INVALID,
    ORIGINAL_DENIED, ORIGINAL_UNSUPPORTED, ORIGINAL_MISSING, ORIGINAL_INVALID, ORIGINAL_NULL }
enum class GpsDiagnosticPhase { IMPORT_SNAPSHOT, EXPORT_SNAPSHOT, OUTPUT_READBACK }
data class GpsDiagnosticInfo(
    val phase:GpsDiagnosticPhase?=null,
    val locationPermission:Boolean?=null,
    val route:GpsAccessRoute?=null,
    val fallback:GpsOriginalFallback?=null,
    val input:GpsPresence?=null,
    val snapshot:GpsPresence?=null,
    val output:GpsPresence?=null,
    val sameTagSet:Boolean?=null,
) {
    internal fun fields():Map<String,Any> = buildMap {
        phase?.let{put("gpsPhase",it.name)}
        locationPermission?.let{put("gpsLocationPermission",it)}
        route?.let{put("gpsAccessRoute",it.name)}
        fallback?.let{put("gpsOriginalFallback",it.name)}
        input?.let{put("gpsInputPresence",it.name)}
        snapshot?.let{put("gpsSnapshotPresence",it.name)}
        output?.let{put("gpsOutputPresence",it.name)}
        sameTagSet?.let{put("gpsSameTagSet",it)}
    }
    companion object {
        /** Complete coordinate tag set, not a claim that a coordinate is geographically valid. */
        fun presence(keys:Set<String>):GpsPresence = when {
            keys.containsAll(listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef"))->GpsPresence.COORDINATE_TAGS
            keys.any{it.startsWith("GPS")}->GpsPresence.OTHER_TAGS
            else->GpsPresence.NONE
        }
        internal fun fromFields(f:Map<String,String>):GpsDiagnosticInfo? = GpsDiagnosticInfo(
            GpsDiagnosticPhase.entries.firstOrNull{it.name==f["gpsPhase"]},
            f["gpsLocationPermission"]?.toBooleanStrictOrNull(),
            GpsAccessRoute.entries.firstOrNull{it.name==f["gpsAccessRoute"]},
            GpsOriginalFallback.entries.firstOrNull{it.name==f["gpsOriginalFallback"]},
            GpsPresence.entries.firstOrNull{it.name==f["gpsInputPresence"]},
            GpsPresence.entries.firstOrNull{it.name==f["gpsSnapshotPresence"]},
            GpsPresence.entries.firstOrNull{it.name==f["gpsOutputPresence"]},
            f["gpsSameTagSet"]?.toBooleanStrictOrNull(),
        ).takeIf{it.fields().isNotEmpty()}
    }
}
