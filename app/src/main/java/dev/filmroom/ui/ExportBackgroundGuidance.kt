package dev.filmroom.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import dev.filmroom.R
import dev.filmroom.i18n.L10n

/** Public Android signals only. STANDARD_ALLOWED is deliberately not an OEM guarantee. */
internal enum class ExportBackgroundStatus { STANDARD_ALLOWED, RESTRICTED, UNKNOWN }
internal object ExportBackgroundPolicy {
    fun assess(restricted:Boolean?, dozeExempt:Boolean?):ExportBackgroundStatus = when {
        restricted == true -> ExportBackgroundStatus.RESTRICTED
        restricted == false && dozeExempt == true -> ExportBackgroundStatus.STANDARD_ALLOWED
        else -> ExportBackgroundStatus.UNKNOWN
    }
    fun helpResource(manufacturer:String, brand:String):Int {
        val names=listOf(manufacturer,brand).map { it.lowercase(java.util.Locale.ROOT) }
        return when {
            names.any { it in setOf("vivo","iqoo") } -> R.string.export_background_help_vivo
            names.any { it in setOf("oppo","realme","oneplus") } -> R.string.export_background_help_oppo
            names.any { it in setOf("xiaomi","redmi","poco") } -> R.string.export_background_help_xiaomi
            else -> R.string.export_background_help_generic
        }
    }
    fun read(context:Context):ExportBackgroundStatus = assess(
        runCatching { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted }.getOrNull(),
        runCatching { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
    )
}

/** Only standard, public Settings routes; no private OEM components, permission requests or grants. */
internal object ExportBackgroundSettings {
    fun candidates(packageName:String)=listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    fun open(activity:Activity):Boolean = open(activity.packageName,
        { it.resolveActivity(activity.packageManager) != null }, { activity.startActivity(it) })
    internal fun open(packageName:String, resolves:(Intent)->Boolean, launch:(Intent)->Unit):Boolean {
        for (intent in candidates(packageName)) {
            try {
                if (!resolves(intent)) continue
                launch(intent)
                return true // Navigation only. This never changes or infers permission state.
            } catch (_: RuntimeException) { /* OEM route missing, inaccessible or removed: try next. */ }
        }
        return false
    }
}

/** Owns one pre-options request. Configuration changes preserve the decision, never an export job. */
class ExportBackgroundGuidance internal constructor(
    private val activity:Activity,
    private val status:()->ExportBackgroundStatus,
    private val openSettings:()->Boolean,
    private val proceed:()->Unit,
    private val verifiedProceed:()->Unit=proceed
) {
    constructor(activity:Activity, proceed:()->Unit):this(activity,
        { ExportBackgroundPolicy.read(activity) }, { ExportBackgroundSettings.open(activity) }, proceed)

    constructor(activity:Activity, proceed:()->Unit, verifiedProceed:()->Unit):this(activity,
        { ExportBackgroundPolicy.read(activity) }, { ExportBackgroundSettings.open(activity) }, proceed, verifiedProceed)

    private enum class Phase { IDLE, WARNING, SETTINGS_PENDING, RETURN_CONFIRM, CONTINUING }
    private var phase=Phase.IDLE
    private var dialog:AlertDialog?=null
    private val preferences by lazy { activity.getSharedPreferences("export-background-guidance",Context.MODE_PRIVATE) }
    private fun text(id:Int)=L10n.text(id)

    fun restore(state:Bundle?) {
        phase=when(state?.getString(STATE_KEY)) {
            Phase.WARNING.name -> Phase.WARNING
            Phase.SETTINGS_PENDING.name -> Phase.SETTINGS_PENDING
            Phase.RETURN_CONFIRM.name -> Phase.RETURN_CONFIRM
            else -> Phase.IDLE // Never recreate a continuation or enqueue work after process death.
        }
    }
    fun save(state:Bundle) { state.putString(STATE_KEY,phase.name) }
    fun offer() {
        if(phase!=Phase.IDLE)return
        if(preferences.getBoolean(IGNORE_KEY,false) || status()==ExportBackgroundStatus.STANDARD_ALLOWED)continueOnce()
        else { phase=Phase.WARNING; showWarning() }
    }
    fun onResume() {
        if(dialog!=null)return
        when(phase) {
            Phase.WARNING -> showWarning()
            Phase.SETTINGS_PENDING -> {
                // A successful navigation is not a grant. Re-read only on this explicit pending return.
                if(status()==ExportBackgroundStatus.STANDARD_ALLOWED)continueOnce(verified=true)
                else { phase=Phase.RETURN_CONFIRM;showReturnConfirmation() }
            }
            Phase.RETURN_CONFIRM -> showReturnConfirmation()
            else -> Unit
        }
    }
    /** Called when the existing options flow is dismissed (or the editor is no longer eligible). */
    fun finish() { phase=Phase.IDLE }
    fun close() { dialog?.setOnCancelListener(null);dialog?.dismiss();dialog=null }
    private fun cancel() { phase=Phase.IDLE }
    private fun continueOnce(verified:Boolean=false) {
        if(phase==Phase.CONTINUING)return
        phase=Phase.CONTINUING
        if(verified) {
            Toast.makeText(activity,text(R.string.export_background_verified_toast),Toast.LENGTH_SHORT).show()
            verifiedProceed() // Opens configuration only; never creates an export job.
        } else proceed()
    }
    private fun show(builder:AlertDialog.Builder) {
        if(activity.isFinishing || activity.isDestroyed)return
        dialog=builder.setOnCancelListener { cancel() }.create().also { current ->
            current.setOnDismissListener { if(dialog===current)dialog=null }
            current.show()
            val titleId=activity.resources.getIdentifier("alertTitle","id","android")
            if(titleId!=0)current.findViewById<TextView>(titleId)?.apply {
                maxLines=Int.MAX_VALUE;ellipsize=null;setSingleLine(false)
            }

        }
    }
    private fun explanation():String = text(if(status()==ExportBackgroundStatus.RESTRICTED)
        R.string.export_background_restricted else R.string.export_background_unknown)
    private fun showWarning() {
        // Vertical, full-width actions avoid the framework's cramped three-button row.
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(24),dp(8),dp(24),dp(16))
        }
        content.addView(TextView(activity).apply {
            text=explanation()+"\n\n"+
                text(ExportBackgroundPolicy.helpResource(Build.MANUFACTURER,Build.BRAND))
            textSize=15f
            setTextColor(FilmroomPalette.ink)
            setPadding(0,0,0,dp(16))
        })
        content.addView(TextView(activity).apply {
            text=text(R.string.export_background_help)
            textSize=12f;setTextColor(FilmroomPalette.muted)
            setPadding(0,0,0,dp(12))
        })
        fun action(id:Int, primary:Boolean=false, run:()->Unit) {
            content.addView(Button(activity).apply {
                tag=id
                text=text(id);isAllCaps=false;textSize=14f
                setSingleLine(false);ellipsize=null
                minHeight=dp(48)
                setPadding(dp(12),dp(8),dp(12),dp(8))
                setTextColor(if(primary)FilmroomPalette.surface else FilmroomPalette.ink)
                background=GradientDrawable().apply {
                    cornerRadius=dp(12).toFloat()
                    setColor(if(primary)FilmroomPalette.accent else Color.TRANSPARENT)
                }
                setOnClickListener {
                    if(phase!=Phase.WARNING)return@setOnClickListener
                    // Clear before launching: lifecycle callbacks must not see a stale dialog.
                    dialog?.dismiss();dialog=null
                    run()
                }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
        }
        action(R.string.export_background_open,true) {
            phase=Phase.SETTINGS_PENDING
            if(!openSettings()) {
                phase=Phase.RETURN_CONFIRM
                Toast.makeText(activity,text(R.string.export_background_settings_missing),Toast.LENGTH_LONG).show()
                showReturnConfirmation()
            }
        }
        action(R.string.export_background_ignore) { continueOnce() }
        action(R.string.export_background_always_ignore) {
            preferences.edit().putBoolean(IGNORE_KEY,true).apply()
            continueOnce()
        }
        show(AlertDialog.Builder(activity).setTitle(text(R.string.export_background_title))
            .setView(ScrollView(activity).apply { addView(content) }))
    }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+0.5f).toInt()
    private fun showReturnConfirmation() {
        val message=if(status()==ExportBackgroundStatus.STANDARD_ALLOWED)
            text(R.string.export_background_return_allowed) else explanation()
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16))
        }
        content.addView(TextView(activity).apply {
            text=message+"\n\n"+text(R.string.export_background_return_message)
            textSize=15f;setTextColor(FilmroomPalette.ink);setPadding(0,0,0,dp(16))
        })
        fun action(id:Int, primary:Boolean, run:()->Unit) {
            content.addView(Button(activity).apply {
                tag=id;text=text(id);isAllCaps=false;textSize=14f
                minHeight=dp(48);setSingleLine(false);ellipsize=null
                setPadding(dp(12),dp(8),dp(12),dp(8))
                setTextColor(if(primary)FilmroomPalette.surface else FilmroomPalette.ink)
                background=GradientDrawable().apply {
                    cornerRadius=dp(12).toFloat()
                    setColor(if(primary)FilmroomPalette.accent else Color.TRANSPARENT)
                }
                setOnClickListener {
                    if(phase!=Phase.RETURN_CONFIRM)return@setOnClickListener
                    dialog?.dismiss();dialog=null;run()
                }
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
        }
        action(R.string.export_background_continue,true) { continueOnce() }
        action(R.string.export_background_cancel,false) { cancel() }
        show(AlertDialog.Builder(activity).setTitle(text(R.string.export_background_return_title))
            .setView(ScrollView(activity).apply { addView(content) }))
    }

    companion object {
        internal const val STATE_KEY="exportBackgroundGuidancePhase"
        internal const val IGNORE_KEY="permanentlyIgnore"
    }
}
