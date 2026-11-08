package dev.filmroom.livephoto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Optional host adapter. Supply an authorized vendor SDK call; no proprietary AAR is bundled. */
fun interface DeviceSupportProbe { fun isSupported(): Boolean? }
data class GalleryTarget(val packageName: String, val label: String, val versionCode: Long)

/** Device discovery never upgrades a writer to supported. Only explicit gallery feedback does that. */
class LivePhotoCompatibility(
    context: Context,
    private val deviceProbes: Map<TargetBrand, DeviceSupportProbe> = emptyMap()
) {
    private val app = context.applicationContext
    private val resolver = ProfileResolver(FileCompatibilityStore(File(app.noBackupFilesDir, "livephoto-sdk/compatibility")))
    private val manager get() = app.packageManager
    private fun viewIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(
        Uri.parse("content://media/external/images/media/0"), "image/jpeg")

    @Suppress("DEPRECATION")
    private fun galleries(): List<GalleryTarget> = try {
        manager.queryIntentActivities(viewIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.enabled == true && it.activityInfo.exported && it.activityInfo.packageName != app.packageName }
            .mapNotNull { match ->
                try {
                    val name = match.activityInfo.packageName
                    val info = manager.getPackageInfo(name, 0)
                    GalleryTarget(name, match.loadLabel(manager).toString(), info.longVersionCode)
                } catch (_: Exception) { null }
            }.distinctBy { it.packageName }.sortedBy { it.label }
    } catch (_: Exception) { emptyList() }

    /** Image viewers, not a claim that these apps implement Motion Photo. */
    suspend fun availableGalleries(): List<GalleryTarget> = withContext(Dispatchers.IO) { galleries() }

    @Suppress("DEPRECATION")
    private fun defaultGallery(): String? {
        val available = galleries()
        val resolved = try { manager.resolveActivity(viewIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            catch (_: Exception) { null }
        // The system chooser is not a gallery. Ambiguity remains UNKNOWN until the host/user selects one.
        return available.firstOrNull { it.packageName == resolved }?.packageName
            ?: available.singleOrNull()?.packageName
    }

    @Suppress("DEPRECATION")
    private fun environment(packageName: String?, settings: String): TargetEnvironment {
        val selected = packageName ?: defaultGallery()
        val version = if (selected == null) null else try { manager.getPackageInfo(selected, 0).longVersionCode }
            catch (_: Exception) { null }
        return TargetEnvironment(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.FINGERPRINT,
            Build.VERSION.SDK_INT, selected, version, settings)
    }

    suspend fun resolve(request: VideoToLiveRequest): ProfileSelection = withContext(Dispatchers.IO) {
        val local = request.targetBrand == null
        val env = if (local) environment(request.targetGalleryPackage, request.compatibilitySettingsKey()) else null
        val brand = request.targetBrand ?: ProfileResolver.brandOf(Build.MANUFACTURER, Build.BRAND)
        val probe = if (local) deviceProbes[brand] else null
        val hint = probe?.let {
            val status = try {
                when (it.isSupported()) { true -> SupportStatus.SUPPORTED; false -> SupportStatus.UNSUPPORTED; null -> SupportStatus.UNKNOWN }
            } catch (_: Exception) { SupportStatus.UNKNOWN } catch (_: LinkageError) { SupportStatus.UNKNOWN }
            DeviceSupportHint(status, "HOST_VENDOR_ADAPTER",
                "仅为宿主接入的厂商设备能力结果，不能替代此 profile 的相册验收。")
        }
        currentCoroutineContext().ensureActive()
        resolver.resolve(brand, env, request.profile, request.coverOffsetMs == 0L, hint)
    }

    /**
     * User must check the exact output in the identified gallery first. Never call on converter errors.
     * YES/NO affect only this profile and output-settings key. UNKNOWN removes this specific record.
     */
    suspend fun recordGalleryResult(result: ConversionResult, status: SupportStatus): CompatibilityRecord? = withContext(Dispatchers.IO) {
        val selection = requireNotNull(result.selection) { "Missing target selection snapshot" }
        val old = requireNotNull(selection.environment) { "Remote target cannot be confirmed on the generating phone" }
        require(old.canRemember) { "请先选择具体相册；相册包或版本未知，不能记录兼容结论。" }
        val current = environment(old.galleryPackage, old.mediaSettingsKey)
        require(current.canRemember && current.key == old.key) { "系统或相册版本已变化，请重新生成并验收。" }
        require(result.asset.profile == selection.selectedProfile && result.publishedUris.size == result.asset.parts.size) {
            "Only published output with a matching profile can be confirmed"
        }
        val coroutine = currentCoroutineContext()
        // Reject missing/edited files or access failures rather than turning them into unsupported evidence.
        for ((index, part) in result.asset.parts.withIndex()) {
            val hash = app.contentResolver.openInputStream(result.publishedUris[index])?.use {
                FileDigests.sha256(it) { coroutine.ensureActive() }
            } ?: throw LivePhotoException(ErrorCode.IO, "Cannot read the published asset for confirmation")
            require(hash == part.sha256) { "输出文件已被修改，请重新生成后测试。" }
        }
        coroutine.ensureActive()
        resolver.remember(selection, status, result.asset.image.sha256)
    }

    suspend fun clearRecords() = withContext(Dispatchers.IO) { resolver.clear() }
}

