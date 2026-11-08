package dev.filmroom.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import dev.filmroom.R
import java.util.UUID

/**
 * A foreground, user-initiated repair flow. Backup metadata cannot initiate a permission request,
 * acquire a URI grant or silently associate a same-named image. Construct after super.onCreate
 * and before onStart, so restored state and Activity Result registration are both available. The caller verifies the selected
 * image and persists the association after [onRelink] (which only fires after explicit confirmation).
 */
internal class ArchivedPhotoAccessUi(
    private val activity: ComponentActivity,
    private val onPermissionResult: (jobId: String, access: Access) -> Unit,
    private val onRelink: suspend (jobId: String, uri: Uri) -> Boolean,
    private val isLinked: (jobId: String, uri: Uri) -> Boolean = { _, _ -> false },
) : DefaultLifecycleObserver {
    enum class Access { GRANTED, PARTIAL, DENIED }
    private enum class Stage { IDLE, OFFER, PERMISSION, PICKER, CONFIRM, SAVING }
    private var stage = Stage.IDLE
    private var jobId: String? = null
    private var selected: Uri? = null
    private var dialog: AlertDialog? = null
    val busy: Boolean get() = stage != Stage.IDLE

    init {
        activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)?.let { saved ->
            val id = saved.getString("job")?.takeIf(::validJobId)
            val restored = runCatching { Stage.valueOf(saved.getString("stage").orEmpty()) }.getOrNull()
            if (id != null && restored != null && restored != Stage.IDLE) {
                jobId = id
                stage = restored
                selected = saved.getString("selected")?.let(Uri::parse)?.takeIf(::validSelection)
                if ((stage == Stage.CONFIRM && selected == null) || stage == Stage.SAVING) reset()
            }
        }
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply {
                putString("job", jobId)
                putString("stage", stage.name)
                // Only the URI explicitly chosen in the system picker, never bitmap bytes or a filename guess.
                selected?.let { putString("selected", it.toString()) }
            }
        }
        activity.lifecycle.addObserver(this)
    }

    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (stage != Stage.PERMISSION) return@registerForActivityResult
        val id = jobId ?: return@registerForActivityResult reset()
        val access = currentAccess(activity)
        reset()
        onPermissionResult(id, access)
        if (access != Access.GRANTED) toast(if (access == Access.PARTIAL) ArchivedPhotoAccessText.PARTIAL.text(activity) else ArchivedPhotoAccessText.DENIED.text(activity))
    }
    private val picker = activity.registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }) { uri ->
        if (stage != Stage.PICKER) return@registerForActivityResult
        if (uri == null) { reset(); return@registerForActivityResult }
        if (!validSelection(uri)) { reset(); toast(ArchivedPhotoAccessText.INVALID_SELECTION.text(activity)); return@registerForActivityResult }
        selected = uri
        stage = Stage.CONFIRM
        showPending()
    }

    /** Call only from an explicit tap on a failed archived-photo action. Never call while binding cards. */
    fun offer(id: String) {
        if (busy || !validJobId(id) || activity.isFinishing || activity.isDestroyed) return
        jobId = id
        selected = null
        stage = Stage.OFFER
        showPending()
    }

    override fun onStart(owner: LifecycleOwner) { showPending() }
    override fun onDestroy(owner: LifecycleOwner) {
        dialog?.setOnDismissListener(null)
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        activity.lifecycle.removeObserver(this)
    }

    private fun showPending() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || dialog?.isShowing == true || activity.isFinishing) return
        when (stage) {
            Stage.OFFER -> show(AlertDialog.Builder(activity)
                .setTitle(ArchivedPhotoAccessText.TITLE.text(activity))
                .setMessage(ArchivedPhotoAccessText.EXPLANATION.text(activity))
                .setPositiveButton(ArchivedPhotoAccessText.GRANT.text(activity)) { _, _ ->
                    stage = Stage.PERMISSION
                    val current = currentAccess(activity)
                    if (current == Access.GRANTED) {
                        val id = jobId
                        reset()
                        if (id != null) onPermissionResult(id, current)
                    } else try { permissions.launch(requestedPermissions()) }
                    catch (_: Exception) { reset(); toast(ArchivedPhotoAccessText.DENIED.text(activity)) }
                }
                .setNeutralButton(ArchivedPhotoAccessText.CHOOSE.text(activity)) { _, _ ->
                    stage = Stage.PICKER
                    try { picker.launch(arrayOf("image/*")) }
                    catch (_: Exception) { reset(); toast(ArchivedPhotoAccessText.INVALID_SELECTION.text(activity)) }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> reset() }, Stage.OFFER)
            Stage.CONFIRM -> show(AlertDialog.Builder(activity)
                .setTitle(ArchivedPhotoAccessText.LINK_TITLE.text(activity))
                .setMessage(ArchivedPhotoAccessText.LINK_CONFIRM.text(activity))
                .setPositiveButton(ArchivedPhotoAccessText.LINK.text(activity)) { _, _ ->
                    if (stage != Stage.CONFIRM) return@setPositiveButton
                    val id = jobId
                    val uri = selected
                    // Enter a separate state before callback so repeated button clicks cannot relink twice.
                    stage = Stage.SAVING
                    selected = null
                    if (id != null && uri != null) {
                        try {
                            val hadGrant = activity.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            val durable = activity.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                            if (!durable) { reset(); toast(ArchivedPhotoAccessText.PERSIST_FAILED.text(activity)); return@setPositiveButton }
                            // Enter the transaction immediately: even cancellation before the first dispatch
                            // must execute its grant cleanup finally block.
                            activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    val committed = finishConfirmedRelink(
                                        hadGrant = hadGrant,
                                        isLinked = { isLinked(id, uri) },
                                        relink = { onRelink(id, uri) },
                                        release = { activity.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                                    )
                                    if (!committed) toast(ArchivedPhotoAccessText.LINK_FAILED.text(activity))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                  catch (_: Exception) { toast(ArchivedPhotoAccessText.LINK_FAILED.text(activity)) }
                                finally { reset() }
                            }
                        } catch (_: Exception) { reset(); toast(ArchivedPhotoAccessText.PERSIST_FAILED.text(activity)) }
                    } else reset()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> reset() }, Stage.CONFIRM)
            else -> Unit // A pending system result is not permission and must never be replayed.
        }
    }

    private fun show(builder: AlertDialog.Builder, shownStage: Stage) {
        val value = builder.create()
        dialog = value
        value.setOnCancelListener { if (stage == shownStage) reset() }
        value.setOnDismissListener {
            if (dialog === value) dialog = null
            // System launches changed stage first; a simple back/outside dismissal clears the draft.
            if (stage == shownStage) reset()
        }
        value.show()
        value.window?.setBackgroundDrawableResource(R.drawable.filmroom_dialog_background)
    }
    private fun reset() { stage = Stage.IDLE; jobId = null; selected = null }
    private fun toast(message: CharSequence) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val STATE_KEY = "archived-photo-access"
        /** A failed/cancelled callback can already have committed atomically; inspect durable state first. */
        internal suspend fun finishConfirmedRelink(
            hadGrant: Boolean,
            isLinked: () -> Boolean,
            relink: suspend () -> Boolean,
            release: () -> Unit,
        ): Boolean {
            var committed = false
            try {
                committed = relink()
                return committed
            } finally {
                // If durable state cannot be read, do not revoke access that an already-saved link may need.
                val linked = if (!hadGrant && !committed) runCatching(isLinked).getOrDefault(true) else true
                if (!hadGrant && !committed && !linked) runCatching(release)
            }
        }

        internal fun validJobId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        internal fun validSelection(uri: Uri): Boolean = uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
            !uri.authority.orEmpty().contains('@') && !uri.path.isNullOrBlank() && uri.fragment == null &&
            uri.toString().length <= 4096 && uri.toString().none { it.code < 32 }
        internal fun requestedPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        fun currentAccess(context: android.content.Context): Access {
            val full = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (context.checkSelfPermission(full) == PackageManager.PERMISSION_GRANTED) return Access.GRANTED
            if (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return Access.PARTIAL
            return Access.DENIED
        }
    }
}
