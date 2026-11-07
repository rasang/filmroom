package dev.filmroom.presets

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A local, cancellable preview. Only the explicit Share image action opens the system chooser. */
internal class PresetQrShareDialog(
    private val activity: ComponentActivity,
    private val showReadable: (AlertDialog) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private var renderJob: Job? = null
    private var shareJob: Job? = null
    private var bitmap: Bitmap? = null
    private var sharing = false
    var presetId: String? = null
        private set
    val isShowing: Boolean get() = dialog?.isShowing == true
    private fun text(id: Int) = L10n.text(id)
    private fun qrWidth(bitmapWidth: Int, availablePixels: Int) =
        PresetQrImage.displayWidth(bitmapWidth, availablePixels, activity.resources.displayMetrics.density)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun active(target: AlertDialog) = dialog === target && target.isShowing &&
        !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun show(item: SavedPresetStore.Preset) {
        if (dialog != null) return
        presetId = item.id
        val qr = ImageView(activity).apply {
            tag = "recipe-share-qr"
            contentDescription = text(R.string.preset_qr_image_description)
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        val status = TextView(activity).apply {
            tag = "recipe-share-status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = text(R.string.preset_qr_working)
            setPadding(0, dp(12), 0, 0)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
            addView(TextView(activity).apply {
                text = item.name; textSize = 18f
                setPadding(0, 0, 0, dp(16)); setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(qr, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(status, LinearLayout.LayoutParams(-1, -2))
        }
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            bitmap?.let { image ->
                val width = qrWidth(image.width, body.width - body.paddingLeft - body.paddingRight)
                if (qr.maxWidth != width) qr.maxWidth = width
            }
        }
        // Wrap short content; cap long content so actions remain outside the scroll area.
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = dp((activity.resources.configuration.screenHeightDp - 220).coerceIn(80, 520))
                val allowed = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(allowed, MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }
        val target = AlertDialog.Builder(activity).setTitle(text(R.string.preset_qr_title)).setView(scroll)
            .setNegativeButton(text(R.string.preset_qr_done), null)
            .setPositiveButton(text(R.string.preset_qr_share_image), null).create()
        dialog = target
        target.setOnDismissListener {
            if (dialog === target) {
                renderJob?.cancel(); shareJob?.cancel()
                qr.setImageDrawable(null)
                // The background PNG writer may still hold its own reference until it finishes.
                // Do not recycle a bitmap while that writer is using it.
                bitmap = null; dialog = null; presetId = null; sharing = false
                onDismiss()
            }
        }
        showReadable(target)
        target.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false; minimumHeight = dp(48) }
        target.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight = dp(48)
        target.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val image = bitmap ?: return@setOnClickListener
            if (!active(target) || sharing) return@setOnClickListener
            sharing = true
            target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            shareJob = activity.lifecycleScope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) {
                        val folder = File(activity.cacheDir, "preset-qr-shares").apply { mkdirs() }
                        folder.listFiles()?.filter { it.isFile && it.name.startsWith("preset-") && it.extension == "png" && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                        val file = File(folder, "preset-${UUID.randomUUID()}.png")
                        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        FileProvider.getUriForFile(activity, "${activity.packageName}.presetqr", file)
                    }
                    if (!active(target)) return@launch
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Filmroom recipe", uri) }
                    activity.startActivity(Intent.createChooser(send, text(R.string.preset_qr_share_image)))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (active(target)) status.text = text(R.string.preset_qr_share_failed) }
                finally { if (dialog === target) { sharing = false; target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = bitmap != null } }
            }
        }
        renderJob = activity.lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.Default) {
                    val payload = PresetQrCodec.encode(item.name, item.parameters())
                    PresetQrImage.render(activity, payload)
                }
                if (!active(target)) return@launch
                bitmap = image
                qr.maxWidth = qrWidth(image.width, activity.resources.displayMetrics.widthPixels - dp(96))
                qr.setImageBitmap(image)
                (qr.drawable as? BitmapDrawable)?.paint?.isFilterBitmap = false
                qr.visibility = View.VISIBLE
                status.text = text(R.string.preset_qr_share_scope)
                target.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (active(target)) status.text = text(when {
                error is PresetQrCodec.Failure && error.problem == PresetQrCodec.Problem.TOO_LARGE -> R.string.preset_qr_too_large
                error is SavedPresetStore.Failure && error.problem == SavedPresetStore.Problem.INCOMPATIBLE -> R.string.preset_qr_incompatible
                else -> R.string.preset_qr_share_failed
            }) }
        }
    }

    fun close() { dialog?.dismiss() }
}
