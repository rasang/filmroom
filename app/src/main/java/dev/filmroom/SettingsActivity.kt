package dev.filmroom

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.filmroom.diagnostics.Diagnostics
import dev.filmroom.ui.DiagnosticShareIntents
import kotlinx.coroutines.*

/** Settings has no editor ViewModel: opening or leaving cannot replace editor state or cancel exports. */
class SettingsActivity : ComponentActivity() {
    private var renderedLanguage="zh"
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(dev.filmroom.i18n.L10n.wrap(base))}

    private val ink=Color.rgb(244,241,233)
    private val muted=Color.rgb(184,187,178)
    private val accent=Color.rgb(229,163,93)
    private lateinit var radios:RadioGroup
    private lateinit var custom:RadioButton
    private lateinit var field:EditText
    private lateinit var share:Button
    private val values=listOf(5,15,30)
    private var ids=emptyList<Int>()
    private var shareJob:Job?=null
    private var pending:AlertDialog?=null
    private var logNotesExpanded=false
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float,color:Int=ink)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);setPadding(0,dp(8),0,dp(8))
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        renderedLanguage=dev.filmroom.i18n.L10n.language(this)
        logNotesExpanded=savedInstanceState?.getBoolean("logNotesExpanded") ?: false
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(17,20,19))}
        root.setOnApplyWindowInsetsListener{v,i->v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        val header=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(8),dp(16),dp(8))}
        header.addView(ImageButton(this).apply {
            contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_16b38790d59b);setImageResource(R.drawable.ic_back);imageTintList=ColorStateList.valueOf(ink)
            setBackgroundColor(Color.TRANSPARENT);setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener{finish()}
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df3d58c7d84b),22f).apply {setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(header)
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(16),dp(24));tag="settings-content"}
        fun section(title:Int,key:String):LinearLayout {
            content.addView(label(getString(title),13f,muted).apply{
                tag="settings-heading:$key";setTypeface(null,Typeface.BOLD);setPadding(dp(4),0,dp(4),dp(8))
                isAccessibilityHeading=true
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=if(content.childCount==0)dp(4) else dp(24)})
            return LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;tag="settings-section:$key";setPadding(dp(16),dp(8),dp(16),dp(8))
                background=GradientDrawable().apply{setColor(Color.rgb(25,30,27));cornerRadius=dp(16).toFloat()}
                content.addView(this,LinearLayout.LayoutParams(-1,-2))
            }
        }
        fun divider(parent:LinearLayout){parent.addView(View(this).apply{
            tag="settings-divider";setBackgroundColor(Color.rgb(53,59,54));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply{topMargin=dp(10);bottomMargin=dp(10)})}
        val languageSection=section(R.string.settings_group_language,"language")
        val languageRow=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(6),0,dp(12));tag="language-setting"
            addView(label(dev.filmroom.i18n.L10n.multilingualTitle,16f).apply{setTypeface(null,Typeface.BOLD)})
            addView(label(dev.filmroom.i18n.L10n.multilingualDescription,12f,muted))
            addView(label(dev.filmroom.i18n.L10n.nativeNames[dev.filmroom.i18n.L10n.supported.indexOf(renderedLanguage)],14f,accent))
            isClickable=true;isFocusable=true;contentDescription=dev.filmroom.i18n.L10n.multilingualTitle
            setOnClickListener { if(pending?.isShowing!=true) pending=dev.filmroom.i18n.LanguagePicker.show(this@SettingsActivity,false){pending=null;recreate()} }
        }
        languageSection.addView(languageRow)
        val preferences=dev.filmroom.model.EditorPreferences(this)
        val photoSection=section(R.string.settings_group_photos,"photos")
        fun preferenceRow(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit){
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP;setPadding(0,dp(8),0,dp(8));tag="settings-preference-row"}
            val labels=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
                addView(label(title,16f).apply{setTypeface(null,Typeface.BOLD);setPadding(0,0,dp(12),dp(6));tag="settings-preference-title"})
                addView(label(description,13f,muted).apply{setPadding(0,0,dp(12),0);setLineSpacing(dp(3).toFloat(),1f);tag="settings-preference-description"})
            }
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            val toggle=Switch(this).apply{contentDescription=title;isChecked=checked;minimumHeight=dp(48);minimumWidth=dp(48);showText=false;tag="settings-preference-switch"
                thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted))
                trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(104,76,46),Color.rgb(63,70,64)))
                setOnCheckedChangeListener{_,value->onChange(value)}
            }
            labels.setOnClickListener{toggle.toggle()};row.addView(toggle,LinearLayout.LayoutParams(-2,dp(48)))
            photoSection.addView(row)
        }
        preferenceRow(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2413c386102e),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_712623100497),preferences.resetOnImport){preferences.resetOnImport=it}
        divider(photoSection)
        preferenceRow(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7daa923cc7e5),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f08fa1da11e),preferences.restoreLastPhoto){preferences.restoreLastPhoto=it}
        divider(photoSection)
        val rawPreferences=dev.filmroom.model.RawBatchPreferences(this)
        val rawValue=label(getString(rawPreferences.mode.titleRes),14f,accent).apply{tag="raw-batch-value"}
        val rawRow=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(8));minimumHeight=dp(48)
            tag="raw-batch-setting";isClickable=true;isFocusable=true
            addView(label(getString(R.string.raw_batch_title),16f).apply{setTypeface(null,Typeface.BOLD)})
            addView(label(getString(R.string.raw_batch_description),13f,muted).apply{setLineSpacing(dp(3).toFloat(),1f)})
            addView(rawValue)
        }
        fun updateRawValue(){
            rawValue.text=getString(rawPreferences.mode.titleRes)
            rawRow.contentDescription=getString(R.string.raw_batch_title)+" · "+rawValue.text
        }
        updateRawValue()
        rawRow.setOnClickListener {
            if(pending?.isShowing!=true){
                val modes=dev.filmroom.model.RawBatchMode.entries
                var selected=modes.indexOf(rawPreferences.mode)
                val choices=object:ArrayAdapter<String>(this,android.R.layout.select_dialog_singlechoice,modes.map{getString(it.titleRes)}){
                    override fun getView(position:Int,recycled:View?,parent:android.view.ViewGroup):View =
                        (super.getView(position,recycled,parent) as CheckedTextView).apply{
                            setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null
                            layoutParams=AbsListView.LayoutParams(-1,-2);minHeight=dp(48)
                        }
                }
                val dialog=AlertDialog.Builder(this)
                    .setTitle(R.string.raw_batch_title)
                    .setSingleChoiceItems(choices,selected){_,which->selected=which}
                    .setPositiveButton(R.string.raw_batch_apply){_,_->rawPreferences.mode=modes[selected];updateRawValue()}
                    .setNegativeButton(R.string.text_2cd0f3be8738,null)
                    .create()
                dialog.setOnDismissListener{if(pending===dialog)pending=null}
                pending=dialog;dialog.show()
            }
        }
        photoSection.addView(rawRow)
        val diagnosticSection=section(R.string.settings_group_diagnostics,"diagnostics")
        diagnosticSection.addView(label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84a61a56ecc6),16f).apply{setTypeface(null,Typeface.BOLD)})
        radios=RadioGroup(this)
        ids=values.map { minutes -> RadioButton(this).apply {
            id=View.generateViewId();text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b46da08445e2, minutes);textSize=16f;minHeight=dp(48);setTextColor(ink)
            buttonTintList=ColorStateList.valueOf(accent)
        }.also(radios::addView).id }
        custom=RadioButton(this).apply {id=View.generateViewId();text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_45809131f9a2);textSize=16f;minHeight=dp(48);setTextColor(ink);buttonTintList=ColorStateList.valueOf(accent)}
        radios.addView(custom);diagnosticSection.addView(radios)
        field=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_NUMBER;hint="1–1440";contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_75a879d2307c)
            setTextColor(ink);setHintTextColor(muted);minHeight=dp(48);isSaveEnabled=false
        }
        diagnosticSection.addView(field)
        val saved=getSharedPreferences("diagnostic-ui",0).getInt("minutes",15).coerceIn(1,1440)
        val selected=savedInstanceState?.getInt("selection") ?: values.indexOf(saved)
        field.setText(savedInstanceState?.getString("custom") ?: if(saved !in values)saved.toString() else "")
        radios.check(if(selected in ids.indices)ids[selected] else custom.id)
        fun updateEnabled(){field.isEnabled=radios.checkedRadioButtonId==custom.id;field.alpha=if(field.isEnabled)1f else .45f}
        radios.setOnCheckedChangeListener{_,_->updateEnabled()};updateEnabled()
        divider(diagnosticSection)
        diagnosticSection.addView(label(getString(R.string.settings_log_privacy_summary),13f,muted).apply{tag="settings-log-summary";setLineSpacing(dp(3).toFloat(),1f)})
        val notes=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="settings-log-notes"
            addView(label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1fdccd5c32ea),13f,muted).apply{setLineSpacing(dp(3).toFloat(),1f)})
            addView(label(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_546989732ef0),13f,muted).apply{setLineSpacing(dp(3).toFloat(),1f)})
        }
        val notesToggle=Button(this).apply{
            tag="settings-log-notes-toggle";isAllCaps=false;textSize=14f;setTextColor(muted);gravity=Gravity.START or Gravity.CENTER_VERTICAL
            minHeight=dp(48);minimumHeight=dp(48);setPadding(0,0,0,0);setBackgroundColor(Color.TRANSPARENT)
        }
        fun updateNotes(){
            notes.visibility=if(logNotesExpanded)View.VISIBLE else View.GONE
            notesToggle.text=getString(if(logNotesExpanded)R.string.settings_hide_log_notes else R.string.settings_show_log_notes)
        }
        notesToggle.setOnClickListener{logNotesExpanded=!logNotesExpanded;updateNotes()};updateNotes()
        diagnosticSection.addView(notesToggle,LinearLayout.LayoutParams(-1,-2));diagnosticSection.addView(notes)
        share=Button(this).apply {
            text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d09e99fe27d8);isAllCaps=false;textSize=16f;minHeight=dp(48);setTextColor(Color.rgb(32,21,12))
            background=GradientDrawable().apply{setColor(accent);cornerRadius=dp(12).toFloat()}
            setOnClickListener {
                val index=ids.indexOf(radios.checkedRadioButtonId)
                val minutes=if(index>=0)values[index] else field.text.toString().toIntOrNull()
                if(minutes==null || minutes !in 1..1440){field.error=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_dcc63884dad1);return@setOnClickListener}
                getSharedPreferences("diagnostic-ui",0).edit().putInt("minutes",minutes).apply()
                shareDiagnostics(minutes)
            }
        }
        diagnosticSection.addView(share,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(8)})
        root.addView(ScrollView(this).apply{isFillViewport=true;tag="settings-scroll";addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }
    override fun onSaveInstanceState(out:Bundle){out.putBoolean("logNotesExpanded",logNotesExpanded);out.putInt("selection",ids.indexOf(radios.checkedRadioButtonId));out.putString("custom",field.text.toString());super.onSaveInstanceState(out)}
    private fun shareDiagnostics(minutes:Int){
        if(shareJob?.isCompleted==false)return
        share.isEnabled=false
        val dialog=AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f0922cbcaaa2)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_13e5bfcd9568, minutes))
            .setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738)){_,_->shareJob?.cancel()}.create()
        dialog.setOnCancelListener{shareJob?.cancel()};pending=dialog;dialog.show()
        shareJob=lifecycleScope.launch {
            try {
                val snapshot=Diagnostics.prepareShare(minutes);ensureActive()
                dialog.dismiss();pending=null
                startActivity(Intent.createChooser(DiagnosticShareIntents.create(this@SettingsActivity,snapshot.file,minutes),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0039fff3b, minutes)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch(c:CancellationException){throw c}
            catch(_:Exception){if(!isFinishing&&!isDestroyed)AlertDialog.Builder(this@SettingsActivity).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b6ad51ea5ed7)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f2df43642219)).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_de32e20193ad),null).show()}
            finally{dialog.dismiss();if(pending===dialog)pending=null;share.isEnabled=true}
        }
    }
    override fun onDestroy(){pending?.dismiss();pending=null;super.onDestroy()}
}
