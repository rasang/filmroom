package dev.filmroom.presets

import dev.filmroom.diagnostics.*
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.filmroom.R
import dev.filmroom.i18n.L10n

/** Camera and album are equally available, including when camera permission is denied. */
class RecipeScannerActivity : ComponentActivity() {
    private lateinit var camera: DecoratedBarcodeView
    private lateinit var status: TextView
    private lateinit var album: Button
    private var pickerPending = false
    private var returned = false
    private var resumed = false
    private var permissionRequested = false
    private var scanLog: RecipeLog? = null
    private var cameraFailureLogged = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) resumeCamera() else { scanLog?.note(RecipeReason.PERMISSION_DENIED); showCameraUnavailable() }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerPending = false
        album.isEnabled = true
        if (uri != null) deliver(Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        else { scanLog?.at(RecipeStep.CAMERA); resumeCamera() }
    }
    private fun text(id: Int) = L10n.text(id)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun buttonBackground() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x44FFFFFF),
        android.graphics.drawable.GradientDrawable().apply { setColor(0xCC242424.toInt()); cornerRadius = dp(24).toFloat() }, null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanLog = RecipeLog(RecipeAction.SCAN)
        scanLog?.at(RecipeStep.CAMERA)
        pickerPending = savedInstanceState?.getBoolean("pickerPending") ?: false
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        camera = DecoratedBarcodeView(this).apply {
            tag = "recipe-camera-preview"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setStatusText("")
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
        root.addView(camera, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            tag = "recipe-camera-status"; text = text(R.string.preset_qr_scan_prompt)
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(0x99000000.toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(64) })
        val close = Button(this).apply {
            text = text(R.string.saved_preset_cancel); tag = "recipe-camera-close"
            isAllCaps = false; minHeight = dp(48); setTextColor(Color.WHITE); background = buttonBackground()
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply { rightMargin = dp(16); topMargin = dp(8) })
        album = Button(this).apply {
            text = text(R.string.preset_qr_album); contentDescription = text
            tag = "recipe-camera-album"; isAllCaps = false; minWidth = dp(112); minHeight = dp(48)
            setTextColor(Color.WHITE); background = buttonBackground(); setPadding(dp(20), 0, dp(20), 0)
            isEnabled = !pickerPending
            setOnClickListener {
                if (pickerPending || returned) return@setOnClickListener
                pickerPending = true; isEnabled = false; camera.pause()
                scanLog?.at(RecipeStep.ALBUM)
                try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (error: Exception) { scanLog?.note(RecipeLog.reason(error)); pickerPending = false; isEnabled = true; resumeCamera() }
            }
        }
        root.addView(album, FrameLayout.LayoutParams(-2, dp(56), Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = dp(20); bottomMargin = dp(24)
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        camera.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (!pickerPending && resumed && !returned) deliver(Intent().putExtra(EXTRA_QR_TEXT, result.text))
            }
        })
        camera.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (!cameraFailureLogged) { cameraFailureLogged = true; scanLog?.note(RecipeReason.CAMERA_ERROR) }
                showCameraUnavailable()
            }
        })
        if (!hasPermission() && !permissionRequested) {
            permissionRequested = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun showCameraUnavailable() { status.text = text(R.string.preset_qr_camera_denied) }
    private fun resumeCamera() {
        if (!resumed || pickerPending || returned || isFinishing) return
        if (hasPermission()) { status.text = text(R.string.preset_qr_scan_prompt); camera.resume() }
        else showCameraUnavailable()
    }
    private fun deliver(result: Intent) {
        if (returned || isFinishing) return
        scanLog?.end(Outcome.SUCCESS)
        returned = true; camera.pause(); setResult(Activity.RESULT_OK, result); finish()
    }
    override fun onDestroy() { scanLog?.end(Outcome.CANCELLED); super.onDestroy() }
    override fun onResume() { super.onResume(); resumed = true; resumeCamera() }
    override fun onPause() { resumed = false; camera.pause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pickerPending", pickerPending)
        outState.putBoolean("permissionRequested", permissionRequested)
        super.onSaveInstanceState(outState)
    }
    companion object { const val EXTRA_QR_TEXT = "recipeQrText" }
}
