package dev.filmroom.livephoto.core

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** These are explicit writer profiles, not device compatibility certifications. */
enum class LivePhotoProfile(val displayName: String, val paired: Boolean = false) {
    GOOGLE("Google Motion Photo"),
    XIAOMI_COMPAT("小米 / Redmi · 兼容候选"),
    OPLUS("OPPO / 一加 · Oplus 候选"),
    VIVO_SINGLE("vivo · 单文件候选"),
    VIVO_PAIR_2104("vivo · 双文件 2104 候选", true),
    VIVO_PAIR_LEGACY("vivo · 双文件 2200/2016 候选", true),
    HONOR_LEGACY("荣耀 · 旧 LIVE_ 尾标候选"),
    SAMSUNG_SEF_107("三星 · JPEG SEF 107 / mpv3"),
    SAMSUNG_SEF_106("三星 · JPEG SEF 106")
}

enum class ErrorCode {
    INVALID_REQUEST, UNSUPPORTED_INPUT, UNSUPPORTED_PROFILE, INVALID_JPEG, INVALID_MP4,
    INVALID_METADATA, RESOURCE_LIMIT, OUTPUT_EXISTS, VERIFICATION_FAILED,
    TRANSCODE_FAILED, COVER_FAILED, PUBLISH_FAILED, BUSY, IO
}
class LivePhotoException(val code: ErrorCode, message: String, cause: Throwable? = null) : IOException(message, cause)
internal fun demand(ok: Boolean, code: ErrorCode, message: String) { if (!ok) throw LivePhotoException(code, message) }

/** PTS is relative to the normalized video, NOT the original video. Indices are presentation order. */
data class CoverInfo(val timestampUs: Long, val frameIndex: Int, val frameCount: Int, val durationUs: Long) {
    init {
        require(durationUs > 0 && durationUs <= 60_000_000) { "Normalized clip must be <= 60 seconds" }
        require(timestampUs in 0 until durationUs && frameIndex >= 0 && frameCount in 1..100_000 && frameIndex < frameCount)
    }
}

data class PackageRequest(
    val coverJpeg: File,
    val videoMp4: File,
    val profile: LivePhotoProfile,
    val cover: CoverInfo,
    val allowExperimentalProfiles: Boolean = false,
    /** Optional reproducible ID for integration tests. Production defaults to a random 112-bit ID. */
    val bindingId: String? = null
)

data class VideoRange(val offset: Long, val length: Long)
data class AssetPart(val file: File, val mimeType: String, val sha256: String)
data class PackagedAsset(
    val id: String,
    val profile: LivePhotoProfile,
    val image: AssetPart,
    val companionVideo: AssetPart?,
    val cover: CoverInfo,
    val videoRange: VideoRange,
    val bindingId: String?,
    val report: VerificationReport
) {
    val parts: List<AssetPart> get() = listOfNotNull(image, companionVideo)
}

data class VerificationReport(
    val profile: LivePhotoProfile,
    val jpegBytes: Long,
    val videoRange: VideoRange,
    val motionExtentBytes: Long,
    val videoSha256: String,
    val warnings: List<String>,
    /** Limited generated-layout checks only, NOT full standard or Gallery certification. Null = not applicable. */
    val googleLayoutChecksPassed: Boolean?,
    val galleryConfirmed: Boolean = false
)

object SdkLimits {
    const val MAX_JPEG_BYTES: Long = 24L * 1024 * 1024
    const val MAX_VIDEO_BYTES: Long = 256L * 1024 * 1024
    const val MAX_METADATA_BYTES: Int = 65533
    const val BUFFER_BYTES: Int = 64 * 1024
    const val WRITER_REVISION = "0.1.0-alpha02"
}
internal fun randomBindingId(): String = ByteArray(14).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
