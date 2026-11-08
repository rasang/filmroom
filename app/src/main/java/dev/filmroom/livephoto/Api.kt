package dev.filmroom.livephoto

import android.net.Uri
import dev.filmroom.livephoto.core.*

/** All positions are milliseconds. coverOffsetMs is relative to the selected clip, not the original. */
data class VideoToLiveRequest(
    val input: Uri,
    /** null selects by target brand and this environment's explicit gallery feedback. */
    val profile: LivePhotoProfile? = null,
    val startMs: Long = 0,
    val durationMs: Long = 3000,
    val coverOffsetMs: Long = 0,
    val keepAudio: Boolean = true,
    val maxLongEdge: Int = 1920,
    val jpegQuality: Int = 95,
    val allowExperimentalProfiles: Boolean = false,
    val saveToGallery: Boolean = true,
    val relativePath: String = "DCIM/Filmroom/",
    val exportTimeoutMs: Long = 180_000,
    /** null = this device. Non-null = another target: local evidence is intentionally not reused. */
    val targetBrand: TargetBrand? = null,
    val targetGalleryPackage: String? = null,
    val requireConfirmedSupport: Boolean = false
) {
    init {
        require(input.scheme == "content" || input.scheme == "file") { "Only local content:// and file:// sources are allowed" }
        require(startMs in 0..(Long.MAX_VALUE / 1000 - 60_000) && durationMs in 100..60_000)
        require(coverOffsetMs in 0 until durationMs)
        require(maxLongEdge in 2..8192 && jpegQuality in 60..100)
        require(exportTimeoutMs in 10_000..600_000)
        require(targetBrand == null || targetGalleryPackage == null) { "A remote target cannot use a local gallery" }
        require(targetGalleryPackage == null || targetGalleryPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
    }
}

enum class ConversionStage { PROBING, TRANSCODING, EXTRACTING_COVER, PACKAGING, VERIFYING, PUBLISHING, COMPLETE }
data class ConversionProgress(val stage: ConversionStage, val percent: Int? = null)
/** A returned Uri is a published media row. It does not certify vendor Gallery recognition. */
data class ConversionResult(val asset: PackagedAsset, val publishedUris: List<Uri>, val galleryConfirmed: Boolean = false, val selection: ProfileSelection? = null)

/** Scope feedback to media settings; do not claim an SDR first-frame test covers HDR or other modes. */
internal fun VideoToLiveRequest.compatibilitySettingsKey(): String =
    "jpeg-avc-sdr|edge=$maxLongEdge|quality=$jpegQuality|duration=$durationMs|cover=$coverOffsetMs|audio=$keepAudio"

