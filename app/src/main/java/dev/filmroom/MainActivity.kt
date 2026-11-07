package dev.filmroom

import dev.filmroom.ui.FilmroomPalette

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.model.CropMode
import dev.filmroom.ui.ProcessingVisualStage
import dev.filmroom.ui.ProcessingAnimationView
import dev.filmroom.ui.RulerSeekBar
import dev.filmroom.ui.WrappingActionLayout
import dev.filmroom.ui.PhotoView
import dev.filmroom.ui.DiagnosticShareIntents
import dev.filmroom.diagnostics.Diagnostics
import dev.filmroom.ui.ExportQueueEntry
import dev.filmroom.ui.ExportQueueSummary
import dev.filmroom.export.ExportQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.roundToInt

class MainActivity:ComponentActivity() {
    private var renderedLanguage="zh"
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(dev.filmroom.i18n.L10n.wrap(base))}

    private val model:EditorViewModel by viewModels()
    private val exportBackgroundGuidance=dev.filmroom.ui.ExportBackgroundGuidance(this,
        proceed={if(model.state.value.canExport)exportNotifications.offer{exportOptionsDialog()} else exportBackgroundGuidanceFinished()},
        verifiedProceed={exportOptionsDialog()})
    private fun exportBackgroundGuidanceFinished(){exportBackgroundGuidance.finish()}
    private val exportNotifications=dev.filmroom.ui.ExportNotificationPermission(this)
    private val ink=FilmroomPalette.ink; private val muted=FilmroomPalette.muted
    private val accent=FilmroomPalette.accent;private val surface=FilmroomPalette.surface;private val raised=FilmroomPalette.raised
    private lateinit var root:LinearLayout;private lateinit var photo:PhotoView;private lateinit var tools:LinearLayout
    private lateinit var categoryBar:LinearLayout;private lateinit var badge:TextView
    private lateinit var histogram:dev.filmroom.ui.PreviewHistogramView
    private var lastToastMessage:String?=null
    private var lastToastAttempt:Long=0
    private var lastEngineNotice:String?=null
    private var recoveryDialog:AlertDialog?=null
    private var presetDialog:AlertDialog?=null
    private val savedPresetDialogs: dev.filmroom.presets.SavedPresetDialogs by lazy { dev.filmroom.presets.SavedPresetDialogs(this,{model.state.value},model::applySavedPreset,{it.showReadable()},{recipeScanner.launch(Intent(this,dev.filmroom.presets.RecipeScannerActivity::class.java))}) }
    private var pendingSavedPreset:Bundle?=null
    private lateinit var recipeButton:Button
    private var languageDialog:AlertDialog?=null
    private lateinit var resetPresetButton:Button
    private lateinit var queueEntry:ExportQueueEntry
    private lateinit var importButton:Button;private lateinit var exportButton:Button;private lateinit var undo:Button;private lateinit var redo:Button;private lateinit var compare:Button
    private lateinit var compositionButton:Button;private lateinit var infoButton:Button;private lateinit var welcomeScroll:ScrollView;private lateinit var welcome:LinearLayout;private lateinit var loading:LinearLayout;private lateinit var loadingText:TextView
    private data class Stock(val id:String,val name:String,val paper:String,val kind:String="negative",val route:String="print")
    private val catalog:List<Stock> by lazy{
        val rows=org.json.JSONObject(assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        (0 until rows.length()).map{index->rows.getJSONObject(index).let{Stock(it.getString("id"),it.getString("name"),it.getString("defaultPaper"),it.getString("kind"),it.getString("defaultRoute"))}}
    }
    private val films get()=listOf(Stock("none",getString(R.string.film_none),"none","none","print"))+catalog.filter{it.kind=="negative"||it.kind=="reversal"}
    private val papers get()=catalog.filter{it.kind=="paper"||it.kind=="cine_print"}.map{it.id to it.name}
    private var group="film";private var selected="brightness";private var dragging=false
    private val landscapeEditor get()=dev.filmroom.ui.EditorLayoutPolicy.usesTwoPanes(resources.configuration.screenWidthDp,resources.configuration.screenHeightDp,isInMultiWindowMode)
    private var renderedTwoPane=false
    private var landscapeScroll:ScrollView?=null
    private var landscapeBuiltGroup:String?=null
    private val landscapePositions=mutableMapOf<String,Int>()
    private data class ParameterSection(val view:LinearLayout,var key:List<Any?>)
    private val landscapeSections=linkedMapOf<String,ParameterSection>()
    private fun rememberLandscapeScroll(){landscapeBuiltGroup?.let{key->landscapeScroll?.let{landscapePositions[key]=it.scrollY}}}

    private data class StripPosition(val x:Int,val width:Int,val anchor:String?,val offset:Int,val selection:String?)
    private data class StripBinding(val view:HorizontalScrollView,val row:LinearLayout,val key:String,val selection:String,var ready:Boolean=false)
    private val stripPositions=mutableMapOf<String,StripPosition>()
    private val visibleStrips=mutableListOf<StripBinding>()
    private var exportProgressDialog:AlertDialog?=null
    private var exportProgressText:TextView?=null
    private var exportDetailsText:TextView?=null
    private lateinit var toolHost:FrameLayout
    private var expandedTools:AlertDialog?=null
    private var expandedToolId:String?=null
    private var exportAnimation:ProcessingAnimationView?=null
    private var exportCancelButton:Button?=null
    private val invalidateDetail:()->Unit={if(::photo.isInitialized)photo.releaseDetail()}
    private lateinit var detailLoading:ProgressBar
    private var detailFeedback=dev.filmroom.ui.DetailFeedback()
    private var detailForeground=false
    private var restoredViewer:FloatArray?=null;private var restoredZoom:FloatArray?=null;private var restoredSource:String?=null
    private var compositionReturnTool:String?=null;private var compositionReturnGroup:String?=null
    private var straightenDial:dev.filmroom.ui.StraightenDialView?=null
    private var straightenReset:Button?=null
    private var builtCompositionEditing=false;private var builtCompositionAspect:Double?=null;private var boundCompositionEditing:Boolean?=null;private var boundCompare:Boolean?=null;private var builtParameters:EditorState?=null;private var shownSource:String?=null;private var lastBitmap:android.graphics.Bitmap?=null
    private val recipeImport: dev.filmroom.presets.RecipeImportDialog by lazy { dev.filmroom.presets.RecipeImportDialog(this,{it.showReadable()},{savedPresetDialogs.open()}) }
    private val recipeScanner: androidx.activity.result.ActivityResultLauncher<Intent> =registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->recipeImport.accept(result.resultCode,result.data)}
    private val picker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)model.importPhoto(uri,dev.filmroom.diagnostics.ImportOrigin.PHOTO_PICKER)}
    private val filePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.importPhoto(uri,dev.filmroom.diagnostics.ImportOrigin.FILES_PICKER)}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).roundToInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        recipeImport // Register restoration before STARTED; scanner results return to this editor.
        renderedLanguage=dev.filmroom.i18n.L10n.language(this)
        compositionReturnTool=savedInstanceState?.getString("compositionReturnTool");compositionReturnGroup=savedInstanceState?.getString("compositionReturnGroup")
        group=savedInstanceState?.getString("group") ?: "film";selected=savedInstanceState?.getString("selected") ?: "brightness"
        pendingSavedPreset=savedInstanceState?.getBundle("savedPresetDialog")
        pendingImprintDraft=savedInstanceState?.getBundle("imprintDraft");pendingImprintSource=savedInstanceState?.getString("imprintSource")
        restoredViewer=savedInstanceState?.getFloatArray("viewerNormalized");restoredZoom=savedInstanceState?.getFloatArray("zoom");restoredSource=savedInstanceState?.getString("zoomSource")
        lastToastAttempt=savedInstanceState?.getLong("lastToastAttempt") ?: 0
        lastToastMessage=savedInstanceState?.getString("lastToastMessage");lastEngineNotice=savedInstanceState?.getString("lastEngineNotice")
        savedInstanceState?.getBundle("stripPositions")?.let{saved->for(key in saved.keySet())saved.getBundle(key)?.let{
            stripPositions[key]=StripPosition(it.getInt("x"),it.getInt("width"),it.getString("anchor"),it.getInt("offset"),it.getString("selection"))
        }}
        savedInstanceState?.getBundle("landscapePositions")?.let{saved->for(key in saved.keySet())landscapePositions[key]=saved.getInt(key)}
        detailFeedback=dev.filmroom.ui.DetailFeedback(savedInstanceState?.getStringArrayList("detailToastKeys").orEmpty())
        buildScreen()
        exportBackgroundGuidance.restore(savedInstanceState)
        if(!dev.filmroom.i18n.L10n.hasChosen(this)) languageDialog=dev.filmroom.i18n.LanguagePicker.show(this,true, savedInstanceState?.takeIf { it.containsKey("languageDraft") }?.getInt("languageDraft")){
            languageDialog=null
            if(renderedLanguage!=dev.filmroom.i18n.L10n.language(this))recreate()
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){
            if(!model.state.value.startupReady){isEnabled=false;onBackPressedDispatcher.onBackPressed();return}
            if(model.state.value.compositionEditing){photo.discardCompositionGesture();model.cancelComposition();return}
            if(model.state.value.busy!=null) AlertDialog.Builder(this@MainActivity).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_dde174726d3f)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_aa7d5766b326)).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7084fcb218d)){_,_->model.cancel()}.setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c9691192f1b),null).showReadable()
            else { isEnabled=false;onBackPressedDispatcher.onBackPressed() }
        }})
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{bind(it)}} }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                queueEntry.setForeground(true)
                try {
                    val queue = withContext(Dispatchers.IO) { ExportQueue.get(applicationContext) }
                    queue.jobs.map(ExportQueueSummary::from).distinctUntilChanged().collect { queueEntry.bind(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { queueEntry.bind(null) }
                finally { queueEntry.setForeground(false) }
            }
        }
    }
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW){dev.filmroom.border.BorderCatalog.clearCache();if(::photo.isInitialized)photo.releaseDetail()}}
    override fun onStart(){super.onStart();if(::photo.isInitialized)photo.setForeground(true);if(::histogram.isInitialized)histogram.setForeground(true)}
    override fun onResume(){super.onResume();model.setPrewarmForeground(true);if(renderedLanguage!=dev.filmroom.i18n.L10n.language(this)){recreate();return};settingsOpening=false;exportBackgroundGuidance.onResume();dev.filmroom.ui.EditorSystemBars.apply(this);detailForeground=true;if(::photo.isInitialized)updateDetailStatus(photo.detailStatus)}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)dev.filmroom.ui.EditorSystemBars.apply(this)}
    override fun onMultiWindowModeChanged(inMultiWindowMode:Boolean,newConfig:android.content.res.Configuration){super.onMultiWindowModeChanged(inMultiWindowMode,newConfig);dev.filmroom.ui.EditorSystemBars.apply(this);if(::photo.isInitialized && renderedTwoPane!=landscapeEditor){recreate();return};androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)}
    override fun onPause(){model.setPrewarmForeground(false);if(::photo.isInitialized)photo.finishCompositionGesture();detailForeground=false;if(::detailLoading.isInitialized)detailLoading.visibility=View.INVISIBLE;super.onPause()}
    override fun onDestroy(){imprintDialog?.dismiss();imprintDialog=null;exportBackgroundGuidance.close();languageDialog?.dismiss();languageDialog=null;detailForeground=false;if(model.detailInvalidator===invalidateDetail)model.detailInvalidator=null;if(::photo.isInitialized)photo.close();super.onDestroy()}
    override fun onStop(){
        if(::photo.isInitialized)photo.setForeground(false)
        if(::histogram.isInitialized)histogram.setForeground(false)
        recoveryDialog?.dismiss();recoveryDialog=null
        presetDialog?.dismiss();presetDialog=null
        if(isChangingConfigurations)pendingSavedPreset=savedPresetDialogs.capture() ?: pendingSavedPreset
        savedPresetDialogs.close()
        expandedTools?.dismiss()
        dismissExportProgress()
        if(!isChangingConfigurations)model.processingScreenLeft()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onSaveInstanceState(outState:Bundle){
        (savedPresetDialogs.capture() ?: pendingSavedPreset)?.let{outState.putBundle("savedPresetDialog",it)}
        imprintDialog?.takeIf{it.isShowing}?.let{outState.putBundle("imprintDraft",dev.filmroom.imprint.ImprintDialog.capture(it));outState.putString("imprintSource",imprintDialogSource)}
        exportBackgroundGuidance.save(outState)
        languageDialog?.takeIf{it.isShowing}?.let { outState.putInt("languageDraft",it.listView.checkedItemPosition) }
        photo.finishCompositionGesture();outState.putFloatArray("viewerNormalized",photo.viewerState())
        outState.putStringArrayList("detailToastKeys",detailFeedback.savedKeys())
        rememberLandscapeScroll()
        outState.putBundle("landscapePositions",Bundle().apply{landscapePositions.forEach{(key,value)->putInt(key,value)}})
        visibleStrips.forEach(::rememberStrip)
        outState.putBundle("stripPositions",Bundle().apply{for((key,p) in stripPositions)putBundle(key,Bundle().apply{putInt("x",p.x);putInt("width",p.width);putString("anchor",p.anchor);putInt("offset",p.offset);putString("selection",p.selection)})})
        outState.putString("lastToastMessage",lastToastMessage);outState.putLong("lastToastAttempt",lastToastAttempt);outState.putString("lastEngineNotice",lastEngineNotice);outState.putString("compositionReturnTool",compositionReturnTool);outState.putString("compositionReturnGroup",compositionReturnGroup);outState.putString("group",group);outState.putString("selected",selected);outState.putFloatArray("zoom",photo.transform());outState.putString("zoomSource",model.state.value.photo?.cacheKey);super.onSaveInstanceState(outState)}
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL;includeFontPadding=true}
    private fun shape(color:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(12).toFloat();if(stroke!=null)setStroke(dp(1),stroke)}
    private fun button(label:String,icon:Int?=null,primary:Boolean=false,action:()->Unit)=Button(this).apply{
        text=label;textSize=14f;isAllCaps=false;minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(48);minimumWidth=dp(48);setPadding(dp(12),dp(10),dp(12),dp(10));ellipsize=null;maxLines=Int.MAX_VALUE;setHorizontallyScrolling(false)
        setTextColor(if(primary)FilmroomPalette.onAccent else ink);background=shape(if(primary)accent else raised)
        if(icon!=null){val d=getDrawable(icon)!!.mutate();d.setTint(if(primary)FilmroomPalette.onAccent else ink);d.setBounds(0,0,dp(24),dp(24));setCompoundDrawables(d,null,null,null);compoundDrawablePadding=dp(6)}
        setOnClickListener{action()}
    }
    private fun iconButton(label:String,icon:Int,action:()->Unit)=button("",icon,action=action).apply{
        contentDescription=label;tooltipText=label;setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(Color.TRANSPARENT)
    }
    private fun toolButton(label:String,icon:Int,chosen:Boolean,action:()->Unit)=button(label,action=action).apply{
        textSize=12f;maxLines=1;setHorizontallyScrolling(false);setPadding(dp(10),dp(8),dp(10),dp(8));minimumWidth=dp(64)
        if(!landscapeEditor){
            val drawable=getDrawable(icon)!!.mutate();drawable.setTint(if(chosen)accent else ink);drawable.setBounds(0,0,dp(24),dp(24))
            setCompoundDrawables(null,drawable,null,null);compoundDrawablePadding=dp(6)
        }
        setTextColor(if(chosen)accent else ink);background=shape(if(chosen)raised else Color.TRANSPARENT)
        isSelected=chosen
    }
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun buildScreen(){
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(FilmroomPalette.background)}
        dev.filmroom.ui.EditorSystemBars.attach(this,root)
        setContentView(root)
        // Keep seven semantic touch targets; use compact visual glyphs and spend spare
        // space at group boundaries, rather than squeezing seven painted tiles together.
        fun toolbarGlyph(view:Button,label:String,icon:Int) {
            view.contentDescription=label;view.tooltipText=label
            if(view.text.isNotEmpty())view.text=""
            if(view.tag!=icon){
                view.tag=icon
                view.setCompoundDrawables(getDrawable(icon)!!.mutate().apply{
                    setTint(if(view===exportButton)FilmroomPalette.onAccent else ink)
                    setBounds(0,0,dp(22),dp(22))
                },null,null,null)
            }
            view.compoundDrawablePadding=0;view.setSingleLine(true);view.setHorizontallyScrolling(false)
        }
        var paintedTarget=0
        val top=object:LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                val width=View.MeasureSpec.getSize(widthMeasureSpec)
                // Do not switch to 48dp until there is also a useful whitespace budget.
                val target=if(width>=dp(384))dp(48) else dp(44)
                val preferredInset=dp(if(width>=dp(600))16 else if(width>=dp(384))12 else if(width>=dp(344))8 else 4)
                val side=minOf(preferredInset,((width-target*7)/2).coerceAtLeast(0))
                setPadding(side,dp(8),side,dp(8))
                val spare=(width-side*2-target*7).coerceAtLeast(0)
                val inner=minOf(dp(4),spare/12)
                val group=minOf(dp(16),(spare-inner*3)/3)
                // import | undo redo reset | export queue | settings
                val gaps=intArrayOf(group,inner,inner,group,inner,group)
                // On landscape this becomes an elastic central space, keeping the
                // import/edit group left and export/settings group right.
                gaps[3]+=spare-gaps.sum()
                for(index in 1 until childCount){
                    (getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply{
                        marginStart=gaps[if(index==5)5 else index-1];resolveLayoutDirection(layoutDirection)
                    }
                }
                (queueEntry.layoutParams as LinearLayout.LayoutParams).apply{marginStart=gaps[4];resolveLayoutDirection(layoutDirection)}
                toolbarGlyph(importButton,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_576d81bb0631),R.drawable.ic_import)
                toolbarGlyph(exportButton,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_76420433f22d),R.drawable.ic_export)
                toolbarGlyph(undo,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_926a50b98ece),R.drawable.ic_undo)
                toolbarGlyph(redo,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_03717b6f1070),R.drawable.ic_redo)
                toolbarGlyph(resetPresetButton,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ae384296a4ac),R.drawable.ic_restore_preset)
                val settings=getChildAt(childCount-1) as Button
                toolbarGlyph(settings,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df3d58c7d84b),R.drawable.ic_settings)
                listOf(importButton,undo,redo,resetPresetButton,exportButton,queueEntry,settings).forEach{view->
                    view.minimumWidth=target;view.minWidth=target
                    val iconInset=(target-dp(22))/2
                    view.setPadding(iconInset,dp(8),iconInset,dp(8))
                    view.layoutParams.width=target;view.layoutParams.height=dp(48)
                }
                if(paintedTarget!=target){
                    paintedTarget=target
                    val inset=(target-dp(36))/2
                    exportButton.background=android.graphics.drawable.InsetDrawable(shape(accent),inset,dp(6),inset,dp(6))
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        importButton=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_576d81bb0631)){importDialog()}.apply{setPadding(dp(10),dp(8),dp(10),dp(8))}
        undo=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_926a50b98ece),R.drawable.ic_undo){photo.finishCompositionGesture();model.undo()}.apply{compoundDrawablePadding=0}
        redo=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_03717b6f1070),R.drawable.ic_redo){photo.finishCompositionGesture();model.redo()}.apply{compoundDrawablePadding=0}
        resetPresetButton=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ae384296a4ac),R.drawable.ic_restore_preset){confirmPresetReset()}.apply{compoundDrawablePadding=0}
        exportButton=button("",R.drawable.ic_export,true){photo.finishCompositionGesture();exportDialog()}.apply{compoundDrawablePadding=0;setPadding(dp(12),dp(8),dp(12),dp(8));contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_76420433f22d);tooltipText=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_76420433f22d)}
        importButton.background=shape(Color.TRANSPARENT)
        queueEntry=ExportQueueEntry(this).apply {
            background=shape(Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@MainActivity,ExportQueueActivity::class.java)) }
        }
        // Export and its compact queue entry are immediate neighbours on the same row.
        val exportActions=row().apply {
            addView(exportButton)
            addView(queueEntry,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(12) })
        }
        top.addView(importButton)
        listOf(undo,redo,resetPresetButton,exportActions,iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df3d58c7d84b),R.drawable.ic_settings){diagnosticsDialog()}.apply{compoundDrawablePadding=0}).forEach {
            top.addView(it,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(12)})
        }
        root.addView(top,LinearLayout.LayoutParams(-1,-2))
        val wideLayout=landscapeEditor
        renderedTwoPane=wideLayout
        val body=LinearLayout(this).apply{orientation=if(wideLayout)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL}
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        val imageColumn=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(imageColumn,if(body.orientation==LinearLayout.HORIZONTAL)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        val frame=dev.filmroom.ui.PhotoPreviewFrame(this);imageColumn.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        photo=PhotoView(this);model.detailInvalidator=invalidateDetail;if(model.localDetailEnabled)photo.configureDetail(model.detailProviderFactory());frame.addView(photo,FrameLayout.LayoutParams(-1,-1))
        welcome=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(if(wideLayout)20 else 28),dp(if(wideLayout)8 else 12),dp(if(wideLayout)20 else 28),dp(if(wideLayout)8 else 12))}
        welcome.addView(ImageView(this).apply{setImageResource(R.drawable.welcome_cplift)},LinearLayout.LayoutParams(dp(if(wideLayout)40 else 76),dp(if(wideLayout)40 else 76)))
        welcome.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1f14f4b50d0b),if(wideLayout)18f else 25f).apply{gravity=Gravity.CENTER;setPadding(0,dp(if(wideLayout)6 else 20),0,dp(if(wideLayout)4 else 10))})
        welcome.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_95c277eafaea),if(wideLayout)12f else 14f,muted).apply{gravity=Gravity.CENTER})
        welcome.addView(button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7fc4b2dbd50),R.drawable.ic_import,true){importDialog()}.apply{minimumHeight=dp(48);minHeight=dp(48)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(if(wideLayout)8 else 24)})
        welcomeScroll=ScrollView(this).apply{isFillViewport=true;addView(welcome,FrameLayout.LayoutParams(-1,-2))}
        frame.addView(welcomeScroll,FrameLayout.LayoutParams(-1,-1))
        val readouts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;isClickable=false;isFocusable=false}
        frame.addView(readouts,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{leftMargin=dp(12);rightMargin=dp(48);topMargin=dp(12)})
        histogram=dev.filmroom.ui.PreviewHistogramView(this)
        readouts.addView(histogram,LinearLayout.LayoutParams(dp(100),dp(44)))
        badge=text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c37117eec876),12f).apply{setPadding(dp(10),dp(5),dp(10),dp(5));background=shape(surface)}
        readouts.addView(badge,LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(6)})
        // A fixed, text-free image overlay. Hidden states never resize the photo or tools.
        detailLoading=ProgressBar(this,null,android.R.attr.progressBarStyleSmall).apply{
            isIndeterminate=true;indeterminateDrawable=dev.filmroom.ui.SmallLoadingDrawable(accent,resources.displayMetrics.density)
            minimumWidth=0;minimumHeight=0;visibility=View.INVISIBLE
            contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_002e7216c97a);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable=false;isFocusable=false
        }
        frame.addView(detailLoading,FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP or Gravity.END).apply{rightMargin=dp(12);topMargin=dp(12)})
        photo.onDetailStatusChanged={status->updateDetailStatus(status)}
        loading=row().apply{background=shape(surface);setPadding(dp(12),dp(8),dp(8),dp(8))}
        loading.addView(ProgressBar(this),LinearLayout.LayoutParams(dp(24),dp(24)))
        loadingText=text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1663134e88db),14f).apply{setPadding(dp(10),0,dp(10),0)};loading.addView(loadingText,LinearLayout.LayoutParams(0,-2,1f))
        loading.addView(button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738)){model.cancel()},LinearLayout.LayoutParams(-2,-2))
        frame.addView(loading,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{leftMargin=dp(12);rightMargin=dp(12);bottomMargin=dp(12)})
        compare=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_36c69988127e),R.drawable.ic_compare){photo.finishCompositionGesture();model.compare()}
        frame.addView(compare,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.END).apply{rightMargin=dp(12);bottomMargin=dp(8)})
        recipeButton=iconButton(dev.filmroom.i18n.L10n.text(R.string.saved_preset_import),R.drawable.ic_recipe_bottle){photo.finishCompositionGesture();savedPresetDialogs.open()}.apply{tag="saved-recipes"}
        frame.addView(recipeButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.END).apply{rightMargin=dp(68);bottomMargin=dp(8)})
        // A reusable film-tool action, never an image overlay or a parameter selection.
        compositionButton=toolButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f02806d5dc08),R.drawable.ic_composition,false){
            val current=model.state.value
            val ready=if(current.parameters.filmBorder)current.presentation!=null && current.renderedParameters?.filmBorder==true && current.renderedParameters.filmBorderStage==current.parameters.filmBorderStage && current.presentation.mediumFormat==current.parameters.effectiveMediumFormat && current.presentation.mediumPortrait==current.parameters.effectiveMediumPortrait
                else current.cropPresentation!=null && current.renderedParameters?.filmBorder==false
            if(current.editable && current.rendered!=null && ready && !current.compare && !current.compositionEditing){
                expandedTools?.dismiss()
                compositionReturnTool=selected;compositionReturnGroup=group
                selected="composition";group="film"
                photo.finishCompositionGesture();model.beginComposition()
            }
        }.apply{tag="composition";contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f02806d5dc08)}
        infoButton=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c3154414c11),R.drawable.ic_info){photoInfo()}
        frame.addView(infoButton,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM or Gravity.START).apply{leftMargin=dp(12);bottomMargin=dp(8)})
        frame.avoidControls(photo,infoButton,recipeButton,compare)
        val toolColumn=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(surface);setPadding(dp(12),dp(8),dp(12),dp(8))}
        body.addView(toolColumn,if(wideLayout)LinearLayout.LayoutParams(dp((resources.configuration.screenWidthDp*0.45f).roundToInt().coerceAtLeast(240)),-1)else LinearLayout.LayoutParams(-1,-2))
        // The current tool is measured at its natural height: never a vertically scrolling slot.
        toolHost=FrameLayout(this)
        if(!wideLayout)toolColumn.addView(toolHost,LinearLayout.LayoutParams(-1,-2))
        tools=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(8))}
        val categories=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;background=shape(raised);isFillViewport=true}
        categoryBar=row().apply{setPadding(dp(4),dp(4),dp(4),dp(4))};categories.addView(categoryBar)
        toolColumn.addView(categories,LinearLayout.LayoutParams(-1,-2))
        if(wideLayout){
            landscapeScroll=ScrollView(this).apply{tag="landscape-parameters-scroll";isFillViewport=false;addView(tools)}
            toolColumn.addView(landscapeScroll,LinearLayout.LayoutParams(-1,0,1f))
        }
        buildTools()
    }
    private fun enabled(view:View,value:Boolean){view.isEnabled=value;view.alpha=if(value)1f else 0.38f}
    private fun updateDetailStatus(status:dev.filmroom.roi.DetailStatus){
        if(!::detailLoading.isInitialized)return
        val state=model.state.value
        val key=state.photo?.cacheKey?.takeIf{it==shownSource}
        val scienceReady=if(state.parameters.filmBorder && state.parameters.filmBorderStage==1)state.renderedParameters==state.parameters else state.renderedParameters?.copy(imprint=state.parameters.imprint,filmBorder=state.parameters.filmBorder,negativeWhiteHoles=state.parameters.negativeWhiteHoles,composition=state.parameters.composition,crop=state.parameters.crop,mediumFormat=state.parameters.mediumFormat,mediumPortrait=state.parameters.mediumPortrait)==state.parameters
        val presentationChanged=state.renderedParameters?.let{it.filmBorder!=state.parameters.filmBorder || it.negativeWhiteHoles!=state.parameters.negativeWhiteHoles || it.composition!=state.parameters.composition || it.crop!=state.parameters.crop || it.mediumFormat!=state.parameters.mediumFormat || it.mediumPortrait!=state.parameters.mediumPortrait}==true
        val compositionOnly=state.compositionSource!=null && scienceReady && (state.compositionEditing || presentationChanged)
        val eligible=!(state.parameters.filmBorder && state.parameters.filmBorderStage==1) && model.localDetailEnabled && state.startupReady && state.busy==null && !state.exporting && ((!state.updating && !state.previewRefining) || compositionOnly) && !state.compare && state.rendered!=null && scienceReady
        val wholeFrameRefining=state.parameters.preImagingBorder && state.previewRefining && state.startupReady && state.busy==null && !state.exporting && !state.compare && state.rendered!=null
        detailLoading.visibility=if(detailForeground && (wholeFrameRefining || detailFeedback.showLoading(status,key,eligible,detailForeground)))View.VISIBLE else View.INVISIBLE
        detailFeedback.takeFailureToast(status,key,eligible,detailForeground)?.let{
            Toast.makeText(this,it,Toast.LENGTH_SHORT).show()
        }
    }
    private fun bind(s:EditorUiState){
        if(s.exporting)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hasPhoto=s.photo!=null
        val sourceChanged=hasPhoto && shownSource!=s.photo?.cacheKey
        if(sourceChanged && shownSource!=null){imprintDialog?.dismiss();imprintDialog=null}
        shownSource=s.photo?.cacheKey
        welcomeScroll.visibility=if(hasPhoto)View.GONE else View.VISIBLE
        // The readout and original hint share one passive column, clear of the detail spinner.
        val showSourceBadge=hasPhoto && (s.compare || s.rendered==null)
        badge.visibility=if(showSourceBadge)View.VISIBLE else View.GONE
        badge.importantForAccessibility=if(showSourceBadge)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val shown=if(s.compare)s.original else s.rendered ?: s.original
        histogram.bind(dev.filmroom.ui.PreviewHistogramInput.from(s),s.photo?.cacheKey?.let{"$it:${s.compare || s.rendered==null}"})
        if(lastBitmap!==shown){
            photo.onNextPreviewDraw=null
            if(!s.compare && shown!=null && shown===s.rendered)s.renderedDiagnosticContext?.let { diagnostic ->
                photo.onNextPreviewDraw={drawn->if(drawn===shown)diagnostic.timing?.nextDraw(diagnostic)}
            }
            photo.bitmap=shown;lastBitmap=shown
            if(!s.compare && shown!=null && shown===s.rendered)s.renderedDiagnosticContext?.let(model::previewBound)
        }
        if(!model.localDetailEnabled)photo.disableDetail()
        val scienceReady=if(s.parameters.filmBorder && s.parameters.filmBorderStage==1)s.renderedParameters==s.parameters else s.renderedParameters?.copy(imprint=s.parameters.imprint,filmBorder=s.parameters.filmBorder,negativeWhiteHoles=s.parameters.negativeWhiteHoles,composition=s.parameters.composition,crop=s.parameters.crop,mediumFormat=s.parameters.mediumFormat,mediumPortrait=s.parameters.mediumPortrait)==s.parameters
        val presentationChanged=s.renderedParameters?.let{it.filmBorder!=s.parameters.filmBorder || it.negativeWhiteHoles!=s.parameters.negativeWhiteHoles || it.composition!=s.parameters.composition || it.crop!=s.parameters.crop || it.mediumFormat!=s.parameters.mediumFormat || it.mediumPortrait!=s.parameters.mediumPortrait}==true
        val compositionOnly=s.compositionSource!=null && scienceReady && (s.compositionEditing || presentationChanged)
        // Never enter a new presentation kind through the previous bitmap's gesture adapter.
        val presentationReady=if(s.parameters.filmBorder)s.presentation!=null && s.renderedParameters?.filmBorder==true && s.renderedParameters.filmBorderStage==s.parameters.filmBorderStage && s.presentation.mediumFormat==s.parameters.effectiveMediumFormat && s.presentation.mediumPortrait==s.parameters.effectiveMediumPortrait
            else s.cropPresentation!=null && s.renderedParameters?.filmBorder==false
        photo.bindDetail(s.photo,s.parameters,!(s.parameters.filmBorder && s.parameters.filmBorderStage==1) && model.localDetailEnabled && s.startupReady && s.busy==null && ((!s.updating && !s.previewRefining) || compositionOnly) && !s.exporting && s.rendered!=null && scienceReady,s.compare,presentedBorder=!s.compare && s.rendered!=null && (s.renderedParameters?.filmBorder==true || s.cropPresentation!=null))
        if(sourceChanged){if(restoredSource==s.photo?.cacheKey&&restoredViewer!=null)photo.restoreViewerState(restoredViewer!!)else if(restoredSource==s.photo?.cacheKey&&restoredZoom!=null)photo.restoreTransform(restoredZoom!!)else photo.resetZoom();restoredZoom=null;restoredViewer=null}
        photo.preImagingCompositionPreview=!s.compare && s.renderedParameters?.preImagingBorder==true
        if((s.presentation!=null && s.renderedParameters?.filmBorder==true && s.renderedParameters.filmBorderStage==s.parameters.filmBorderStage && s.presentation.mediumFormat==s.parameters.effectiveMediumFormat && s.presentation.mediumPortrait==s.parameters.effectiveMediumPortrait) || (s.rendered==null && s.parameters.filmBorder))photo.bindComposition(if(s.compare)null else s.presentation,s.compositionSource,s.photo?.let{s.parameters.presentationTransform(it.originalWidth,it.originalHeight)} ?: s.parameters.composition,
            s.compositionEditing && presentationReady && !s.compare,interactiveBorder=s.interactiveBorder){transform,commit->model.composition(transform,commit)}
        else photo.bindCrop(if(s.compare)null else s.cropPresentation,s.compositionSource,s.parameters.crop,
            s.compositionEditing && presentationReady && !s.compare){transform,commit->model.composition(transform,commit)}
        pendingImprintDraft?.takeIf{s.editable}?.let{draft->
            pendingImprintDraft=null
            if(pendingImprintSource==s.photo?.cacheKey)showImprintDialog(draft)
            pendingImprintSource=null
        }
        bindImprintPreview(s)
        updateDetailStatus(photo.detailStatus)
        badge.text=if(s.compare)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1b0acbdaf172)else if(showSourceBadge)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c37117eec876)else ""
        enabled(resetPresetButton,s.editable && !s.compositionEditing)
        enabled(recipeButton,s.editable && !s.compositionEditing)
        pendingSavedPreset?.takeIf{s.editable}?.let{pendingSavedPreset=null;savedPresetDialogs.restore(it)}
        enabled(importButton,s.startupReady&&s.busy==null);enabled(exportButton,s.canExport);enabled(undo,s.editable&&s.canUndo);enabled(redo,s.editable&&s.canRedo);enabled(compare,s.rendered!=null&&s.busy==null)
        if(boundCompare!=s.compare){
            boundCompare=s.compare
            compare.contentDescription=if(s.compare)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a21efd19b7df)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_36c69988127e);compare.isSelected=s.compare;compare.background=shape(if(s.compare)accent else raised)
            compare.compoundDrawables.filterNotNull().forEach{it.setTint(if(s.compare)FilmroomPalette.onAccent else ink)}
        }
        loading.visibility=if(!s.exporting&&(s.busy!=null||s.updating))View.VISIBLE else View.GONE;loadingText.text=s.busyDisplay ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c3c12cabaae9)
        compare.visibility=if(s.photo==null || loading.visibility==View.VISIBLE)View.GONE else View.VISIBLE
        infoButton.visibility=if(s.photo!=null)compare.visibility else View.GONE
        recipeButton.visibility=compare.visibility
        enabled(compositionButton,s.editable && s.rendered!=null && presentationReady && !s.compare)
        if(landscapeEditor)tools.findViewWithTag<View>("crop-adjust")?.let{enabled(it,compositionButton.isEnabled)}
        if(!s.compositionEditing && selected=="composition")restoreCompositionTool()
        if(boundCompositionEditing!=s.compositionEditing){
            boundCompositionEditing=s.compositionEditing
            compositionButton.isSelected=s.compositionEditing
            compositionButton.setTextColor(if(s.compositionEditing)accent else ink)
            compositionButton.background=shape(if(s.compositionEditing)raised else Color.TRANSPARENT)
            compositionButton.compoundDrawables.filterNotNull().forEach{it.setTint(if(s.compositionEditing)accent else ink)}
        }
        updateExportProgress(s)
        showStatus(s)
        // Tool values depend on scientific parameters and border ON/OFF, not the crop or edit mode.
        // Keep existing listeners/views during a gesture; every mutation reads current parameters.
        val toolValues=builtParameters?.let{it.copy(composition=s.parameters.composition,crop=it.crop.copy(transform=s.parameters.crop.transform))}
        if(!dragging && (sourceChanged || toolValues!=s.parameters || builtCompositionEditing!=s.compositionEditing ||
            (s.compositionEditing && builtCompositionAspect!=s.parameters.composition.targetAspect) || tools.tag!=s.editable)){buildTools()}
        val straighten=if(s.parameters.filmBorder)s.parameters.composition.straightenDegrees else s.parameters.crop.transform.straightenDegrees
        straightenDial?.angle=straighten
        straightenReset?.let{enabled(it,s.editable && s.compositionEditing && straighten!=0.0)}
    }
    private fun showStatus(s:EditorUiState){
        if(s.recoveryFailed){
            if(recoveryDialog==null)recoveryDialog=AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_168ac579f2fb))
                .setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_74edb00b2c95))
                .setCancelable(false).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_77b9d0a61b6d)){_,_->recoveryDialog=null;model.retryRecovery()}
                .setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a558cf14eef8)){_,_->finish()}.showReadable()
        }else{recoveryDialog?.dismiss();recoveryDialog=null}
        val message=s.messageDisplay?.takeUnless{it==s.photo?.developmentWarning||s.recoveryFailed}
        if(message!=null&&(message!=lastToastMessage||s.messageAttempt!=lastToastAttempt))Toast.makeText(this,dev.filmroom.i18n.ToastText.forMessage(s.messageUi),Toast.LENGTH_LONG).show()
        lastToastMessage=message;lastToastAttempt=s.messageAttempt
        if(s.engineUnavailableDisplay!=null&&s.engineUnavailableDisplay!=lastEngineNotice)Toast.makeText(this,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.toast_engine),Toast.LENGTH_LONG).show()
        lastEngineNotice=s.engineUnavailableDisplay
    }
    private fun dismissExportProgress(){
        exportProgressDialog?.dismiss();exportProgressDialog=null
        exportProgressText=null;exportDetailsText=null;exportAnimation=null;exportCancelButton=null
    }
    private fun updateExportProgress(s:EditorUiState){
        if(!s.exporting){dismissExportProgress();return}
        if(exportProgressDialog==null){
            val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(16));setBackgroundResource(R.drawable.filmroom_dialog_background)}
            val animation=ProcessingAnimationView(this)
            content.addView(animation,LinearLayout.LayoutParams(-1,dp(148)))
            val progress=text("",18f).apply{gravity=Gravity.CENTER;minLines=2;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;setPadding(0,dp(8),0,dp(16))}
            content.addView(progress,LinearLayout.LayoutParams(-1,-2))
            val details=text("",13f,muted).apply{visibility=View.GONE;setPadding(0,dp(12),0,dp(12))}
            val logs=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb1d19b85634)){diagnosticsDialog()}.apply{visibility=View.GONE}
            val actions=WrappingActionLayout(this,dp(8))
            val cancel=button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7eefaaff791f)){model.cancel()}
            actions.addView(cancel)
            actions.addView(button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)){
                val show=details.visibility!=View.VISIBLE
                details.visibility=if(show)View.VISIBLE else View.GONE
                logs.visibility=details.visibility
                (actions.getChildAt(1) as Button).text=if(show)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0ae7312510f4)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_979a332955c8)
            }.apply{background=shape(Color.TRANSPARENT);setTextColor(muted)})
            content.addView(actions,LinearLayout.LayoutParams(-1,-2))
            content.addView(details,LinearLayout.LayoutParams(-1,-2));content.addView(logs)
            exportAnimation=animation;exportProgressText=progress;exportDetailsText=details;exportCancelButton=cancel
            exportProgressDialog=AlertDialog.Builder(this)
                .setView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(content)}).setCancelable(false).create().apply{
                    setCanceledOnTouchOutside(false)
                    setOnKeyListener{_,key,event->
                        if(key==android.view.KeyEvent.KEYCODE_BACK){
                            if(event.action==android.view.KeyEvent.ACTION_UP&&!model.state.value.cancelling)model.cancel()
                            true
                        }else false
                    }
                    show()
                    window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    window?.setLayout(dp(minOf(resources.configuration.screenWidthDp-32,360)),ViewGroup.LayoutParams.WRAP_CONTENT)
                }
        }
        val stage=s.busyDisplay ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_25e7efea7600)
        val display=s.exportProgress ?: dev.filmroom.engine.ExportDisplayProgress(1)
        exportProgressText?.text=if(s.cancelling)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_35e33ec9481e) else display.compactText
        exportDetailsText?.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_461ec432d86f, stage)+(s.exportSpaceNote?.let{"\n\n$it"} ?: "")
        exportAnimation?.setPhoto(s.rendered ?: s.original)
        exportAnimation?.updateStage(if(s.cancelling)ProcessingVisualStage.CLEANING else ProcessingVisualStage.fromExportStep(display.stage))
        exportCancelButton?.let{enabled(it,!s.cancelling);it.text=if(s.cancelling)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cd33cfaad6ad)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7eefaaff791f)}
    }
    private data class Control(val id:String,val label:String,val icon:Int,val help:String,
        val min:Float=0f,val max:Float=1f,val neutral:Float=0f,
        val read:(EditorState)->Float={0f},val write:(EditorState,Float)->EditorState={p,_->p},
        val active:(EditorState)->Boolean={true},val exactValue:Boolean=false,
        val parameterIds:Set<String> = setOf(id))
    private fun controls():List<Control> = controlsRaw().map(::capabilityControl)
    private fun extraControls():List<Control> = extraControlsRaw().map(::capabilityControl)
    private fun capabilityControl(c:Control)=c.copy(active={p->c.active(p)&&p.controlSupported(c.id)})
    private fun controlsRaw():List<Control> = when(group){
        "adjust"->listOf(
            Control("filmExposure",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_aacf040ce88a),R.drawable.ic_tool_exposure,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7ce0c0cf48da),-4f,4f,0f,{it.filmExposure},{p,v->p.copy(filmExposure=v)}),
            Control("brightness",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8e2f643ad160),R.drawable.ic_brightness,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8dbf138761d8),-2f,2f,0f,{it.brightness},{p,v->p.copy(brightness=v)}),
            Control("contrast",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4cbc753240e5),R.drawable.ic_tool_contrast,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a746a522ab4),0.5f,1.5f,1f,{it.contrast},{p,v->p.copy(contrast=v)},{it.outputRoute=="print"}),
            Control("warmth",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_90f74b4ac6f1),R.drawable.ic_tool_warmth,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4bed17be043d),-1f,1f,0f,{it.warmth},{p,v->p.copy(warmth=v)},{it.outputRoute=="print"}),
            Control("saturation",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6045e74dcfa7),R.drawable.ic_tool_saturation,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fa586db5f990),0f,2f,1f,{it.saturation},{p,v->p.copy(saturation=v)})
        )
        "light"->listOf(
            Control("diffusionFilter",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cda1ea706d23),R.drawable.ic_tool_diffusion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_69c47fd991d5)),
            Control("diffusionStrength",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7351ad023c8d),R.drawable.ic_tool_diffusion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fc5315fbd6fd),0f,2f,0.5f,{it.diffusionStrength},{p,v->p.copy(diffusionStrength=v)},{it.diffusionFilter!="off"}),
            Control("diffusionSize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_dfe1ad62ec51),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_93ee12df430c),0.25f,2f,1f,{it.diffusionSize},{p,v->p.copy(diffusionSize=v)},{it.diffusionFilter!="off"}),
            Control("halation",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e8b4d7c5c3d2),R.drawable.ic_tool_halo,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b89612346534),0f,2f,1f,{it.halation},{p,v->p.copy(halation=v)}),
            Control("halationSize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f08d365f119c),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b5034bff2db9),0.25f,3f,1f,{it.halationSize},{p,v->p.copy(halationSize=v)},{it.halation>0})
        )
        "grain"->listOf(
            Control("grain",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5acbd63667a0),R.drawable.ic_grain,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b7266f68310),0f,2f,1f,{it.grain},{p,v->p.copy(grain=v)}),
            Control("grainSize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_065f01b65731),R.drawable.ic_tool_grain_size,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6c6bd5cb96c0),0.25f,3f,1f,{it.grainSize},{p,v->p.copy(grainSize=v)},{it.grain>0})
        )
        "print"->listOf(
            Control("paper",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_813cd5bf9234),R.drawable.ic_tool_paper,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_14e7fe83afcb)),
            Control("printExposureEv",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_05af46cca01b),R.drawable.ic_tool_exposure,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b2bf5f41cd45),-4f,4f,0f,{it.printExposureEv},{p,v->p.copy(printExposureEv=v)},{it.outputRoute=="print"},true),
            Control("printYellow",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fe7b9f3a996),R.drawable.ic_tool_filter_yellow,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_15206742c3a6),-30f,30f,0f,{it.printYellow},{p,v->p.copy(printYellow=v)},{it.outputRoute=="print"}),
            Control("printMagenta",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f542cc5a3de),R.drawable.ic_tool_filter_magenta,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0e5ad3c12a0e),-30f,30f,0f,{it.printMagenta},{p,v->p.copy(printMagenta=v)},{it.outputRoute=="print"}),
            Control("preflash",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_469f85e3c561),R.drawable.ic_tool_preflash,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7dd11c898748),0f,0.2f,0f,{it.preflash},{p,v->p.copy(preflash=v)},{it.outputRoute=="print"})
        )
        "scan"->listOf(
            Control("scanSharpenAmount",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_23cc75747b5a),R.drawable.ic_tool_sharpen,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_60d828125725),0f,2f,0f,{it.scanSharpenAmount},{p,v->p.copy(scanSharpenAmount=v)}),
            Control("scanSharpenRadius",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e9c8b36e09c7),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cff8983d8e70),0.2f,3f,0.7f,{it.scanSharpenRadius},{p,v->p.copy(scanSharpenRadius=v)},{it.scanSharpenAmount>0})
        )
        else->emptyList()
    }
    private fun extraControlsRaw():List<Control> = when(group){
        "film"->listOf(
            Control("filmStock",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c4d38d9c07cc),R.drawable.ic_film,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5f86d5d545f8)),
            Control("filmFormatMm",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9b7a0bf8b525),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_13d6c864eb05),8f,120f,35f,{it.filmFormatMm},{p,v->p.copy(filmFormatMm=v)},exactValue=true),
            Control("filmDevelopmentTime",getString(R.string.bw_film_development),R.drawable.ic_tool_curve,getString(R.string.bw_development_help)),
            Control("autoExposure",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3c14663c1cfa),R.drawable.ic_tool_auto_exposure,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_eeb4cdfe3959),exactValue=true,parameterIds=setOf("autoExposure","metering")),
            Control("highlightBoostEv",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_be94f251df06),R.drawable.ic_tool_highlights,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b779407bf2a),0f,3f,0f,{it.highlightBoostEv},{p,v->p.copy(highlightBoostEv=v)},exactValue=true),
            Control("highlightBoostRange",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7db1278a003a),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f3e9a635edb2),0f,1f,0.3f,{it.highlightBoostRange},{p,v->p.copy(highlightBoostRange=v)},{it.highlightBoostEv>0},true),
            Control("highlightProtectEv",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1eb299c92ae4),R.drawable.ic_tool_protect,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_67ca9c11ac9c),0f,4f,4f,{it.highlightProtectEv},{p,v->p.copy(highlightProtectEv=v)},exactValue=true)
        )
        "light"->listOf(
            Control("lensBlurUm",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_35d9afa371aa),R.drawable.ic_tool_blur,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a19e8ff37742),0f,100f,0f,{it.lensBlurUm},{p,v->p.copy(lensBlurUm=v)},exactValue=true),
            Control("scatterAmount",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1ecfa7339d9b),R.drawable.ic_tool_scatter,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df421b8d77b5),0f,2f,1f,{it.scatterAmount},{p,v->p.copy(scatterAmount=v)},exactValue=true),
            Control("scatterSize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_27360875f5af),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ff2824686803),0.25f,3f,1f,{it.scatterSize},{p,v->p.copy(scatterSize=v)},{it.scatterAmount>0},true),
            Control("halationColor",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_67eec7fc23cb),R.drawable.ic_tool_color,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_80a38a96456b),active={it.halation>0},exactValue=true),
            Control("halationBounces",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_96dc8a905d84),R.drawable.ic_tool_bounce,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_990e19e16b96),1f,5f,3f,{it.halationBounces.toFloat()},{p,v->p.copy(halationBounces=v.roundToInt())},{it.halation>0},true),
            Control("halationDecay",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d8d39aadba89),R.drawable.ic_tool_decay,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1b574ccb3dca),0f,1f,0.5f,{it.halationDecay},{p,v->p.copy(halationDecay=v)},{it.halation>0},true),
            Control("halationNormalize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_901dc35db60c),R.drawable.ic_tool_normalize,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3aa08a91a7b7),active={it.halation>0},exactValue=true)
        )
        "grain"->listOf(
            Control("grainRedScale",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1ac007c4cc44),R.drawable.ic_tool_layer_red,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7ee55be9078d),0.25f,3f,1f,{it.grainRedScale},{p,v->p.copy(grainRedScale=v)},{it.grain>0},true),
            Control("grainGreenScale",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4178263a7543),R.drawable.ic_tool_layer_green,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7ee55be9078d),0.25f,3f,1f,{it.grainGreenScale},{p,v->p.copy(grainGreenScale=v)},{it.grain>0},true),
            Control("grainBlueScale",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bf4fa10b9092),R.drawable.ic_tool_layer_blue,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7ee55be9078d),0.25f,3f,1f,{it.grainBlueScale},{p,v->p.copy(grainBlueScale=v)},{it.grain>0},true),
            Control("grainSublayers",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0932a36c793c),R.drawable.ic_tool_layers,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_18a3261024a9),exactValue=true),
            Control("grainLayers",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_032a197cbdc1),R.drawable.ic_tool_layer_count,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bec5aa3e1052),1f,4f,1f,{it.grainLayers.toFloat()},{p,v->p.copy(grainLayers=v.roundToInt())},{it.grain>0&&!it.grainSublayers},true),
            Control("grainBlur",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_10822132628a),R.drawable.ic_tool_blur,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_703b12844fd9),0f,2f,0.65f,{it.grainBlur},{p,v->p.copy(grainBlur=v)},{it.grain>0},true),
            Control("grainDyeCloud",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7642681245ee),R.drawable.ic_tool_dye_cloud,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_697904f75f57),0f,3f,1f,{it.grainDyeCloud},{p,v->p.copy(grainDyeCloud=v)},{it.grain>0},true),
            Control("grainUniformity",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_21fab82784d2),R.drawable.ic_tool_uniform,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4e33ce2575ba),0.5f,1f,1f,{it.grainUniformity},{p,v->p.copy(grainUniformity=v)},{it.grain>0},true),
            Control("grainClumpSizeUm",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bf9837ba9fec),R.drawable.ic_tool_clump,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9dcf8c3eacb2),0f,100f,0.2f,{it.grainClumpSizeUm},{p,v->p.copy(grainClumpSizeUm=v)},{it.grain>0},true),
            Control("grainClumpStrengthNm",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c95615254a6d),R.drawable.ic_tool_clump,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c58fafa690cd),0f,1000f,30f,{it.grainClumpStrengthNm},{p,v->p.copy(grainClumpStrengthNm=v)},{it.grain>0},true)
        )
        "develop"->listOf(
            Control("filmGamma",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9f9d03c837c2),R.drawable.ic_tool_curve,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_43e5e3a7d83d),0.5f,2f,1f,{it.filmGamma},{p,v->p.copy(filmGamma=v)},{true},true),
            Control("dirAmount",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_febdf6716d8a),R.drawable.ic_tool_coupling,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2ea45ddd3494),0f,2f,1f,{it.dirAmount},{p,v->p.copy(dirAmount=v)}),
            Control("dirDiffusionUm",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d16c830dd6b9),R.drawable.ic_tool_scatter,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5add1c5c18cc),0f,100f,20f,{it.dirDiffusionUm},{p,v->p.copy(dirDiffusionUm=v)},exactValue=true),
            Control("dirSameLayer",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b52f4f0476c1),R.drawable.ic_tool_same_layer,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_57941e00a8a3),0f,2f,1f,{it.dirSameLayer},{p,v->p.copy(dirSameLayer=v)},exactValue=true),
            Control("dirInterLayer",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cad65c0c8168),R.drawable.ic_tool_inter_layer,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_44e260b2da07),0f,2f,1f,{it.dirInterLayer},{p,v->p.copy(dirInterLayer=v)},exactValue=true)
        )
        "print"->listOf(
            Control("paperDevelopmentTime",getString(R.string.bw_print_development),R.drawable.ic_tool_curve,getString(R.string.bw_development_help)),
            Control("outputRoute",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_abc2bcc3191e),R.drawable.ic_tool_route,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0001b8c5d8e1),active={!it.isReversalFilm}),
            Control("printDiffusionFilter",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6a36379e12c4),R.drawable.ic_tool_diffusion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_31b721f36494),active={it.outputRoute=="print"},exactValue=true),
            Control("printDiffusionStrength",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_30e570b25ae8),R.drawable.ic_tool_diffusion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_35e2151edfde),0f,2f,0.5f,{it.printDiffusionStrength},{p,v->p.copy(printDiffusionStrength=v)},{it.outputRoute=="print"&&it.printDiffusionFilter!="off"},true),
            Control("printDiffusionSize",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_884a4b4a9697),R.drawable.ic_tool_radius,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c7fab338a3d7),0.25f,2f,1f,{it.printDiffusionSize},{p,v->p.copy(printDiffusionSize=v)},{it.outputRoute=="print"&&it.printDiffusionFilter!="off"},true),
            Control("neutralFiltersFromDatabase",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cf53a2dacdff),R.drawable.ic_tool_neutral,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8adffac5e954),active={it.outputRoute=="print"},exactValue=true),
            Control("neutralCyan",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3369277a249a),R.drawable.ic_tool_filter_cyan,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b7bf91e866a3),0f,200f,0f,{it.neutralCyan},{p,v->p.copy(neutralCyan=v)},{it.outputRoute=="print"&&!it.effectiveNeutralFiltersFromDatabase},true),
            Control("neutralMagenta",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d74d21d856e9),R.drawable.ic_tool_filter_magenta,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b7bf91e866a3),0f,200f,65f,{it.neutralMagenta},{p,v->p.copy(neutralMagenta=v)},{it.outputRoute=="print"&&!it.effectiveNeutralFiltersFromDatabase},true),
            Control("neutralYellow",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_402b841befae),R.drawable.ic_tool_filter_yellow,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b7bf91e866a3),0f,200f,55f,{it.neutralYellow},{p,v->p.copy(neutralYellow=v)},{it.outputRoute=="print"&&!it.effectiveNeutralFiltersFromDatabase},true),
            Control("paperGamma",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7304135c5662),R.drawable.ic_tool_curve,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4362dbdb902d),0.5f,2f,1f,{it.paperGamma},{p,v->p.copy(paperGamma=v)},{it.outputRoute=="print"},true),
            Control("paperGammaFast",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6de4ace73d5d),R.drawable.ic_tool_curve,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d586866d607b),0.5f,2f,1f,{it.paperGammaFast},{p,v->p.copy(paperGammaFast=v)},{it.outputRoute=="print"},true),
            Control("paperGammaSlow",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_948834ada56f),R.drawable.ic_tool_curve,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_656f07b65ba1),0.5f,2f,1f,{it.paperGammaSlow},{p,v->p.copy(paperGammaSlow=v)},{it.outputRoute=="print"},true),
            Control("paperGammaRed",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_350e57d09710),R.drawable.ic_tool_layer_red,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6aae1486c3af),0.5f,2f,1f,{it.paperGammaRed},{p,v->p.copy(paperGammaRed=v)},{it.outputRoute=="print"},true),
            Control("paperGammaGreen",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3ddbae16e340),R.drawable.ic_tool_layer_green,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_87dcffd01b93),0.5f,2f,1f,{it.paperGammaGreen},{p,v->p.copy(paperGammaGreen=v)},{it.outputRoute=="print"},true),
            Control("paperGammaBlue",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bd27c57099e9),R.drawable.ic_tool_layer_blue,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2d22a0ed76a2),0.5f,2f,1f,{it.paperGammaBlue},{p,v->p.copy(paperGammaBlue=v)},{it.outputRoute=="print"},true),
            Control("paperDeveloperExhaustion",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6dfc6e921ae6),R.drawable.ic_tool_exhaustion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a04be1d22c78),0f,1f,0f,{it.paperDeveloperExhaustion},{p,v->p.copy(paperDeveloperExhaustion=v)},{it.outputRoute=="print"},true),
            Control("printIlluminant",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2311d090000f),R.drawable.ic_tool_illuminant,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_555843d03db0),active={it.outputRoute=="print"},exactValue=true),
            Control("printCompensation",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6535e9c39c2a),R.drawable.ic_tool_exposure,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ecb5cb67b187),active={it.outputRoute=="print"},exactValue=true),
            Control("normalizePrintExposure",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_99e0c3ab5f84),R.drawable.ic_tool_normalize,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7d5a1c52f90),active={it.outputRoute=="print"},exactValue=true),
            Control("preflashYellow",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9d35c4a1f0c8),R.drawable.ic_tool_filter_yellow,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e5180d3737f5),-30f,30f,0f,{it.preflashYellow},{p,v->p.copy(preflashYellow=v)},{it.outputRoute=="print"&&it.preflash>0},true),
            Control("preflashMagenta",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_01894cdc79b1),R.drawable.ic_tool_filter_magenta,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d08a02a1d17f),-30f,30f,0f,{it.preflashMagenta},{p,v->p.copy(preflashMagenta=v)},{it.outputRoute=="print"&&it.preflash>0},true)
        )
        "scan"->listOf(
            Control("scanBlur",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a8fd554611b),R.drawable.ic_tool_blur,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f45c23864553),0f,3f,0f,{it.scanBlur},{p,v->p.copy(scanBlur=v)},exactValue=true),
            Control("scanGlarePercent",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c08900693d28),R.drawable.ic_tool_glare,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_eeaba0ff1bbe),0f,5f,0f,{it.scanGlarePercent},{p,v->p.copy(scanGlarePercent=v)},exactValue=true),
            Control("scanGlareRoughness",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f40402359b27),R.drawable.ic_tool_roughness,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_56b172e2135b),0f,1f,0.7f,{it.scanGlareRoughness},{p,v->p.copy(scanGlareRoughness=v)},{it.scanGlarePercent>0},true),
            Control("scanGlareBlur",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_65863fca9aa3),R.drawable.ic_tool_diffusion,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_73339f074019),0f,20f,0.5f,{it.scanGlareBlur},{p,v->p.copy(scanGlareBlur=v)},{it.scanGlarePercent>0},true),
            Control("scanWhiteCorrection",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9c6c44727001),R.drawable.ic_tool_white_point,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e4d3654ef963),active={correctionSupported(it)},exactValue=true),
            Control("scanBlackCorrection",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_391a41ace427),R.drawable.ic_tool_black_point,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a4922750f39f),active={correctionSupported(it)},exactValue=true),
            Control("scanWhiteLevel",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a4e50b0ef643),R.drawable.ic_tool_white_point,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4d74dee109f4),0.5f,1f,0.98f,{it.scanWhiteLevel},{p,v->p.copy(scanWhiteLevel=v)},{it.scanWhiteCorrection&&correctionSupported(it)},true),
            Control("scanBlackLevel",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6654107ffef8),R.drawable.ic_tool_black_point,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f040ad2ae5b5),0f,0.2f,0.01f,{it.scanBlackLevel},{p,v->p.copy(scanBlackLevel=v)},{it.scanBlackCorrection&&correctionSupported(it)},true),
            Control("outputGamut",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_646130986822),R.drawable.ic_tool_gamut,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_192216b8cb6d),exactValue=true),
            Control("useGpu",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6f16843acab7),R.drawable.ic_tool_gpu,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8d80b85bbd7f),exactValue=true)
        )
        else->emptyList()
    }
    private fun correctionSupported(p:EditorState)=p.scannerCorrectionSupported
    private fun choiceValues(id:String):List<Pair<String,String>> = when(id){
        "filmDevelopmentTime","paperDevelopmentTime"->listOf("0.0" to getString(R.string.bw_development_default))+(if(id=="filmDevelopmentTime")listOf(4f,5f,6.5f,9f,12f)else listOf(2f,3.5f,5f,7f,9f)).map{it.toString() to "$it min"}
        "halationColor"->listOf("stock" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_683099bf5b6c),"red" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_789f61dbfcc7),"amber" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d5234258fd84),"neutral" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_574ce385b86d))
        "diffusionFilter","printDiffusionFilter"->listOf("off" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fd47edce45b),"glimmerglass" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2eb88a373a7f),"black_pro_mist" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_adb439547ac9),"pro_mist" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3b339febf98c),"cinebloom" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_aaf1818f550f))
        "outputRoute"->listOf("print" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_499e8c373806),"direct" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b5ef62893e01))
        "printIlluminant"->listOf("TH-KG3","D50","D55","D65").map{it to it}
        "autoExposure"->dev.filmroom.ui.AutoExposureChoice.options
        "neutralFiltersFromDatabase","halationNormalize","grainSublayers","printCompensation","normalizePrintExposure","scanWhiteCorrection","scanBlackCorrection","outputGamut","useGpu"->listOf("false" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fd47edce45b),"true" to if(id=="useGpu")dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7eb336e42cb5)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8da97ddda990))
        else->emptyList()
    }
    private fun choiceValue(p:EditorState,id:String):String=when(id){
        "filmDevelopmentTime"->p.filmDevelopmentTime.toString();"paperDevelopmentTime"->p.paperDevelopmentTime.toString()
        "neutralFiltersFromDatabase"->p.effectiveNeutralFiltersFromDatabase.toString();"halationColor"->p.halationColor;"diffusionFilter"->p.diffusionFilter;"printDiffusionFilter"->p.printDiffusionFilter
        "outputRoute"->p.outputRoute;"printIlluminant"->p.printIlluminant
        "halationNormalize"->p.halationNormalize.toString();"grainSublayers"->p.grainSublayers.toString();"autoExposure"->dev.filmroom.ui.AutoExposureChoice.value(p)
        "printCompensation"->p.printCompensation.toString();"normalizePrintExposure"->p.normalizePrintExposure.toString()
        "scanWhiteCorrection"->p.effectiveScanWhiteCorrection.toString();"scanBlackCorrection"->p.effectiveScanBlackCorrection.toString();"outputGamut"->p.outputGamut.toString();"useGpu"->p.useGpu.toString()
        else->""
    }
    private fun choose(p:EditorState,id:String,value:String):EditorState=when(id){
        "filmDevelopmentTime"->p.copy(filmDevelopmentTime=value.toFloat());"paperDevelopmentTime"->p.copy(paperDevelopmentTime=value.toFloat())
        "neutralFiltersFromDatabase"->p.copy(neutralFiltersFromDatabase=value.toBoolean());"halationColor"->p.copy(halationColor=value);"diffusionFilter"->p.copy(diffusionFilter=value);"printDiffusionFilter"->p.copy(printDiffusionFilter=value)
        "outputRoute"->p.selectOutputRoute(value)
        "printIlluminant"->p.copy(printIlluminant=value)
        "halationNormalize"->p.copy(halationNormalize=value.toBoolean());"grainSublayers"->p.copy(grainSublayers=value.toBoolean());"autoExposure"->dev.filmroom.ui.AutoExposureChoice.choose(p,value)
        "printCompensation"->p.copy(printCompensation=value.toBoolean());"normalizePrintExposure"->p.copy(normalizePrintExposure=value.toBoolean())
        "scanWhiteCorrection"->p.copy(scanWhiteCorrection=value.toBoolean());"scanBlackCorrection"->p.copy(scanBlackCorrection=value.toBoolean());"outputGamut"->p.copy(outputGamut=value.toBoolean());"useGpu"->p.copy(useGpu=value.toBoolean())
        else->p
    }
    private fun buildTools(){
        if(!::tools.isInitialized)return
        if(landscapeEditor){buildLandscapeTools();return}
        visibleStrips.forEach{rememberStrip(it);it.view.setOnScrollChangeListener(null)};visibleStrips.clear()
        val s=model.state.value
        // VM restoration/external entry must have the same single selected tool as a tap.
        if(s.compositionEditing && selected!="composition"){
            compositionReturnTool=selected;compositionReturnGroup=group;selected="composition";group="film"
        }
        if(!s.compositionEditing && selected=="composition")restoreCompositionTool()
        val p=s.parameters;tools.removeAllViews();builtParameters=p;builtCompositionEditing=s.compositionEditing;builtCompositionAspect=p.composition.targetAspect;tools.tag=s.editable
        try {
        buildCategories()
        if(selected=="metering")selected="autoExposure" // Restore the legacy separate tool without changing parameters.
        val choices=controls()+extraControls();if(selected!="composition" && choices.none{it.id==selected})selected=choices.first().id
        val selector=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val strip=row();selector.addView(strip)
        for(control in choices){
            strip.addView(toolButton(control.label,control.icon,selected==control.id){finishCompositionForToolChange();selected=control.id;buildTools()}.apply{tag=control.id},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(4)})
            if(control.id=="filmStock"){
                (compositionButton.parent as? ViewGroup)?.removeView(compositionButton)
                strip.addView(compositionButton,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(4)})
            }
        }
        retainStrip(selector,strip,"tools:$group",selected)
        tools.addView(selector)
        if(s.compositionEditing){buildCompositionTools(p,s.editable);return}
        val control=choices.first{it.id==selected}
        renderControl(control,p,s,tools)
        } finally { mountTools() }
    }
    private fun forgetSectionStrips(parent:View){
        fun contains(view:View):Boolean {var node:View?=view;while(node!=null){if(node===parent)return true;node=node.parent as? View};return false}
        val old=visibleStrips.filter{contains(it.view)}
        old.forEach{rememberStrip(it);it.view.setOnScrollChangeListener(null)};visibleStrips.removeAll(old.toSet())
    }
    private fun buildLandscapeTools(preserveSlider:String?=null){
        val s=model.state.value;val p=s.parameters
        if(s.compositionEditing && group!="film"){compositionReturnGroup=group;compositionReturnTool=selected;group="film";selected="composition"}
        if(!s.compositionEditing && selected=="composition")restoreCompositionTool()
        val groupChanged=landscapeBuiltGroup!=group
        if(groupChanged){
            rememberLandscapeScroll();forgetSectionStrips(tools);tools.removeAllViews();landscapeSections.clear()
            forgetSectionStrips(categoryBar.parent as View);buildCategories();landscapeBuiltGroup=group
        }
        val controls=controls()+extraControls()
        for(control in controls){
            val id=control.id
            val value=when(id){"filmStock"->listOf(p.filmStock,p.filmBorder,p.negativeWhiteHoles,p.outputRoute,p.effectiveMediumFormat,p.effectiveMediumPortrait);"paper"->p.paperStock;else->if(choiceValues(id).isNotEmpty())choiceValue(p,id)else control.read(p)}
            val key=listOf(value,p.filmStock,p.paperStock,p.outputRoute,s.editable,control.active(p),if(group=="print")p.outputRoute else null,
                if(id=="outputRoute" || id=="scanWhiteCorrection" || id=="scanBlackCorrection")listOf(p.isReversalFilm,p.preferredOutputRoute,p.scannerCorrectionSupported,p.scanWhiteCorrection,p.scanBlackCorrection)else null)
            val section=landscapeSections.getOrPut(id){
                ParameterSection(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="parameter-section:$id";setPadding(0,dp(8),0,dp(12))},emptyList()).also{
                    tools.addView(it.view,LinearLayout.LayoutParams(-1,-2))
                }
            }
            if(section.key!=key){
                if(id!=preserveSlider){
                    forgetSectionStrips(section.view);section.view.removeAllViews()
                    if(id=="filmStock")controlHeading(control,null,section.view)
                    renderControl(control,p,s,section.view)
                }
                section.key=key
            }
            if(id=="filmStock"){
                val cropKey=listOf(s.compositionEditing,s.editable,p.frameStock,p.filmBorder,p.crop.mode,p.composition.targetAspect,p.effectiveMediumFormat,p.effectiveMediumPortrait)
                val crop=landscapeSections.getOrPut("composition"){
                    ParameterSection(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;tag="parameter-section:composition";setPadding(0,dp(8),0,dp(12))},emptyList()).also{tools.addView(it.view,LinearLayout.LayoutParams(-1,-2))}
                }
                if(crop.key!=cropKey){
                    forgetSectionStrips(crop.view);crop.view.removeAllViews()
                    buildCompositionTools(p,s.editable,crop.view)
                    crop.key=cropKey
                }
            }
        }
        builtParameters=p;builtCompositionEditing=s.compositionEditing;builtCompositionAspect=p.composition.targetAspect;tools.tag=s.editable
        if(groupChanged){
            val destination=landscapePositions[group] ?: 0
            landscapeScroll?.addOnLayoutChangeListener(object:View.OnLayoutChangeListener{
                override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){
                    v.removeOnLayoutChangeListener(this);(v as ScrollView).scrollTo(0,destination)
                }
            });landscapeScroll?.requestLayout()
        }
    }
    private fun buildCategories(){
        categoryBar.removeAllViews()
        (categoryBar.parent as HorizontalScrollView).background=shape(raised)
        val groups=listOf(Triple("film",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a97cb6107f92),R.drawable.ic_film),Triple("adjust",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f65c42a2540b),R.drawable.ic_adjust),Triple("light",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_83219449b5eb),R.drawable.ic_halation),Triple("grain",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b82bd426d236),R.drawable.ic_grain),Triple("develop",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1b5d3d55ef48),R.drawable.ic_adjust),Triple("print",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_499e8c373806),R.drawable.ic_film),Triple("scan",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c2a291b64f6b),R.drawable.ic_gallery))
        if(groups.none{it.first==group})group="film"
        for((key,label,_) in groups)categoryBar.addView(button(label){rememberLandscapeScroll();finishCompositionForToolChange();group=key;selected="";buildTools()}.apply{
            tag=key;setTextColor(if(group==key)accent else muted);background=shape(if(group==key)surface else Color.TRANSPARENT);isSelected=group==key
        })
        retainStrip(categoryBar.parent as HorizontalScrollView,categoryBar,"categories",group)
    }
    private fun finishCompositionForParameterEdit(){if(landscapeEditor && model.state.value.compositionEditing)finishCompositionForToolChange()}
    private fun renderControl(control:Control,p:EditorState,s:EditorUiState,target:LinearLayout){
        val optionValues=choiceValues(control.id)
        if(optionValues.isNotEmpty()){
            controlHeading(control,null,target)
            target.addView(dev.filmroom.ui.SettledChoiceSelector(this).apply {
                bind(control.label,optionValues.map{it.second},optionValues.indexOfFirst{it.first==choiceValue(p,control.id)}) { which ->
                    finishCompositionForParameterEdit()
                    val now=model.state.value
                    if(now.editable&&control.active(now.parameters))
                        model.parameters(choose(now.parameters,control.id,optionValues[which].first),true)
                }
                enabled(this,s.editable&&control.active(p))
            },LinearLayout.LayoutParams(-1,-2))
            if(control.id=="outputRoute" && p.isReversalFilm){
                target.addView(text(getString(R.string.route_reversal_only),12f,muted))
                if(p.preferredOutputRoute=="print")target.addView(text(getString(R.string.route_print_preference_retained),12f,muted))
            }
            if((control.id=="scanWhiteCorrection" && p.scanWhiteCorrection || control.id=="scanBlackCorrection" && p.scanBlackCorrection) && !p.scannerCorrectionSupported)
                target.addView(text(getString(R.string.route_correction_preference_retained),12f,muted))
            return
        }
        when(control.id){
            "filmStock"->stockChoices(p,s,target)
            "paper"->{
                paperChoices(p,s,target)
                if(p.outputRoute=="print" && p.isMonochromePrint)
                    target.addView(text(getString(R.string.print_medium_bw_help),12f,muted))
            }
            else->singleSlider(control,p,s.editable,target)
        }
    }
    private fun restoreCompositionTool(){
        selected=compositionReturnTool?.takeUnless{it=="composition"} ?: "filmStock"
        group=compositionReturnGroup ?: "film"
        compositionReturnTool=null;compositionReturnGroup=null
    }
    private fun finishCompositionForToolChange(){
        if(model.state.value.compositionEditing){photo.finishCompositionGesture();model.confirmComposition()}
        if(selected=="composition")restoreCompositionTool()
    }
    private fun buildCompositionTools(p:EditorState,editable:Boolean,target:LinearLayout=tools){
        val editing=model.state.value.compositionEditing
        val heading=dev.filmroom.ui.ResponsiveSectionHeading(this)
        heading.addView(sectionTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f02806d5dc08),R.drawable.ic_composition).apply{tag="crop-title";if(!landscapeEditor)maxLines=1},LinearLayout.LayoutParams(0,-2,1f))
        target.addView(heading,LinearLayout.LayoutParams(-1,-2))
        if(p.filmBorder){
            buildFramedCompositionChoices(p,editable,editing,target)
        }else{
            val choices=listOf(
                CropMode.ORIGINAL to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4b1db520af46),CropMode.FREE to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d1047eed5dd5),CropMode.SQUARE to "1:1",
                CropMode.RATIO_3_2 to "3:2",CropMode.RATIO_2_3 to "2:3",CropMode.RATIO_4_3 to "4:3",
                CropMode.RATIO_3_4 to "3:4",CropMode.RATIO_16_9 to "16:9",CropMode.RATIO_9_16 to "9:16")
            val current=p.crop.mode
            val scroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false;tag="crop-modes-scroll"}
            val modes=row();scroll.addView(modes)
            for((mode,label)in choices)modes.addView(button(label){photo.finishCompositionGesture();model.chooseCompositionMode(mode)}.apply{
                tag="crop-mode:${mode.name}";maxLines=1;setHorizontallyScrolling(false);isSelected=current==mode;setTextColor(if(isSelected)accent else ink)
                val ratio=when(mode){
                    CropMode.RATIO_3_2->1.5;CropMode.RATIO_2_3->2.0/3.0;CropMode.RATIO_4_3->4.0/3.0;CropMode.RATIO_3_4->.75
                    CropMode.RATIO_16_9->16.0/9.0;CropMode.RATIO_9_16->9.0/16.0;CropMode.SQUARE->1.0
                    else->model.state.value.photo?.let{it.originalWidth.toDouble()/it.originalHeight.coerceAtLeast(1)} ?: 1.5
                }
                val icon=dev.filmroom.ui.CropAspectDrawable(ratio,when(mode){CropMode.FREE->"free";CropMode.ORIGINAL->"original";else->"ratio"},if(isSelected)accent else ink)
                icon.setBounds(0,0,dp(24),dp(24));setCompoundDrawables(null,icon,null,null);compoundDrawablePadding=dp(4)
                contentDescription=label
                background=shape(if(isSelected)raised else Color.TRANSPARENT);enabled(this,editable && editing)
            },LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(4)})
            retainStrip(scroll,modes,"crop-modes:false","crop-mode:${current.name}")
            target.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        }
        if(editing){
            val wheelRow=row().apply{gravity=Gravity.CENTER_VERTICAL;tag="composition-straighten-row"}
            val current=if(p.filmBorder)p.composition.straightenDegrees else p.crop.transform.straightenDegrees
            val dial=dev.filmroom.ui.StraightenDialView(this,ink,muted,accent).apply{
                tag="composition-straighten-dial";angle=current;isEnabled=editable
                onAngleChanged={angle,commit->photo.straighten(angle,commit)}
            }
            straightenDial=dial
            wheelRow.addView(dial,LinearLayout.LayoutParams(0,-2,1f))
            val reset=iconButton(getString(R.string.composition_straighten_reset),R.drawable.ic_reset){
                photo.straighten(0.0,true)
            }.apply{tag="composition-straighten-reset";enabled(this,editable && current!=0.0)}
            straightenReset=reset;wheelRow.addView(reset,LinearLayout.LayoutParams(dp(48),dp(48)))
            target.addView(wheelRow,LinearLayout.LayoutParams(-1,-2))
        }else{straightenDial=null;straightenReset=null}
        val actions=(if(landscapeEditor)dev.filmroom.ui.ResponsiveSectionHeading(this,false)else row()).also{heading.addView(it,LinearLayout.LayoutParams(-2,-2))}
        if(!editing){
            actions.addView(compositionAction(getString(R.string.composition_adjust_action),true){compositionButton.performClick()}.apply{
                tag="crop-adjust";enabled(this,editable && compositionButton.isEnabled)
            })
        }else{
            actions.addView(compositionAction(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738),compactPortrait=true){photo.discardCompositionGesture();model.cancelComposition()}.apply{tag="crop-cancel";enabled(this,editable)})
            actions.addView(compositionAction(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_36f33adaf094),true,compactPortrait=true){photo.finishCompositionGesture();model.confirmComposition()}.apply{tag="crop-confirm";enabled(this,editable)},LinearLayout.LayoutParams(-2,-2).apply{if(!landscapeEditor)marginStart=dp(4)})
        }
    }
    private fun compositionPortrait(p:EditorState):Boolean = if(p.effectiveMediumFormat!=null)p.effectiveMediumPortrait
        else p.composition.targetAspect?.let{it<1.0} ?: (model.state.value.photo?.let{it.originalHeight>it.originalWidth} ?: false)
    private fun buildFramedCompositionChoices(p:EditorState,editable:Boolean,editing:Boolean,target:LinearLayout){
        val portrait=compositionPortrait(p)
        val label=dev.filmroom.i18n.L10n.text(if(portrait)R.string.text_cad49035a99d else R.string.text_61d5477ed69a)
        val nextLabel=dev.filmroom.i18n.L10n.text(if(portrait)R.string.text_61d5477ed69a else R.string.text_cad49035a99d)
        val controls=row().apply{tag="composition-frame-controls"}
        val toggle=button(label){
            // Read the live draft, not the captured binding: rapid taps must alternate.
            photo.finishCompositionGesture()
            val state=model.state.value
            if(state.editable && state.compositionEditing && state.parameters.filmBorder){
                model.chooseCompositionMode(if(compositionPortrait(state.parameters))CropMode.RATIO_3_2 else CropMode.RATIO_2_3)
            }
        }.apply{
            tag="composition-orientation-toggle";textSize=12f;maxLines=1
            setPadding(dp(10),dp(8),dp(10),dp(8));setTextColor(ink)
            background=android.graphics.drawable.InsetDrawable(shape(raised),0,dp(4),0,dp(4))
            // InsetDrawable supplies padding; restore the intended text bounds afterwards.
            setPadding(dp(10),dp(8),dp(10),dp(8))
            val icon=dev.filmroom.ui.OrientationToggleDrawable(portrait,ink)
            icon.setBounds(0,0,dp(24),dp(24));setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=dp(6)
            contentDescription=getString(R.string.composition_orientation_toggle_description,label,nextLabel)
            androidx.core.view.ViewCompat.setStateDescription(this,label);tooltipText=contentDescription;enabled(this,editable && editing)
        }
        controls.addView(toggle,LinearLayout.LayoutParams(-2,-2))
        controls.addView(View(this).apply{setBackgroundColor(raised);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(1),dp(24)).apply{marginStart=dp(8);marginEnd=dp(8)})
        val hasMedium=p.frameFormats.has120
        val formats=p.frameFormats.formats
        val formatScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false;clipToPadding=false}
        val formatChoices=row();formatScroll.addView(formatChoices)
        for(format in formats){
            val formatLabel=format?.label ?: "135"
            formatChoices.addView(button(formatLabel){photo.finishCompositionGesture();model.chooseCompositionFormat(format)}.apply{
                tag="composition-format:${format?.name ?: "135"}";maxLines=1;setHorizontallyScrolling(false)
                isSelected=p.effectiveMediumFormat==format;setTextColor(if(isSelected)accent else ink)
                contentDescription=dev.filmroom.i18n.L10n.text(R.string.medium_format_label)+" · "+formatLabel
                background=android.graphics.drawable.InsetDrawable(shape(if(isSelected)raised else Color.TRANSPARENT),0,dp(4),0,dp(4))
                setPadding(dp(12),dp(8),dp(12),dp(8));enabled(this,editable && editing && hasMedium)
            },LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(4)})
        }
        retainStrip(formatScroll,formatChoices,"composition-formats", "composition-format:${p.effectiveMediumFormat?.name ?: "135"}")
        controls.addView(formatScroll,LinearLayout.LayoutParams(0,-2,1f))
        target.addView(controls,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(4)})
    }
    private fun compositionAction(label:String,primary:Boolean=false,compactPortrait:Boolean=false,action:()->Unit)=button(label,primary=primary,action=action).apply{
        if(landscapeEditor){
            // The visual pill is smaller; the actual focus/touch bounds remain at least 48dp.
            textSize=12f
            background=android.graphics.drawable.InsetDrawable(shape(if(primary)accent else raised),dp(2),dp(7),dp(2),dp(7))
            // InsetDrawable installs its own padding, so set the text padding afterwards.
            setPadding(dp(10),dp(7),dp(10),dp(7))
        }else{
            maxLines=1
            if(compactPortrait){
                // Reduce only the visible pill height; retain text, width, padding and 48dp touch bounds.
                val insets=intArrayOf(paddingLeft,paddingTop,paddingRight,paddingBottom)
                background=android.graphics.drawable.InsetDrawable(shape(if(primary)accent else raised),0,dp(4),0,dp(4))
                setPadding(insets[0],insets[1],insets[2],insets[3])
            }
        }
    }
    private fun sectionTitle(label:String,icon:Int):TextView{
        if(!landscapeEditor)return text(label,14f)
        return dev.filmroom.ui.GlyphAlignedSectionTitleView(this).apply{
            text=label;textSize=14f;setTextColor(ink);gravity=Gravity.CENTER_VERTICAL;includeFontPadding=true
            val drawable=getDrawable(icon)!!.mutate();drawable.setTint(ink)
            setHeadingIcon(drawable,(textSize*1.15f).roundToInt(),dp(6))
        }
    }

    private fun mountTools(){
        if(landscapeEditor)return
        if(expandedTools?.isShowing==true){
            if(expandedToolId!=selected)(tools.parent as? ScrollView)?.scrollTo(0,0)
            expandedToolId=selected
            return
        }
        (tools.parent as? ViewGroup)?.removeView(tools);toolHost.removeAllViews()
        val largeText=resources.configuration.fontScale>1.35f && !model.state.value.compositionEditing
        if(largeText){
            val label=if(model.state.value.compositionEditing)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f02806d5dc08) else (controls()+extraControls()).firstOrNull{it.id==selected}?.label ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9634fb0832be)
            toolHost.addView(button(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_06ea736a7066, label),R.drawable.ic_adjust){
                val scroll=ScrollView(this).apply{setPadding(dp(12),dp(8),dp(12),dp(8));addView(tools)}
                expandedToolId=selected
                expandedTools=AlertDialog.Builder(this).setView(scroll).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c0b3fbff51cc),null).create().apply{
                    setOnDismissListener{scroll.removeView(tools);expandedTools=null;mountTools()};showReadable()
                }
            },FrameLayout.LayoutParams(-1,-2))
        }else toolHost.addView(tools,FrameLayout.LayoutParams(-1,-2))
    }
    private fun rememberStrip(binding:StripBinding){
        if(!binding.ready||binding.view.width<=0)return
        val x=binding.view.scrollX
        val anchor=(0 until binding.row.childCount).map{binding.row.getChildAt(it)}.firstOrNull{it.right>x}
        stripPositions[binding.key]=StripPosition(x,binding.view.width,anchor?.tag as? String,x-(anchor?.left ?: 0),binding.selection)
    }
    private fun retainStrip(view:HorizontalScrollView,items:LinearLayout,key:String,selectedId:String){
        val saved=stripPositions[key]
        // During asynchronous session recovery the default stock is not the restored choice yet.
        val selection=if(key=="films"&&model.state.value.photo==null)saved?.selection ?: selectedId else selectedId
        view.tag="strip:$key"
        val binding=StripBinding(view,items,key,selection);visibleStrips.add(binding)
        // Restore after child layout, before first drawing; never run an unconditional centering post.
        view.addOnLayoutChangeListener(object:View.OnLayoutChangeListener{
            override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){
                if(binding !in visibleStrips){view.removeOnLayoutChangeListener(this);return}
                if(binding.ready||view.width<=0)return
                view.removeOnLayoutChangeListener(this)
                val children=(0 until items.childCount).map{items.getChildAt(it)}
                var x=if(saved==null)0 else if(saved.width==view.width)saved.x else
                    children.firstOrNull{it.tag==saved.anchor}?.let{it.left+saved.offset} ?: saved.x
                if(saved==null||saved.width!=view.width||saved.selection!=selection){
                    children.firstOrNull{it.tag==selection}?.let{chosen->
                        if(chosen.left<x)x=chosen.left
                        else if(chosen.right>x+view.width)x=chosen.right-view.width
                    }
                }
                view.scrollTo(x.coerceAtLeast(0),0);binding.ready=true;rememberStrip(binding)
                view.setOnScrollChangeListener{_,_,_,_,_->rememberStrip(binding)}
            }
        })
        view.requestLayout()
    }
    private fun stockChoices(p:EditorState,s:EditorUiState,target:LinearLayout=tools){
            // Film browsing is deliberately tap-only; short parameter enums retain their snap rails.
            // Width comes from the visible pane, not the longest stock name.
            val cardWidth=dp(112)
            val carousel=dev.filmroom.ui.FilmStockScrollView(this,cardWidth).apply{isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false}
            val cards=row();carousel.addView(cards)
            fun chooseStock(index:Int){
                val film=films[index]
                finishCompositionForParameterEdit()
                val current=model.state.value.parameters
                if(!model.state.value.editable || current.filmStock==film.id)return
                val old=films.firstOrNull{it.id==current.filmStock}
                model.parameters(current.selectFilm(film.id,film.paper,film.route,old?.paper))
            }
            for(film in films){
                val chosen=p.filmStock==film.id
                val stock=button(dev.filmroom.ui.FilmStockArtwork.cardLabel(film.id,film.name)){
                    chooseStock(films.indexOf(film))
                }.apply{
                    tag=film.id;isSelected=chosen;textSize=12f;minimumWidth=dp(48);minimumHeight=dp(48)
                    setPadding(dp(10),dp(6),dp(10),dp(8))
                    val art=dev.filmroom.ui.FilmStockArtwork.drawable(this@MainActivity,film.id)
                    val edge=dp(72)
                    art.setBounds(0,0,edge,edge)
                    setCompoundDrawables(null,art,null,null)
                    compoundDrawablePadding=dp(2)
                    setTextColor(if(chosen)accent else ink)
                    maxLines=Int.MAX_VALUE;setHorizontallyScrolling(false)
                    background=shape(if(chosen)raised else Color.TRANSPARENT,if(chosen)accent else null)
                }
                stock.contentDescription="${film.name}，${if(film.kind=="none")"" else if(film.kind=="reversal")dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ed42cfc64f18)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d60c820b2be8)}"+if(chosen)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_657a8c2de63c)else ""
                enabled(stock,s.editable);cards.addView(stock,LinearLayout.LayoutParams(cardWidth,-2).apply{if(film!==films.last())marginEnd=dp(4)})
            }
            retainStrip(carousel,cards,"films",p.filmStock)
            target.addView(carousel,LinearLayout.LayoutParams(-1,-2))
            val infoRow=row()
            val infoScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false;tag="border-options-scroll";addView(infoRow)}
            if(dev.filmroom.border.BorderCatalog.PROTOTYPE_ENABLED){
                infoRow.addView(Switch(this).apply{
                    text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_74b054e27203);contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_369143830b0b);tag="film-border-switch";maxLines=1;setHorizontallyScrolling(false);minHeight=dp(48);isChecked=p.filmBorder
                    isEnabled=s.editable && (p.filmBorder || p.frameFormats.available)
                    setOnCheckedChangeListener{_,checked->
                        if(isEnabled && model.state.value.editable){
                            finishCompositionForParameterEdit();photo.finishCompositionGesture()
                            model.parameters(model.state.value.parameters.selectFilmBorder(checked))
                        }
                    }
                },LinearLayout.LayoutParams(-2,-2))

            }
            if(dev.filmroom.border.BorderCatalog.PROTOTYPE_ENABLED){
                infoRow.addView(Switch(this).apply{
                    text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4b96acdd5e96);contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6d2683a65646);tag="negative-white-holes-switch"
                    maxLines=1;setHorizontallyScrolling(false);minHeight=dp(48);isChecked=p.negativeWhiteHoles
                    enabled(this,s.editable && p.filmBorder && p.negativeWhiteHolesSupported)
                    setOnCheckedChangeListener{_,checked->
                        val current=model.state.value
                        if(isEnabled && current.editable && current.parameters.filmBorder && current.parameters.negativeWhiteHolesSupported){
                            finishCompositionForParameterEdit();photo.finishCompositionGesture()
                            model.parameters(model.state.value.parameters.copy(negativeWhiteHoles=checked))
                        }
                    }
                },LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            }
            // Keep imprint settings next to the film-edge controls in every editor layout.
            infoRow.addView(button(dev.filmroom.imprint.ImprintText.SETTINGS.text(this),R.drawable.ic_time_date_settings){showImprintDialog()}.apply{
                tag="imprint-settings-button";background=shape(Color.TRANSPARENT)
                maxLines=1;setHorizontallyScrolling(false);minimumHeight=dp(48)
                enabled(this,s.editable)
            },LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            target.addView(infoScroll,LinearLayout.LayoutParams(-1,-2))
    }
    private fun paperChoices(p:EditorState,s:EditorUiState,target:LinearLayout){
        val textPaint=android.text.TextPaint().apply{textSize=12f*resources.displayMetrics.scaledDensity}
        val longestLine=catalog.filter{it.kind=="paper"||it.kind=="cine_print"}
            .flatMap{dev.filmroom.ui.PrintMediumLabels.cardLabel(it.id,it.name,it.kind).lines()}
            .maxOfOrNull{textPaint.measureText(it)} ?: 0f
        val cardWidth=maxOf(dp(136),kotlin.math.ceil(longestLine+dp(20)).toInt())
        val carousel=dev.filmroom.ui.FilmStockScrollView(this,cardWidth).apply{
            isHorizontalScrollBarEnabled=false;isVerticalScrollBarEnabled=false
            contentDescription=getString(R.string.text_3691a3aa4ece)
        }
        val cards=row();carousel.addView(cards)
        val choices=listOf("none" to getString(R.string.paper_none))+papers
        for((id,name) in choices){
            val chosen=p.paperStock==id
            val medium=catalog.firstOrNull{it.id==id}
            val label=if(medium==null)name else dev.filmroom.ui.PrintMediumLabels.label(id,name,medium.kind)
            val cardLabel=if(medium==null)name else dev.filmroom.ui.PrintMediumLabels.cardLabel(id,name,medium.kind)
            val stock=button(cardLabel){
                val current=model.state.value
                if(current.editable && (current.parameters.outputRoute=="print" || !current.parameters.hasFilm || !current.parameters.hasPaper) && current.parameters.paperStock!=id){
                    finishCompositionForParameterEdit()
                    model.parameters(model.state.value.parameters.selectPaper(id))
                }
            }.apply{
                tag=id;isSelected=chosen;textSize=12f;minimumWidth=dp(48);minimumHeight=dp(48)
                setPadding(dp(8),dp(6),dp(8),dp(8))
                breakStrategy=android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency=android.text.Layout.HYPHENATION_FREQUENCY_NONE
                val art=dev.filmroom.ui.PrintMediumArtwork.drawable(this@MainActivity,id)
                val edge=dp(72);art.setBounds(0,0,edge,edge)
                setCompoundDrawables(null,art,null,null);compoundDrawablePadding=dp(2)
                setTextColor(if(chosen)accent else ink)
                maxLines=Int.MAX_VALUE;setHorizontallyScrolling(false)
                contentDescription=label+if(chosen)getString(R.string.text_657a8c2de63c)else ""
                background=shape(if(chosen)raised else Color.TRANSPARENT,if(chosen)accent else null)
                enabled(this,s.editable && (p.outputRoute=="print" || !p.hasFilm || !p.hasPaper))
            }
            cards.addView(stock,LinearLayout.LayoutParams(cardWidth,-2).apply{marginEnd=dp(4)})
        }
        retainStrip(carousel,cards,"papers",p.paperStock)
        target.addView(carousel,LinearLayout.LayoutParams(-1,-2))
    }
    private val imprintPreviewSession=dev.filmroom.imprint.ImprintPreviewSession()
    private var imprintDraftPreview:dev.filmroom.imprint.ImprintSettings?=null
    private fun bindImprintPreview(s:EditorUiState){
        val draft=imprintDraftPreview?.takeIf{imprintDialogSource==s.photo?.cacheKey && !s.compare}
        val border=s.presentation
        val crop=s.cropPresentation
        val plan=if(draft!=null && s.photo!=null && (border!=null || crop!=null)) {
            dev.filmroom.imprint.ImprintPlan.create(draft,s.photo.photoMetadata,
                border?.outputWidth ?: crop!!.outputWidth,border?.outputHeight ?: crop!!.outputHeight,border)
        }else if(s.compare)null else s.imprintPlan
        photo.bindImprint(plan,draftPreview=draft!=null)
    }
    private var imprintDialog:AlertDialog?=null
    private var imprintDialogSource:String?=null
    private var pendingImprintDraft:Bundle?=null
    private var pendingImprintSource:String?=null
    private fun showImprintDialog(draft:Bundle?=null){
        if(imprintDialog?.isShowing==true)return
        val current=model.state.value;val source=current.photo ?: return
        if(!current.editable)return
        finishCompositionForParameterEdit()
        imprintDialogSource=source.cacheKey
        val publishPreview=imprintPreviewSession.begin {settings->
            imprintDraftPreview=if(model.state.value.photo===source)settings else null
            bindImprintPreview(model.state.value)
        }
        imprintDialog=dev.filmroom.imprint.ImprintDialog.show(this,current.parameters,source.photoMetadata,draft,onPreview=publishPreview){settings->
            val latest=model.state.value
            if(latest.editable && latest.photo===source)model.parameters(latest.parameters.copy(imprint=settings))
        }
    }
    private fun formatted(control:Control,value:Float):String=when(control.id){
        "brightness","filmExposure"->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ffd510bae3ad,value)
        "grain","halation","diffusionStrength","scanSharpenAmount"->"${(value*100).roundToInt()}%"
        "grainSize","halationSize","diffusionSize"->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5b1b0d1d80a6,value)
        "preflash"->dev.filmroom.i18n.L10n.format("%.1f%%",value*100)
        "scanSharpenRadius"->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b6a198a1e6c7,value)
        "printYellow","printMagenta"->dev.filmroom.i18n.L10n.format("%+.1f",value)
        "filmFormatMm"->dev.filmroom.i18n.L10n.format("%.1f mm",value)
        "lensBlurUm","dirDiffusionUm","grainClumpSizeUm"->dev.filmroom.i18n.L10n.format("%.2f μm",value)
        "grainClumpStrengthNm"->dev.filmroom.i18n.L10n.format("%.0f nm",value)
        "halationBounces","grainLayers"->value.roundToInt().toString()
        "printExposureEv","highlightBoostEv","highlightProtectEv"->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ffd510bae3ad,value)
        "scanGlarePercent"->dev.filmroom.i18n.L10n.format("%.2f%%",value)
        else->if(control.exactValue)dev.filmroom.i18n.L10n.format("%.2f",value)else dev.filmroom.i18n.L10n.format("%+.0f",(value-control.neutral)/(control.max-control.min)*100)
    }
    private fun controlHeading(control:Control,value:String?,target:LinearLayout=tools):TextView{
        val heading=dev.filmroom.ui.ResponsiveSectionHeading(this)
        heading.addView(sectionTitle(control.label,control.icon),LinearLayout.LayoutParams(0,-2,1f))
        val values=if(landscapeEditor)row().also{heading.addView(it,LinearLayout.LayoutParams(-2,-2))}else heading
        val label=text(value ?: "",14f,accent)
        values.addView(label,LinearLayout.LayoutParams(-2,-2))
        values.addView(iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4e3db4596715, control.label),R.drawable.ic_info){AlertDialog.Builder(this).setTitle(control.label).setMessage(control.help).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_de32e20193ad),null).showReadable()})
        target.addView(heading,LinearLayout.LayoutParams(-1,-2));return label
    }
    private fun singleSlider(control:Control,p:EditorState,editable:Boolean,target:LinearLayout=tools){
        val active=control.active(p)&&!(group=="print"&&p.outputRoute!="print")
        val valueLabel=controlHeading(control,formatted(control,control.read(p)),target)
        val sliderRow=row()
        val reset=iconButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f94fbbaa8f93, control.label),R.drawable.ic_reset){finishCompositionForParameterEdit();model.parameters(control.write(model.state.value.parameters,control.neutral))}
        enabled(reset,editable&&active);sliderRow.addView(reset,LinearLayout.LayoutParams(dp(48),dp(48)))
        val seek=RulerSeekBar(this).apply{max=200;progress=((control.read(p)-control.min)/(control.max-control.min)*200).roundToInt();minimumHeight=dp(48);contentDescription="${control.label}，${formatted(control,control.read(p))}"}
        enabled(seek,editable&&active);sliderRow.addView(seek,LinearLayout.LayoutParams(0,dp(48),1f));target.addView(sliderRow,LinearLayout.LayoutParams(-1,-2))
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){finishCompositionForParameterEdit();dragging=true}
            override fun onProgressChanged(bar:SeekBar,n:Int,user:Boolean){if(user){finishCompositionForParameterEdit();val v=control.min+(control.max-control.min)*n/200;valueLabel.text=formatted(control,v);bar.contentDescription="${control.label}，${formatted(control,v)}";val current=control.write(model.state.value.parameters,v);if(!dragging)builtParameters=current;model.parameters(current,!dragging);if(!dragging && landscapeEditor)buildLandscapeTools(control.id)}}
            override fun onStopTrackingTouch(bar:SeekBar){dragging=false;model.parameters(control.write(model.state.value.parameters,control.min+(control.max-control.min)*bar.progress/200),true);buildTools()}
        })
    }
    private fun confirmPresetReset(){
        val state=model.state.value
        val source=state.photo ?: return
        if(!state.editable || state.compositionEditing || presetDialog?.isShowing==true)return
        val dialog=AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f2961369ae98))
            .setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c265e02073e5))
            .setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738),null)
            .setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e0534b8a4e46)){_,_->if(!isFinishing&&!isDestroyed&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))model.restorePreset(source.cacheKey,state.parameters)}.create()
        presetDialog=dialog
        dialog.setOnDismissListener{if(presetDialog===dialog)presetDialog=null}
        dialog.showReadable()
    }
    private var settingsOpening=false
    private fun diagnosticsDialog(){
        if(settingsOpening)return
        settingsOpening=true
        startActivity(Intent(this,SettingsActivity::class.java))
    }
    private fun photoInfo(){val s=model.state.value;val p=s.photo ?: return
        val raw=p.developmentWarning?.let{"\n\n$it"} ?: ""
        val alpha=if(p.alphaCompositedOnWhite)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d1c4835b23ed)else ""
        AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7c3154414c11)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c96f06693217, p.name, p.originalWidth, p.originalHeight, p.mimeType, p.encodedBytes/1024, alpha, raw, photo.detailStatus.label(), photo.detailStatus.let{if(it.width>0 && it.height>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_557e29365b35, it.width, it.height) else ""}))
            .setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_de32e20193ad),null).setNeutralButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8824021eb6d7)){_,_->photo.resetZoom()}.showReadable()
    }
    private fun filmInfo(){AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_685f5b4ebdf4)).setMessage(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_70e86d94f885, films.size, papers.size)) .setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_de32e20193ad),null).setNeutralButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_038ad816c53f)){_,_->showLicenses()}.showReadable()}
    private fun showLicenses(){
        val items=listOf(
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.spektralab_notices) to "licenses/spektralab/NOTICE.md",
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_070a076df57a) to "licenses/GPL-3.0.txt",
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_42cec9151c59) to "licenses/SPEKTRAFILM_LICENSE.txt",
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0b3a85a2a436) to "licenses/THIRD_PARTY_RUST_NOTICES.txt",
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_334ca499fe26) to "licenses/CODEC_NOTICES.txt",
            dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8150d89b6537) to "fixed135/PROVENANCE-AND-NOTICES.md",
        )
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a485ac7a998),14f,muted).apply{setPadding(0,0,0,dp(12))})
        for((title,file) in items){
            content.addView(button(title){
                val legal=runCatching{
                    val files=if(file=="licenses/spektralab/NOTICE.md")listOf("licenses/spektralab/ATTRIBUTION.txt",file) else listOf(file)
                    files.joinToString("\n\n"){path->assets.open(path).bufferedReader().use{it.readText()}}
                }.getOrDefault(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_200ae4c1c12e))
                val label=text(legal,14f).apply{setPadding(dp(24),dp(16),dp(24),dp(16));setTextIsSelectable(true)}
                AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(label)}).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fd47edce45b),null).showReadable()
            },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
        AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_564eb6dfeaca)).setView(ScrollView(this).apply{addView(content)}).setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fd47edce45b),null).showReadable()
    }
    private fun AlertDialog.Builder.showReadable():AlertDialog = create().also{it.showReadable()}
    private fun AlertDialog.showReadable(){
        show()
        val titleId=resources.getIdentifier("alertTitle","id","android")
        if(titleId!=0)findViewById<TextView>(titleId)?.apply{maxLines=Int.MAX_VALUE;ellipsize=null;setSingleLine(false)}
        val buttons=listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).mapNotNull{getButton(it)}.filter{it.visibility==View.VISIBLE}
        val available=resources.displayMetrics.widthPixels-dp(80)
        val needsStack=buttons.sumOf{(it.paint.measureText(it.text.toString())+it.compoundPaddingLeft+it.compoundPaddingRight).toInt()} > available
        if(needsStack){
            val bar=buttons.firstOrNull()?.parent as? LinearLayout
            if(bar!=null && buttons.all{it.parent===bar}){
                bar.orientation=LinearLayout.VERTICAL
                buttons.forEach{it.layoutParams=LinearLayout.LayoutParams(-1,-2)}
            }
        }
        buttons.forEach{it.setSingleLine(false);it.maxLines=Int.MAX_VALUE;it.ellipsize=null;it.setHorizontallyScrolling(false)
            it.layoutParams=it.layoutParams.apply{height=ViewGroup.LayoutParams.WRAP_CONTENT}}
    }
    private fun choiceAdapter(labels:List<String>,singleChoice:Boolean=false)=object:ArrayAdapter<String>(this,
        if(singleChoice)android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1,labels){
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View =
            (super.getView(position,convertView,parent) as TextView).apply{
                setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null;setHorizontallyScrolling(false)
                setPadding(dp(20),dp(12),dp(20),dp(12));minimumHeight=dp(48)
                layoutParams=AbsListView.LayoutParams(-1,-2)
            }
    }
    private fun importDialog(){
        if(!model.state.value.startupReady||model.state.value.busy!=null)return
        AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d6d466c03f8e))
            .setAdapter(choiceAdapter(listOf(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_543b8fb9aa67), dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_85534f27f5d9)))){_,which->
                if(which==0)picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else filePicker.launch(arrayOf("image/*", "application/octet-stream"))
            }.setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738),null).showReadable()
    }
    private fun exportDialog(){if(model.state.value.canExport)exportBackgroundGuidance.offer()}
    private fun exportOptionsDialog(){
        val s=model.state.value;val p=s.photo
        if(p==null || !s.canExport){exportBackgroundGuidance.finish();return}
        var edge=2560
        var originalSize=true
        var format=model.availableExportFormats.firstOrNull() ?: run{exportBackgroundGuidance.finish();return}
        var quality=95
        var outputColorSpace=s.parameters.outputColorSpace
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),0)}
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c69504e36552, p.originalWidth, p.originalHeight),14f,muted))
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_42a85b8d8038),16f).apply{setPadding(0,dp(16),0,dp(4))})
        val formats=model.availableExportFormats
        fun formatLabel(value:ExportFormat)=when(value){ExportFormat.JPEG->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b4a83add8f8c);ExportFormat.PNG->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_82aa50547f13);ExportFormat.TIFF16->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4b003cfd7c)}
        val formatChoices=RadioGroup(this)
        val formatIds=formats.mapIndexed{index,_->RadioButton(this).apply{
            id=View.generateViewId();text=formatLabel(formats[index]);minHeight=dp(48);setTextColor(ink);textSize=14f
        }.also{formatChoices.addView(it)}.id}
        formatChoices.check(formatIds[0]);content.addView(formatChoices)
        val formatHelp=text(if(format==ExportFormat.JPEG)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7a6dd5919b20)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8d15e79b0441),13f,muted);content.addView(formatHelp)
        val qualityBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val qualityLabel=text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_128a704b1837),14f)
        val qualitySeek=SeekBar(this).apply{max=99;progress=94;minimumHeight=dp(48);contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8cfce0f46112);progressTintList=ColorStateList.valueOf(accent);thumbTintList=ColorStateList.valueOf(accent)}
        qualitySeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(seek:SeekBar){}
            override fun onStopTrackingTouch(seek:SeekBar){}
            override fun onProgressChanged(seek:SeekBar,value:Int,user:Boolean){quality=value+1;qualityLabel.text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7190cce2fd40, quality);seek.contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_59df7ac272da, quality)}
        })
        qualityBox.addView(qualityLabel);qualityBox.addView(qualitySeek);qualityBox.visibility=if(format==ExportFormat.JPEG)View.VISIBLE else View.GONE;content.addView(qualityBox)
        formatChoices.setOnCheckedChangeListener{_,checked->
            val index=formatIds.indexOf(checked);if(index>=0)format=formats[index]
            qualityBox.visibility=if(format==ExportFormat.JPEG)View.VISIBLE else View.GONE
            formatHelp.text=when(format){
                ExportFormat.JPEG->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7a6dd5919b20)
                ExportFormat.PNG->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8d15e79b0441)
                ExportFormat.TIFF16->dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6e539699fdb0)
            }
        }
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_20f14d321daa),16f).apply{setPadding(0,dp(12),0,dp(4))})
        val colorChoices=RadioGroup(this)
        val colorSpaces=listOf("sRGB","Display P3","Adobe RGB (1998)")
        val colorIds=colorSpaces.map{space->RadioButton(this).apply{id=View.generateViewId();text=space;minHeight=dp(48);setTextColor(ink)}.also{colorChoices.addView(it)}.id}
        colorChoices.check(colorIds[colorSpaces.indexOf(outputColorSpace).coerceAtLeast(0)])
        colorChoices.setOnCheckedChangeListener{_,id->colorIds.indexOf(id).takeIf{it>=0}?.let{outputColorSpace=colorSpaces[it]}}
        content.addView(colorChoices)
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df0877ff9634),13f,muted))
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4a7b0d4654d7),16f).apply{setPadding(0,dp(16),0,dp(4))})
        val choices=RadioGroup(this)
        val largest=maxOf(p.originalWidth,p.originalHeight)
        fun outputOption(w:Int,h:Int)=dev.filmroom.border.BorderExportPolicy.option(s.parameters.frameStock,w,h,s.parameters.filmBorder,canonicalWidth=p.originalWidth,canonicalHeight=p.originalHeight,composition=s.parameters.composition,negativeWhiteHoles=s.parameters.negativeWhiteHoles,outputRoute=s.parameters.outputRoute,mediumFormat=s.parameters.effectiveMediumFormat,mediumPortrait=s.parameters.effectiveMediumPortrait)
        val standard=p.outputSize(2560)
        val standardOption=outputOption(standard.width,standard.height)
        val small=RadioButton(this).apply{isEnabled=standardOption.available;id=View.generateViewId();text=(if(largest<=2560)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f60066ec4bbf)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6bea77acefb3))+" · ${standardOption.label}";minHeight=dp(48);setTextColor(ink);textSize=14f}
        choices.addView(small)
        val edgeById=mutableMapOf(small.id to 2560)
        if(largest>4096){val large=p.outputSize(4096)
            val largeOption=outputOption(large.width,large.height)
            val bigger=RadioButton(this).apply{isEnabled=largeOption.available;id=View.generateViewId();text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d1ee60a322e5, largeOption.label);minHeight=dp(48);setTextColor(ink);textSize=14f}
            choices.addView(bigger);edgeById[bigger.id]=4096
        }
        val fullOption=outputOption(p.originalWidth,p.originalHeight)
        val full=if(largest>2560)RadioButton(this).apply{isEnabled=fullOption.available;id=View.generateViewId();text=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1018b7d83c8b, fullOption.label);minHeight=dp(48);setTextColor(ink);textSize=14f}.also{choices.addView(it)}else null
        choices.setOnCheckedChangeListener{_,checked->
            originalSize=full?.id==checked || (full==null && checked==small.id)
            edge=edgeById[checked] ?: 2560
        }
        // Original-size is the default. If unavailable, require an explicit smaller choice;
        // never silently downsample to make an export fit admission.
        choices.check(if(full!=null){if(full.isEnabled)full.id else -1}else if(small.isEnabled)small.id else -1)
        content.addView(choices)
        content.addView(text((if(s.parameters.filmBorder)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6d821f237f2f)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_aabd69bfed16))+dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_597fecd88b25),13f,muted))
        content.addView(text(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c767b0ebc50a),13f,muted).apply{setPadding(0,dp(12),0,dp(12))})
        AlertDialog.Builder(this).setTitle(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cef4e28b1774)).setView(ScrollView(this).apply{addView(content)})
            .setPositiveButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d14f6e1f0a6f)){_,_->if(choices.checkedRadioButtonId!=-1){if(originalSize)model.exportOriginal(format,quality,outputColorSpace)else model.export(format,edge,quality,outputColorSpace)}}
            .setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738),null)
            .setOnDismissListener{exportBackgroundGuidance.finish()}.showReadable()
    }
}
