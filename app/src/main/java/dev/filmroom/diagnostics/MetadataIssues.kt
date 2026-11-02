package dev.filmroom.diagnostics

/** Names describe the schema, never the photo's values. Unknown input keys are not logged. */
enum class MetadataField {
    METADATA, UNKNOWN,
    ImageDescription,
    Make,
    Model,
    Artist,
    Copyright,
    Rating,
    RatingPercent,
    DateTimeOriginal,
    DateTimeDigitized,
    OffsetTimeOriginal,
    OffsetTimeDigitized,
    SubSecTimeOriginal,
    SubSecTimeDigitized,
    CameraOwnerName,
    BodySerialNumber,
    LensMake,
    LensModel,
    LensSerialNumber,
    ImageUniqueID,
    ExposureTime,
    FNumber,
    ApertureValue,
    MaxApertureValue,
    SubjectDistance,
    FocalLength,
    DigitalZoomRatio,
    ExposureIndex,
    ShutterSpeedValue,
    BrightnessValue,
    ExposureBiasValue,
    PhotographicSensitivity,
    SensitivityType,
    ExposureProgram,
    MeteringMode,
    LightSource,
    Flash,
    FocalLengthIn35mmFilm,
    ExposureMode,
    WhiteBalance,
    SceneCaptureType,
    GainControl,
    Contrast,
    Saturation,
    Sharpness,
    SubjectDistanceRange,
    StandardOutputSensitivity,
    RecommendedExposureIndex,
    ISOSpeed,
    LensSpecification,
    UserComment,
    GPSLatitudeRef,
    GPSLongitudeRef,
    GPSSatellites,
    GPSStatus,
    GPSMeasureMode,
    GPSSpeedRef,
    GPSTrackRef,
    GPSImgDirectionRef,
    GPSMapDatum,
    GPSDestLatitudeRef,
    GPSDestLongitudeRef,
    GPSDestBearingRef,
    GPSDestDistanceRef,
    GPSDateStamp,
    GPSAltitude,
    GPSDOP,
    GPSSpeed,
    GPSTrack,
    GPSImgDirection,
    GPSDestBearing,
    GPSDestDistance,
    GPSHPositioningError,
    GPSAltitudeRef,
    GPSDifferential,
    GPSLatitude,
    GPSLongitude,
    GPSTimeStamp,
    GPSDestLatitude,
    GPSDestLongitude,
    GPSVersionID,
    GPSProcessingMethod,
    GPSAreaInformation;
    companion object { fun fromId(value:String)=entries.firstOrNull{it.name==value} }
}
enum class MetadataIssueReason { INVALID_TEXT, INVALID_DATE, INVALID_OFFSET, INVALID_SUBSECONDS, INVALID_NUMERIC, FIELD_TOO_LONG, SNAPSHOT_BUDGET, UNSUPPORTED_TEXT_ENCODING, UNKNOWN_FIELD, ISSUE_LIMIT, READ_FAILED, OUTPUT_VALIDATION_FAILED, CONTAINER_WRITE_FAILED }
enum class MetadataDateBasis { ASSUMED_IMPORT_ZONE }
enum class MetadataIssuePhase { READ_VALIDATION, WRITE_VALIDATE, WRITE_EXCEPTION }
data class MetadataIssue(val field:MetadataField,val reason:MetadataIssueReason)
object MetadataIssues {
    const val MAX_ITEMS=24
    const val MAX_TEXT=2048
    fun bounded(items:List<MetadataIssue>):List<MetadataIssue> {
        val distinct=items.distinct()
        return if(distinct.size<=MAX_ITEMS)distinct else distinct.take(MAX_ITEMS-1)+MetadataIssue(MetadataField.METADATA,MetadataIssueReason.ISSUE_LIMIT)
    }
    fun encode(items:List<MetadataIssue>):String=bounded(items).joinToString(","){"${it.field.name}:${it.reason.name}"}
    fun parse(text:String):List<MetadataIssue> {
        if(text.length>MAX_TEXT)return emptyList()
        return text.split(',').take(MAX_ITEMS).mapNotNull { item ->
            val parts=item.split(':');if(parts.size!=2)return@mapNotNull null
            val field=MetadataField.fromId(parts[0]) ?: return@mapNotNull null
            val reason=MetadataIssueReason.entries.firstOrNull{it.name==parts[1]} ?: return@mapNotNull null
            MetadataIssue(field,reason)
        }.distinct()
    }
}
