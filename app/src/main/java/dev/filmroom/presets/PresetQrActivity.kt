package dev.filmroom.presets

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Import saves a new named preset only. This activity has no access to the photo editor. */
class PresetQrActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[PresetQrViewModel::class.java] }
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var name: EditText
    private lateinit var qr: ImageView
    private lateinit var scan: Button
    private lateinit var album: Button
    private lateinit var save: Button
    private lateinit var share: Button
    private lateinit var close: Button
    private var sharing = false
    private var shownDraft: PresetQrCodec.Draft? = null
    private var restoredName: String? = null
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.externalPending = false
        render(model.state.value)
        val data = result.data
        when {
            result.resultCode != RESULT_OK -> finish()
            data?.data != null -> model.read(data.data!!)
            data?.getStringExtra(RecipeScannerActivity.EXTRA_QR_TEXT) != null ->
                model.read(data.getStringExtra(RecipeScannerActivity.EXTRA_QR_TEXT)!!)
            else -> finish()
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> model.externalPending = false; render(model.state.value); uri?.let { model.read(it) } }
    private fun text(id: Int, vararg args: Any?) = L10n.text(id, *args)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredName = savedInstanceState?.getString("qrName")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        val title = TextView(this).apply { text = text(R.string.preset_qr_title); textSize = 22f; setPadding(0, 0, 0, dp(16)) }
        body.addView(title)
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; textSize = 16f; setPadding(0, 0, 0, dp(16)) }
        body.addView(status)
        details = TextView(this).apply { setPadding(0, 0, 0, dp(12)) }
        body.addView(details)
        qr = ImageView(this).apply { tag = "preset-qr-image"; contentDescription = text(R.string.preset_qr_image_description); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.WHITE) }
        body.addView(qr, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val bitmap = (qr.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val width = PresetQrImage.displayWidth(bitmap.width, body.width - body.paddingLeft - body.paddingRight, resources.displayMetrics.density)
                if (qr.maxWidth != width) qr.maxWidth = width
            }
        }
        name = EditText(this).apply { tag = "preset-qr-name"; hint = text(R.string.saved_preset_name); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        body.addView(name, LinearLayout.LayoutParams(-1, -2))
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            text = text(label); isAllCaps = false; minHeight = dp(48); setSingleLine(false); setOnClickListener { action() }
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        scan = button(R.string.preset_qr_scan) {
            if (model.externalPending) return@button
            model.externalPending = true; render(model.state.value)
            startScanner()
        }
        album = button(R.string.preset_qr_album) {
            if (model.externalPending) return@button
            model.externalPending = true; render(model.state.value)
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: Exception) { model.externalPending = false; render(model.state.value); status.text = text(R.string.preset_qr_invalid) }
        }
        save = button(R.string.preset_qr_save_new) { model.nameDraft = name.text.toString(); model.save(model.nameDraft) }
        share = button(R.string.preset_qr_share_image) { shareImage() }
        close = button(R.string.saved_preset_cancel) { finish() }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        setContentView(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // An approved atomic store write is not cancellable midway; do not imply otherwise.
                if (model.state.value.phase != "working") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
        if (!model.initialized) {
            model.initialized = true
            val draft = savedInstanceState?.getString("qrDraft")
            val id = intent.getStringExtra(EXTRA_PRESET_ID)
            when {
                draft != null -> model.read(draft)
                id != null -> model.share(id)
                savedInstanceState == null -> when (intent.getStringExtra(EXTRA_IMPORT_SOURCE)) {
                    SOURCE_CAMERA -> scan.performClick()
                    SOURCE_ALBUM -> album.performClick()
                }
            }
        }
    }
    private fun startScanner() {
        try {
            scanner.launch(Intent(this, RecipeScannerActivity::class.java))
        } catch (_: Exception) { model.externalPending = false; render(model.state.value); status.text = text(R.string.preset_qr_camera_denied) }
    }
    private fun render(value: PresetQrViewModel.Screen) {
        val busy = value.phase == "working"
        val confirm = value.phase == "confirm" || busy && value.draft != null && value.bitmap == null
        val shared = value.phase == "share" || busy && value.bitmap != null
        scan.visibility = if (value.phase == "home") View.VISIBLE else View.GONE
        album.visibility = scan.visibility
        qr.visibility = if (shared) View.VISIBLE else View.GONE
        name.visibility = if (confirm) View.VISIBLE else View.GONE
        save.visibility = name.visibility
        share.visibility = qr.visibility
        listOf(scan, album, save, share).forEach { it.isEnabled = !busy && !sharing && !model.externalPending }
        close.isEnabled = !busy
        close.text = text(if (value.phase == "saved") R.string.preset_qr_done else R.string.saved_preset_cancel)
        status.text = value.error?.let(::problem) ?: text(when (value.phase) {
            "working" -> R.string.preset_qr_working
            "share" -> R.string.preset_qr_share_scope
            "confirm" -> R.string.preset_qr_import_scope
            "saved" -> R.string.preset_qr_saved
            else -> R.string.preset_qr_home
        })
        details.visibility = if (value.draft != null) View.VISIBLE else View.GONE
        value.draft?.let { draft ->
            details.text = text(R.string.preset_qr_details, draft.name, draft.parameters.filmStock, draft.parameters.paperStock)
            if (confirm && shownDraft !== draft) { name.setText(restoredName ?: model.nameDraft.ifEmpty { draft.name }); restoredName = null; shownDraft = draft }
        }
        if (shared) {
            value.bitmap?.let { qr.maxWidth = PresetQrImage.displayWidth(it.width, resources.displayMetrics.widthPixels - dp(40), resources.displayMetrics.density) }
            qr.setImageBitmap(value.bitmap)
            (qr.drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false
        }
    }
    private fun shareImage() {
        val bitmap = model.state.value.takeIf { it.phase == "share" }?.bitmap ?: return
        if (sharing) return
        sharing = true; share.isEnabled = false
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val folder = File(cacheDir, "preset-qr-shares").apply { mkdirs() }
                    // Only our old generated PNGs. Never traverse another cache or touch source photos.
                    folder.listFiles()?.filter { it.isFile && it.name.startsWith("preset-") && it.extension == "png" && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                    val file = File(folder, "preset-${UUID.randomUUID()}.png")
                    file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                    FileProvider.getUriForFile(this@PresetQrActivity, "$packageName.presetqr", file)
                }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Filmroom preset", uri) }
                startActivity(Intent.createChooser(send, text(R.string.preset_qr_share_image)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = text(R.string.preset_qr_share_failed) }
            finally { sharing = false; share.isEnabled = true }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val state = model.state.value
        if (state.phase == "confirm") { outState.putString("qrDraft", state.payload); outState.putString("qrName", name.text.toString()); model.nameDraft = name.text.toString() }
        super.onSaveInstanceState(outState)
    }
    private fun problem(error: Exception): String = text(when {
        error is PresetQrCodec.Failure -> when (error.problem) {
            PresetQrCodec.Problem.INCOMPATIBLE -> R.string.preset_qr_incompatible
            PresetQrCodec.Problem.TOO_LARGE -> R.string.preset_qr_too_large
            else -> R.string.preset_qr_invalid
        }
        error is SavedPresetStore.Failure -> when (error.problem) {
            SavedPresetStore.Problem.DUPLICATE_NAME -> R.string.saved_preset_duplicate_name
            SavedPresetStore.Problem.INVALID_NAME -> R.string.saved_preset_invalid_name
            SavedPresetStore.Problem.FULL -> R.string.saved_preset_full
            SavedPresetStore.Problem.INCOMPATIBLE -> R.string.preset_qr_incompatible
            SavedPresetStore.Problem.UNREADABLE -> R.string.saved_preset_unreadable
            else -> R.string.saved_preset_write_failed
        }
        else -> R.string.preset_qr_invalid
    })
    companion object {
        const val EXTRA_PRESET_ID = "presetId"
        const val EXTRA_IMPORT_SOURCE = "importSource"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_ALBUM = "album"
    }
}

class PresetQrFileProvider : FileProvider()
