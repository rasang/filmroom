package dev.filmroom.livephoto.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** Support is for this profile + environment + output settings, never the whole brand. */
enum class SupportStatus { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class TargetBrand { OPLUS, VIVO, XIAOMI, HONOR, SAMSUNG, OTHER }
enum class SelectionReason { MANUAL, USER_CONFIRMED, CANDIDATE, NO_CANDIDATE }

/** No serial number, Android ID, media URI, GPS or account information is stored. */
data class TargetEnvironment(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val buildFingerprint: String,
    val sdkInt: Int,
    val galleryPackage: String?,
    val galleryVersion: Long?,
    val mediaSettingsKey: String,
    val writerRevision: String = SdkLimits.WRITER_REVISION
) {
    private fun known(value: String?) = !value.isNullOrBlank() && !value.equals("unknown", true)
    val canRemember: Boolean get() = known(manufacturer) && known(brand) && known(model) &&
        known(buildFingerprint) && sdkInt > 0 && known(galleryPackage) &&
        galleryVersion != null && galleryVersion >= 0 && mediaSettingsKey.isNotBlank()
    val key: String get() {
        // Length-prefix encoding prevents delimiter collisions in user/build strings.
        val fields = listOf("capability-v1", manufacturer, brand, model, buildFingerprint,
            sdkInt.toString(), galleryPackage.orEmpty(), galleryVersion?.toString().orEmpty(),
            mediaSettingsKey, writerRevision)
        val encoded = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class CompatibilityRecord(
    val environmentKey: String,
    val profile: LivePhotoProfile,
    val status: SupportStatus,
    val checkedAtMs: Long,
    val assetSha256: String
)

/** Device/vendor API observations are advisory; they do not certify our custom writer. */
data class DeviceSupportHint(val status: SupportStatus, val source: String, val detail: String)
data class ProfileCapability(val profile: LivePhotoProfile, val status: SupportStatus, val record: CompatibilityRecord?)
data class ProfileSelection(
    val brand: TargetBrand,
    val environment: TargetEnvironment?,
    val selectedProfile: LivePhotoProfile?,
    val status: SupportStatus,
    val reason: SelectionReason,
    val candidates: List<ProfileCapability>,
    val warnings: List<String>,
    val deviceHint: DeviceSupportHint? = null
)

interface CompatibilityStore {
    fun read(environmentKey: String, profile: LivePhotoProfile): CompatibilityRecord?
    fun write(record: CompatibilityRecord)
    fun remove(environmentKey: String, profile: LivePhotoProfile)
    fun clear()
}

/** Private, bounded, atomic records. Invalid/unreadable records are UNKNOWN, never UNSUPPORTED. */
class FileCompatibilityStore(private val directory: File): CompatibilityStore {
    private fun file(key: String, profile: LivePhotoProfile): File {
        require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid environment key" }
        return File(directory, "$key-${profile.name}.properties")
    }
    override fun read(environmentKey: String, profile: LivePhotoProfile): CompatibilityRecord? {
        val f = file(environmentKey, profile)
        return try {
            if (!f.isFile || f.length() !in 1..4096) return null
            val p = Properties().apply { f.inputStream().use { load(it) } }
            if (p.getProperty("schema") != "1" || p.getProperty("key") != environmentKey ||
                p.getProperty("profile") != profile.name) return null
            val status = SupportStatus.valueOf(p.getProperty("status"))
            val time = p.getProperty("time")?.toLongOrNull() ?: return null
            val hash = p.getProperty("asset") ?: return null
            if (status == SupportStatus.UNKNOWN || time < 0 || !hash.matches(Regex("[0-9a-f]{64}"))) return null
            CompatibilityRecord(environmentKey, profile, status, time, hash)
        } catch (_: Exception) { null }
    }
    override fun write(record: CompatibilityRecord) {
        require(record.status != SupportStatus.UNKNOWN && record.checkedAtMs >= 0)
        require(record.assetSha256.matches(Regex("[0-9a-f]{64}")))
        val destination = file(record.environmentKey, record.profile)
        Files.createDirectories(directory.toPath())
        val temp = File(directory, ".${UUID.randomUUID()}.tmp")
        val p = Properties().apply {
            setProperty("schema", "1"); setProperty("key", record.environmentKey)
            setProperty("profile", record.profile.name); setProperty("status", record.status.name)
            setProperty("time", record.checkedAtMs.toString()); setProperty("asset", record.assetSha256)
        }
        try {
            FileOutputStream(temp).use { p.store(it, "Explicit user gallery feedback"); it.fd.sync() }
            try { Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    override fun remove(environmentKey: String, profile: LivePhotoProfile) { Files.deleteIfExists(file(environmentKey, profile).toPath()) }
    override fun clear() {
        // Only SDK capability records; never media or publication journals.
        directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}-[A-Z0-9_]+\\.properties")) }
            ?.forEach { Files.deleteIfExists(it.toPath()) }
    }
}

class ProfileResolver(private val store: CompatibilityStore, private val nowMs: () -> Long = System::currentTimeMillis) {
    companion object {
        const val RECORD_TTL_MS = 90L * 24 * 60 * 60 * 1000
        fun brandOf(manufacturer: String, brand: String): TargetBrand {
            // Branding is a hint, not evidence of support. Exact matching avoids substring surprises.
            fun map(value: String): TargetBrand? = when (value.trim().lowercase(Locale.ROOT)) {
                "oppo", "oneplus", "realme", "oplus" -> TargetBrand.OPLUS
                "vivo", "iqoo" -> TargetBrand.VIVO
                "xiaomi", "redmi", "poco" -> TargetBrand.XIAOMI
                "honor" -> TargetBrand.HONOR
                "samsung" -> TargetBrand.SAMSUNG
                else -> null
            }
            return map(brand) ?: map(manufacturer) ?: TargetBrand.OTHER
        }
        fun candidates(brand: TargetBrand, firstFrame: Boolean = true): List<LivePhotoProfile> {
            val family = when (brand) {
                TargetBrand.OPLUS -> listOf(LivePhotoProfile.OPLUS)
                TargetBrand.XIAOMI -> listOf(LivePhotoProfile.XIAOMI_COMPAT)
                TargetBrand.HONOR -> listOf(LivePhotoProfile.HONOR_LEGACY)
                TargetBrand.SAMSUNG -> listOf(LivePhotoProfile.SAMSUNG_SEF_107, LivePhotoProfile.SAMSUNG_SEF_106)
                TargetBrand.VIVO -> listOf(LivePhotoProfile.VIVO_SINGLE, LivePhotoProfile.VIVO_PAIR_2104) +
                    if (firstFrame) listOf(LivePhotoProfile.VIVO_PAIR_LEGACY) else emptyList()
                TargetBrand.OTHER -> emptyList()
            }
            return family + LivePhotoProfile.GOOGLE
        }
    }
    fun resolve(
        brand: TargetBrand,
        environment: TargetEnvironment? = null,
        manual: LivePhotoProfile? = null,
        firstFrame: Boolean = true,
        deviceHint: DeviceSupportHint? = null
    ): ProfileSelection {
        require(manual != LivePhotoProfile.VIVO_PAIR_LEGACY || firstFrame) { "Legacy vivo supports first-frame cover only" }
        val now = nowMs()
        val observed = LivePhotoProfile.values().filter { firstFrame || it != LivePhotoProfile.VIVO_PAIR_LEGACY }.map { profile ->
            val record = if (environment?.canRemember == true) store.read(environment.key, profile)?.takeIf {
                it.environmentKey == environment.key && it.profile == profile && it.checkedAtMs >= 0 &&
                    it.checkedAtMs <= now && now - it.checkedAtMs <= RECORD_TTL_MS &&
                    it.status != SupportStatus.UNKNOWN
            } else null
            ProfileCapability(profile, record?.status ?: SupportStatus.UNKNOWN, record)
        }
        // A manually tested cross-brand profile may genuinely work in this gallery. Remember it too.
        val profiles = (listOfNotNull(manual) + candidates(brand, firstFrame) +
            observed.filter { it.status == SupportStatus.SUPPORTED }.map { it.profile }).distinct()
        val states = profiles.map { profile -> observed.single { it.profile == profile } }
        val chosen = if (manual != null) states.first() else
            states.filter { it.status == SupportStatus.SUPPORTED }.maxByOrNull { it.record!!.checkedAtMs }
                ?: states.firstOrNull { it.status == SupportStatus.UNKNOWN }
        val reason = when {
            manual != null -> SelectionReason.MANUAL
            chosen == null -> SelectionReason.NO_CANDIDATE
            chosen.status == SupportStatus.SUPPORTED -> SelectionReason.USER_CONFIRMED
            else -> SelectionReason.CANDIDATE
        }
        val warnings = mutableListOf("品牌仅决定候选顺序；生成和保存成功不会被记为相册支持。")
        if (environment == null) warnings += "接收端环境未知；不使用本机相册测试记录。"
        else if (!environment.canRemember) warnings += "相册包或版本等信息不完整；不会保存或套用兼容记录。"
        if (chosen == null) warnings += "当前候选在此环境均有失败记录；不代表手机永远不支持实况。可手动重试或清除记录。"
        if (chosen?.status == SupportStatus.UNSUPPORTED) warnings += "手动选择了有失败记录的格式；本次只是重试，不会覆盖旧结果。"
        if (deviceHint != null) warnings += "厂商能力返回仅为线索，不等于当前自写封装格式通过相册验收。"
        return ProfileSelection(brand, environment, chosen?.profile, chosen?.status ?: SupportStatus.UNSUPPORTED,
            reason, states, warnings, deviceHint)
    }
    /** Call only after the user has viewed THIS output in the identified target gallery. */
    fun remember(selection: ProfileSelection, status: SupportStatus, assetSha256: String): CompatibilityRecord? {
        val env = requireNotNull(selection.environment) { "Remote/unknown target cannot be calibrated on this device" }
        require(env.canRemember) { "A concrete gallery package/version and device environment are required" }
        val profile = requireNotNull(selection.selectedProfile)
        if (status == SupportStatus.UNKNOWN) { store.remove(env.key, profile); return null }
        val record = CompatibilityRecord(env.key, profile, status, nowMs(), assetSha256)
        store.write(record)
        return record
    }
    fun clear() = store.clear()
}
