package dev.filmroom.imprint

import android.app.AlertDialog
import android.app.Application
import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.filmroom.EditorUiState
import dev.filmroom.EditorViewModel
import dev.filmroom.MainActivity
import dev.filmroom.resetAndroidViewModelFactoryForHostTest
import dev.filmroom.border.BorderCatalog
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CropMode
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Real Activity callbacks, retained ViewModel and presentation pipeline. Only science is stubbed.
 * This is host lifecycle coverage, not device/GPU or photographic film-color acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintActivityHostTest {
    @Before fun isolate() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"en")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        // Match existing real-import Activity fixtures for engine/export disk admission.
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
    }
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun idleUi()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    private fun click(dialog:AlertDialog,which:Int) {
        // Android queues onShow/default button dismissal; exercise installed listeners after dispatch.
        idleUi();dialog.getButton(which).performClick();idleUi()
        if(which==AlertDialog.BUTTON_NEGATIVE)assertFalse(dialog.isShowing)
    }
    private fun await(message:String,diagnostic:()->String={""},done:()->Boolean) {
        repeat(1500) {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)}
        error("Imprint Activity timeout: $message ${diagnostic()}")
    }
    /** Opt-in evidence is drawn from actual mounted Android views, never recreated UI. */
    private fun capture(name:String,view:View,width:Int=0,maxHeight:Int=0) {
        val configured=System.getProperty("filmroom.imprint.evidenceDir")?.takeIf{it.isNotBlank()} ?: return
        val directory=File(configured).canonicalFile
        val repository=generateSequence(File(System.getProperty("user.dir")).canonicalFile){it.parentFile}
            .firstOrNull{File(it,"settings.gradle.kts").isFile}
        require(repository==null || !directory.toPath().startsWith(repository.toPath())){"Evidence must be outside the source tree"}
        check(directory.mkdirs() || directory.isDirectory)
        if(width>0) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
            view.layout(0,0,width,view.measuredHeight)
        }
        require(view.width>0 && view.height>0){"Evidence view has not been laid out"}
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            File(directory,"$name.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally{image.recycle()}
        File(directory,"README.txt").writeText("Actual MainActivity, imprint dialog and native date picker Android views drawn with Robolectric native Canvas. Scientific rendering uses a tiny fixture stub. These are host UI evidence, not device screenshots or film-color acceptance.\n")
    }
    /** Verify the mounted native actions, not merely the existence of their listeners. */
    private fun capturePicker(name:String,dialog:AlertDialog,width:Int,maxHeight:Int) {
        val root=dialog.window!!.decorView as ViewGroup
        fun idName(view:View)=if(view.id==View.NO_ID)"none" else runCatching{view.resources.getResourceEntryName(view.id)}.getOrDefault(view.id.toString())
        fun tree():String=buildString {
            fun appendView(view:View,depth:Int) {
                val visible=android.graphics.Rect();val hasVisible=view.getGlobalVisibleRect(visible)
                val local=android.graphics.Rect();view.getDrawingRect(local)
                if(view!==root)root.offsetDescendantRectToMyCoords(view,local)
                val params=view.layoutParams
                append("  ".repeat(depth));append(view.javaClass.simpleName)
                append(" id=${idName(view)} tag=${view.tag} visibility=${view.visibility} shown=${view.isShown}")
                append(" bounds=$local measured=${view.measuredWidth}x${view.measuredHeight} minimum=${view.minimumWidth}x${view.minimumHeight}")
                append(" globalVisible=$hasVisible:$visible params=${params?.javaClass?.simpleName}:${params?.width}x${params?.height}")
                if(params is LinearLayout.LayoutParams)append(" weight=${params.weight}")
                if(view is ScrollView)append(" scrollY=${view.scrollY} childHeight=${view.getChildAt(0)?.height} canScrollUp=${view.canScrollVertically(-1)} canScrollDown=${view.canScrollVertically(1)}")
                if(view is android.widget.HorizontalScrollView)append(" scrollX=${view.scrollX} childWidth=${view.getChildAt(0)?.width} canScrollLeft=${view.canScrollHorizontally(-1)} canScrollRight=${view.canScrollHorizontally(1)}")
                if(view is TextView)append(" text=${view.text.toString().replace('\n',' ').take(90)}")
                append('\n')
                if(view is ViewGroup)repeat(view.childCount){appendView(view.getChildAt(it),depth+1)}
            }
            appendView(root,0)
        }
        // Validate the framework's original WRAP_CONTENT window before any screenshot remeasure.
        // Forcing screen width first could hide clipping in a narrower real dialog window.
        val before=tree()
        capture("$name-original-window",root)
        System.getProperty("filmroom.imprint.evidenceDir")?.takeIf{it.isNotBlank()}?.let {
            File(it,"$name-original-window-view-bounds.txt").writeText(before)
        }
        fun assertGeometry() {
            val bounds=android.graphics.Rect(0,0,root.width,root.height)
            val visibleRoot=android.graphics.Rect()
            assertTrue("$name decor is visible",root.getGlobalVisibleRect(visibleRoot))
            assertTrue("$name decor fits the requested viewport",root.width<=width && root.height<=maxHeight)
            val touchMinimum=(48*root.resources.displayMetrics.density+.5f).toInt()
            val actions=listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE).map { which ->
                val button=dialog.getButton(which)
                assertNotNull("$name action $which",button)
                assertTrue("$name action $which is shown",button.isShown)
                assertTrue("$name action $which is enabled and clickable",button.isEnabled && button.isClickable)
                assertTrue("$name action $which needs a 48dp touch target, got ${button.width}x${button.height}",
                    button.width>=touchMinimum && button.height>=touchMinimum)
                val local=android.graphics.Rect();button.getDrawingRect(local);root.offsetDescendantRectToMyCoords(button,local)
                assertTrue("$name action $which is entirely inside decor: $local vs $bounds",bounds.contains(local))
                val visible=android.graphics.Rect()
                assertTrue("$name action $which has visible pixels",button.getGlobalVisibleRect(visible))
                assertTrue("$name action $which stays inside the global visible decor: $visible vs $visibleRoot",visibleRoot.contains(visible))
                assertEquals("$name action $which width must not be clipped",button.width,visible.width())
                assertEquals("$name action $which height must not be clipped",button.height,visible.height())
                visible
            }
            assertFalse("$name native actions must not overlap",android.graphics.Rect.intersects(actions[0],actions[1]))
            // Material TimePicker embeds its action row inside TimePicker itself. Check its actual clock
            // and header, not the ancestor that intentionally contains the two action buttons.
            val bodies=all(root).filter {
                it.javaClass.simpleName in setOf("RadialTimePickerView","DayPickerView","YearPickerView","NumberPicker") ||
                    idName(it) in setOf("time_header","date_picker_header")
            }
            assertTrue("$name must expose native picker content for the overlap check",bodies.isNotEmpty())
            for(body in bodies) {
                val visible=android.graphics.Rect()
                if(body.isShown && body.getGlobalVisibleRect(visible))for(action in actions)
                    assertFalse("$name native actions overlap ${body.javaClass.simpleName} ${idName(body)}: $action vs $visible",
                        android.graphics.Rect.intersects(action,visible))
            }
            val configuration=root.resources.configuration
            if(configuration.screenWidthDp<360 || configuration.screenHeightDp<480 || configuration.fontScale>1.35f) {
                val columns=all(root).filterIsInstance<android.widget.NumberPicker>().filter{it.isShown}
                assertTrue("$name compact native picker exposes its value columns",columns.size>=2)
                val columnBounds=columns.map { column ->
                    val visible=android.graphics.Rect()
                    assertTrue("$name numeric column ${idName(column)} is visible",column.getGlobalVisibleRect(visible))
                    assertTrue("$name numeric column ${idName(column)} stays within the visible decor",visibleRoot.contains(visible))
                    assertEquals("$name numeric column ${idName(column)} must not be clipped horizontally",column.width,visible.width())
                    assertEquals("$name numeric column ${idName(column)} must not be clipped vertically",column.height,visible.height())
                    visible
                }
                for(i in columnBounds.indices)for(j in i+1 until columnBounds.size)
                    assertFalse("$name numeric columns must not overlap",android.graphics.Rect.intersects(columnBounds[i],columnBounds[j]))
                for(horizontal in all(root).filterIsInstance<android.widget.HorizontalScrollView>().filter{it.isShown})
                    assertFalse("$name must not require horizontal scrolling to reach date/time columns",
                        horizontal.canScrollHorizontally(-1) || horizontal.canScrollHorizontally(1))
            }
        }
        assertGeometry()
        // Also exercise bounded evidence layout, but never use it to replace original-window QA.
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        root.layout(0,0,width,root.measuredHeight)
        val after=tree()
        capture(name,root)
        System.getProperty("filmroom.imprint.evidenceDir")?.takeIf{it.isNotBlank()}?.let {
            File(it,"$name-view-bounds.txt").writeText("Before bounded evidence layout:\n$before\nAfter ${width}x${maxHeight} layout:\n$after")
        }
        assertGeometry()
    }
    private fun reveal(view:View) {
        val scroll=generateSequence(view.parent){it.parent}.filterIsInstance<ScrollView>().firstOrNull() ?: return
        val bounds=android.graphics.Rect();view.getDrawingRect(bounds)
        scroll.offsetDescendantRectToMyCoords(view,bounds)
        scroll.scrollTo(0,bounds.top)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    private fun photo(a:MainActivity)=field(a,"photo") as PhotoView
    private fun boundPlan(v:PhotoView)=PhotoView::class.java.getDeclaredField("imprintPlan").apply{isAccessible=true}.get(v) as ImprintPlan?
    @Suppress("UNCHECKED_CAST") private fun overlays(v:PhotoView)=PhotoView::class.java.getDeclaredField("imprintOverlays").apply{isAccessible=true}.get(v) as List<Pair<ImprintPlan.Stamp,Bitmap>>
    @Suppress("UNCHECKED_CAST") private fun state(vm:EditorViewModel)=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
    private fun source(name:String,date:String)=PhotoSource(
        File(RuntimeEnvironment.getApplication().cacheDir,name).apply{writeText("immutable source fixture")},name,"image/jpeg",6000,4000,0,
        photoMetadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to date,"PhotographicSensitivity" to "400","MeteringMode" to "5",
            "ExposureTime" to "1/250","FNumber" to "56/10","ExposureBiasValue" to "1/2","FocalLength" to "800/1")))
    private inner class Fixture(val controller:ActivityController<MainActivity>):AutoCloseable {
        val vm=model(controller.get())
        val flow=state(vm)
        val source=source("imprint-activity.jpg","2025:04:28 12:34:56")
        val frozen=source.photoMetadata.toJson()
        val a get()=controller.get()
        val root get()=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val expanded get()=(field(a,"expandedTools") as? AlertDialog)?.takeIf{it.isShowing}
        val controlsRoot:View get()=expanded?.window?.decorView ?: root
        init {
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await("startup completes"){(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false;photo(a).disableDetail()
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            BorderCatalog.initialize(RuntimeEnvironment.getApplication().assets){vm.borderMemorySnapshot()}
            vm.renderPreview={input,_,_,_->input.copy(Bitmap.Config.ARGB_8888,false)}
            vm.detailPreviewEdge={384}
            val original=Bitmap.createBitmap(384,256,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
            val p=EditorState()
            flow.value=EditorUiState(photo=source,original=original,rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
            await("imprint tool binds"){all(field(a,"tools") as View).any{it.tag=="imprint" || it.tag=="parameter-section:imprint"}}
            layout()
            if(a.resources.configuration.fontScale>1.35f && a.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                // Open the real accessibility expansion, never click a detached tool selector.
                all(field(a,"toolHost") as View).filterIsInstance<Button>().single().performClick()
                assertNotNull(expanded);layout()
            }
        }
        fun layout(){
            val w=a.resources.configuration.screenWidthDp;val h=a.resources.configuration.screenHeightDp
            root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
            expanded?.window?.decorView?.let { decor ->
                decor.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST))
                decor.layout(0,0,w,decor.measuredHeight)
            }
        }
        fun selectTool(id:String){all(controlsRoot).singleOrNull{it.tag==id}?.performClick();layout()}
        fun imprintTools(){selectTool("imprint")}
        fun filmTools(){selectTool("filmStock")}
        fun tagged(tag:String)=all(controlsRoot).single{it.tag==tag}
        fun open():AlertDialog {
            idleUi();imprintTools();tagged("imprint-date-preview").performClick();idleUi()
            val dialog=field(a,"imprintDialog") as AlertDialog
            assertTrue(dialog.isShowing);return dialog
        }
        fun apply(dialog:AlertDialog,expected:ImprintSettings) {
            click(dialog,AlertDialog.BUTTON_POSITIVE)
            await("date settings committed after Apply"){flow.value.parameters.imprint==expected && !dialog.isShowing}
            settle()
        }
        fun settle()=await("settled exact presentation",diagnostic={
            val s=flow.value
            "canExport=${s.canExport} editable=${s.editable} busy=${s.busy} updating=${s.updating} refining=${s.previewRefining} sameParameters=${s.renderedParameters==s.parameters} samePlan=${boundPlan(photo(a))===s.imprintPlan} plan=${s.imprintPlan} bound=${boundPlan(photo(a))} message=${s.message} engine=${s.engineUnavailable} compare=${s.compare} border=${s.parameters.filmBorder} exposure=${s.parameters.imprint.exposure}"
        }){flow.value.canExport && flow.value.renderedParameters==flow.value.parameters && boundPlan(photo(a))===flow.value.imprintPlan}
        fun applyDate() {
            imprintTools()
            if(!(tagged("imprint-date-switch") as Switch).isChecked)tagged("imprint-date-switch").performClick()
            settle()
            assertTrue(flow.value.parameters.imprint.dateEnabled)
            assertEquals("contax_t2",flow.value.parameters.imprint.selectedDateStyle)
            assertFalse(checkNotNull(flow.value.imprintPlan).isEmpty)
        }
        override fun close() {
            controller.pause().stop().destroy()
            BorderCatalog.clearCache();BorderCatalog.initialize(RuntimeEnvironment.getApplication().assets)
            RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi");RuntimeEnvironment.setFontScale(1f)
        }
    }
    private fun chooseStyle(dialog:AlertDialog,index:Int) {
        val group=dialog.window!!.decorView.findViewWithTag<RadioGroup>("imprint-style")
        assertEquals(4,group.childCount);group.check(group.getChildAt(index).id)
    }
    private fun draftDateTime(dialog:AlertDialog)=ImprintDialog.capture(dialog).getString("imprint-datetime")
    private fun openDatePicker(dialog:AlertDialog):DatePickerDialog {
        dialog.window!!.decorView.findViewWithTag<Button>("imprint-date-value").performClick();idleUi()
        return (ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog).also{assertTrue(it.isShowing)}
    }
    private fun chooseDate(dialog:AlertDialog,year:Int,month:Int,day:Int) {
        val picker=openDatePicker(dialog);picker.updateDate(year,month-1,day)
        click(picker,AlertDialog.BUTTON_POSITIVE);assertFalse(picker.isShowing)
    }
    private fun assertPickerControls(dialog:AlertDialog) {
        val root=dialog.window!!.decorView
        assertTrue("The date editor must not require typed date/time text",all(root).none{it is EditText})
        for(tag in listOf("imprint-date-value")) {
            val value=root.findViewWithTag<Button>(tag)
            assertNotNull(tag,value);assertTrue(value.isEnabled);assertFalse(value.onCheckIsTextEditor())
        }
        assertNull(root.findViewWithTag<View>("imprint-time-value"))
        assertNotNull(root.findViewWithTag<TextView>("imprint-date-error"))
    }
    private fun assertSelectorGap(f:Fixture) {
        val root=f.controlsRoot as ViewGroup
        fun bounds(view:View)=android.graphics.Rect().also{view.getDrawingRect(it);root.offsetDescendantRectToMyCoords(view,it)}
        val selector=bounds(f.tagged("imprint"));val dateRow=bounds(f.tagged("imprint-date-row"))
        val required=(12*f.a.resources.displayMetrics.density+.5f).toInt()
        assertTrue("Selected date/data tile and date row need at least 12dp actual separation: ${dateRow.top-selector.bottom}px",
            dateRow.top-selector.bottom>=required)
    }
    private fun captureDialog(name:String,dialog:AlertDialog,width:Int,height:Int) {
        capture(name,dialog.window!!.decorView,width,height)
        // Also record the complete real scroll content, not just the first viewport.
        val content=dialog.window!!.decorView.findViewWithTag<ScrollView>("imprint-scroll").getChildAt(0)
        capture("$name-full-content",content,(width-48).coerceAtLeast(1),10_000)
    }
    @Test fun nativePickerPendingSelectionsSurviveRotationAcrossPresentationModes() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            for(kind in listOf("date")) {
                val parent=f.open()
                val initial=openDatePicker(parent)
                initial.updateDate(2000,1,29)
                for(landscape in listOf(true,false)) {
                    RuntimeEnvironment.setQualifiers(if(landscape)"w800dp-h400dp-land-mdpi" else "w400dp-h800dp-port-mdpi")
                    f.controller.recreate();idleUi()
                    await("$kind picker restored across presentation mode change") {
                        val current=ShadowAlertDialog.getLatestAlertDialog()
                        current?.isShowing==true && current is DatePickerDialog
                    }
                    val restored=ShadowAlertDialog.getLatestAlertDialog()
                    assertTrue(restored is DatePickerDialog)
                    val restoredDate=restored as DatePickerDialog
                    assertEquals(2000,restoredDate.datePicker.year);assertEquals(1,restoredDate.datePicker.month)
                    assertEquals(29,restoredDate.datePicker.dayOfMonth)
                    capturePicker("rotation-$kind-${if(landscape)"landscape" else "portrait"}",restored,
                        if(landscape)800 else 400,if(landscape)360 else 760)
                    val draft=field(f.a,"imprintDialog") as AlertDialog
                    assertEquals("2025-04-28 12:34:56",draftDateTime(draft))
                }
                val restored=ShadowAlertDialog.getLatestAlertDialog()
                click(restored,AlertDialog.BUTTON_POSITIVE)
                val draft=field(f.a,"imprintDialog") as AlertDialog
                assertEquals("2000-02-29 12:34:56",draftDateTime(draft))
                click(draft,AlertDialog.BUTTON_NEGATIVE)
            }
        }
    }
    @Test fun actualActivityNativePickersCancelBackRecreationAndManualApplyArePerPhoto() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.applyDate();val applied=f.flow.value.parameters.imprint
            val cancelled=f.open();assertPickerControls(cancelled)
            assertEquals("2025-04-28 12:34:56",draftDateTime(cancelled))
            captureDialog("portrait-date-style-dialog",cancelled,400,760)
            val datePicker=openDatePicker(cancelled)
            capturePicker("portrait-native-date-picker",datePicker,400,760)
            datePicker.updateDate(2000,0,2);click(datePicker,AlertDialog.BUTTON_POSITIVE)
            assertEquals("2000-01-02 12:34:56",draftDateTime(cancelled));chooseStyle(cancelled,1)
            click(cancelled,AlertDialog.BUTTON_NEGATIVE)
            assertEquals(applied,f.flow.value.parameters.imprint)
            val back=f.open()
            assertEquals("2025-04-28 12:34:56",draftDateTime(back))
            chooseStyle(back,2);back.onBackPressed();idleUi();assertFalse(back.isShowing);assertEquals(applied,f.flow.value.parameters.imprint)
            val pending=f.open();chooseStyle(pending,3);chooseDate(pending,2000,1,2)
            val pendingDate=openDatePicker(pending);pendingDate.updateDate(2000,0,3)
            assertEquals("2000-01-02 12:34:56",draftDateTime(pending))
            assertEquals("date",ImprintDialog.capture(pending).getString("imprint-picker"))
            assertEquals(applied,f.flow.value.parameters.imprint)
            f.controller.recreate()
            assertSame(f.vm,model(f.a));assertSame(f.source,f.flow.value.photo)
            await("saved Activity date draft and child picker reopen"){
                (field(f.a,"imprintDialog") as? AlertDialog)?.isShowing==true &&
                    (ShadowAlertDialog.getLatestAlertDialog() as? DatePickerDialog)?.isShowing==true
            }
            idleUi()
            val restored=field(f.a,"imprintDialog") as AlertDialog
            assertNotSame(pending,restored);assertFalse(pending.isShowing);assertFalse(pendingDate.isShowing)
            assertEquals("2000-01-02 12:34:56",draftDateTime(restored))
            val restoredDate=ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
            assertEquals(2000,restoredDate.datePicker.year);assertEquals(0,restoredDate.datePicker.month)
            assertEquals(3,restoredDate.datePicker.dayOfMonth)
            val styles=restored.window!!.decorView.findViewWithTag<RadioGroup>("imprint-style")
            assertEquals(styles.getChildAt(3).id,styles.checkedRadioButtonId)
            assertEquals(applied,f.flow.value.parameters.imprint)
            click(restoredDate,AlertDialog.BUTTON_POSITIVE)
            assertEquals("2000-01-03 12:34:56",draftDateTime(restored));assertPickerControls(restored)
            f.apply(restored,applied.copy(dateStyle="canon_luna",manualDateTime="2000-01-03 12:34:56"))
            assertEquals(applied.copy(dateStyle="canon_luna",manualDateTime="2000-01-03 12:34:56"),f.flow.value.parameters.imprint)
            assertEquals(f.frozen,f.source.photoMetadata.toJson())
            assertEquals("00年1月3日",(f.tagged("imprint-date-preview") as ImprintDatePreviewButton).displayedDate)
            val reopened=f.open();assertEquals("2000-01-03 12:34:56",draftDateTime(reopened))
            reopened.window!!.decorView.findViewWithTag<View>("imprint-restore-capture").performClick()
            assertEquals("2025-04-28 12:34:56",draftDateTime(reopened))
            f.apply(reopened,f.flow.value.parameters.imprint.copy(manualDateTime=null))
            assertNull(f.flow.value.parameters.imprint.manualDateTime)
            assertFalse("Restoring the source date does not enable a border",f.flow.value.parameters.filmBorder)
            assertEquals("Restoring the source date preserves the edge-data preference",
                applied.exposure,f.flow.value.parameters.imprint.exposure)
            f.imprintTools()
            val edge=f.tagged("imprint-exposure-switch") as Switch
            assertFalse(edge.isEnabled);assertEquals(applied.exposure,edge.isChecked);assertEquals(.38f,edge.alpha,0f)
            assertEquals(f.frozen,f.source.photoMetadata.toJson())
        }
    }
    // Robolectric native-graphics ImageDecoder on SDK35 throws at nativeBinding/createFromFile.
    // SDK29 supports this real repository decode, as in existing import host tests; UI-only tests stay35.
    @Config(sdk=[29])
    @Test fun newPhotoDismissesStaleDraftAndRealImportClearsAppliedManualDate() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.applyDate()
            f.vm.parameters(f.flow.value.parameters.let{it.copy(filmBorder=true,imprint=it.imprint.copy(exposure=true))});f.settle()
            val d=f.open();chooseStyle(d,3);chooseDate(d,2000,1,2)
            val pendingChild=openDatePicker(d);pendingChild.updateDate(2012,1,29)
            val replacement=source("imprint-replacement.jpg","1999:12:31 23:59:59")
            val before=f.flow.value.parameters
            f.flow.value=f.flow.value.copy(photo=replacement)
            await("source replacement dismisses old draft and native picker"){!d.isShowing && !pendingChild.isShowing}
            // A retained old Apply listener must also reject the new source.
            click(d,AlertDialog.BUTTON_POSITIVE)
            assertSame(replacement,f.flow.value.photo);assertEquals(before,f.flow.value.parameters)
            val latest=f.open();assertEquals("1999-12-31 23:59:59",draftDateTime(latest))
            chooseStyle(latest,0);chooseDate(latest,2001,2,3)
            f.apply(latest,f.flow.value.parameters.imprint.copy(dateStyle="contax_t2",manualDateTime="2001-02-03 23:59:59"))
            assertEquals("2001-02-03 23:59:59",f.flow.value.parameters.imprint.manualDateTime)
            val beforeFailedImport=f.flow.value.parameters
            f.vm.importPhoto(android.net.Uri.parse("content://missing-imprint-import-provider/photo"))
            val failedJob=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}.get(f.vm) as? Job
            assertNotNull(failedJob)
            await("failed import operation completes",{ "job=$failedJob busy=${f.flow.value.busy} message=${f.flow.value.messageDisplay}" }){failedJob!!.isCompleted}
            assertNull(f.flow.value.busy);assertSame(replacement,f.flow.value.photo)
            assertEquals("A failed import cannot clear the current photo override",beforeFailedImport,f.flow.value.parameters)
            assertEquals("2001-02-03 23:59:59",f.flow.value.parameters.imprint.manualDateTime)
            val stale=f.open();chooseDate(stale,2010,11,12)
            val staleChild=openDatePicker(stale);staleChild.updateDate(2010,10,13)
            val incoming=File(RuntimeEnvironment.getApplication().cacheDir,"imprint-real-import.png")
            val bitmap=Bitmap.createBitmap(384,256,Bitmap.Config.ARGB_8888)
            try {incoming.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
            val uri=android.net.Uri.parse("content://imprint-activity/incoming.png")
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri,incoming.inputStream())
            val preferences=dev.filmroom.model.EditorPreferences(RuntimeEnvironment.getApplication())
            val previousReset=preferences.resetOnImport
            preferences.resetOnImport=true
            try { f.vm.importPhoto(uri) } finally { preferences.resetOnImport=previousReset }
            val importJob=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}.get(f.vm) as? Job
            assertNotNull("Real import must create an operation",importJob)
            fun importDiagnostic():String {
                val state=f.flow.value
                return "operation=$importJob startup=${state.startupReady} busy=${state.busy} message=${state.message} " +
                    "messageDisplay=${state.messageDisplay} engine=${state.engineUnavailable} photo=${state.photo?.cacheKey} " +
                    "old=${replacement.cacheKey} manual=${state.parameters.imprint.manualDateTime} " +
                    "inputBytes=${incoming.length()} freeBytes=${incoming.parentFile!!.usableSpace}"
            }
            // Finish the real operation before inspecting adoption. A rejected import fails promptly
            // with the application's reason rather than spending the full polling window on a terminal state.
            await("real import operation completes",::importDiagnostic){importJob!!.isCompleted}
            assertNotNull(importDiagnostic(),f.flow.value.photo)
            assertNotSame(importDiagnostic(),replacement,f.flow.value.photo)
            assertNotEquals(importDiagnostic(),replacement.cacheKey,f.flow.value.photo!!.cacheKey)
            assertNull(importDiagnostic(),f.flow.value.busy)
            await("import discards open date draft and child picker"){!stale.isShowing && !staleChild.isShowing}
            // Adoption is real; host native-engine availability is not faked after repository import.
            assertNull(f.flow.value.parameters.imprint.manualDateTime)
            assertFalse("Reset on import starts with no border",f.flow.value.parameters.filmBorder)
            assertTrue("Reset on import retains the edge-data preference",f.flow.value.parameters.imprint.exposure)
            f.imprintTools()
            val edge=f.tagged("imprint-exposure-switch") as Switch
            assertFalse(edge.isEnabled);assertTrue(edge.isChecked);assertEquals(.38f,edge.alpha,0f)
            assertEquals(f.frozen,f.source.photoMetadata.toJson())
            assertTrue(incoming.exists())
            assertNull(ImprintContent.displayDateTime(f.flow.value.photo!!.photoMetadata,f.flow.value.parameters.imprint))
        }
    }
    @Test fun independentDateAndExposureSwitchesFollowCompositionInPortraitAndLandscape() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            val composition=f.tagged("composition");val imprint=f.tagged("imprint")
            assertSame(composition.parent,imprint.parent)
            val strip=composition.parent as ViewGroup
            assertEquals(strip.indexOfChild(composition)+1,strip.indexOfChild(imprint))
            f.imprintTools()
            assertEquals(2,all(field(f.a,"tools") as View).filterIsInstance<Switch>().size)
            assertFalse((f.tagged("imprint-date-switch") as Switch).isChecked)
            assertFalse((f.tagged("imprint-exposure-switch") as Switch).isChecked)
            assertEquals("'25 04 28",(f.tagged("imprint-date-preview") as ImprintDatePreviewButton).displayedDate)
            assertFalse(f.tagged("imprint-exposure-switch").isEnabled)
            f.filmTools();f.tagged("film-border-switch").performClick();f.settle();f.imprintTools()
            assertTrue(f.tagged("imprint-exposure-switch").isEnabled)
            f.tagged("imprint-exposure-switch").performClick();f.settle()
            assertTrue(f.flow.value.parameters.imprint.exposure);assertFalse(f.flow.value.parameters.imprint.dateEnabled)
            f.tagged("imprint-date-switch").performClick();f.settle()
            assertTrue(f.flow.value.parameters.imprint.exposure);assertTrue(f.flow.value.parameters.imprint.dateEnabled)
            f.layout();assertSelectorGap(f);capture("portrait-date-data-panel",f.root)
            f.tagged("imprint-date-switch").performClick();f.settle()
            assertTrue(f.flow.value.parameters.imprint.exposure);assertFalse(f.flow.value.parameters.imprint.dateEnabled)
            val before=f.flow.value.parameters.imprint
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land-mdpi");f.controller.recreate();f.layout()
            await("landscape imprint section binds"){all(f.root).any{it.tag=="parameter-section:imprint"}}
            val cropSection=f.tagged("parameter-section:composition");val dateSection=f.tagged("parameter-section:imprint")
            assertSame(cropSection.parent,dateSection.parent)
            val sections=cropSection.parent as ViewGroup
            assertEquals(sections.indexOfChild(cropSection)+1,sections.indexOfChild(dateSection))
            f.layout();reveal(dateSection);capture("landscape-date-data-section",f.root)
            assertEquals(before,f.flow.value.parameters.imprint)
            assertTrue((f.tagged("imprint-exposure-switch") as Switch).isChecked)
            assertFalse((f.tagged("imprint-date-switch") as Switch).isChecked)
            val dialog=f.open();assertPickerControls(dialog);captureDialog("landscape-date-style-dialog",dialog,800,360)
            val landscapeDate=openDatePicker(dialog)
            capturePicker("landscape-native-date-picker",landscapeDate,800,360)
            click(landscapeDate,AlertDialog.BUTTON_NEGATIVE)
            chooseStyle(dialog,1)
            f.apply(dialog,f.flow.value.parameters.imprint.copy(dateStyle="yashica_t4"))
            assertFalse("Style edits must not silently enable dates",f.flow.value.parameters.imprint.dateEnabled)
            assertTrue(f.flow.value.parameters.imprint.exposure)
        }
    }
    @Test fun exposureIsDisabledWithoutBorderAndRestoresSelectionAfterRepeatedToggles() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.imprintTools()
            val disabled=f.tagged("imprint-exposure-switch") as Switch
            assertFalse(disabled.isEnabled);assertEquals(.38f,disabled.alpha,0f)
            assertFalse(disabled.createAccessibilityNodeInfo().isEnabled)
            // Defensive callback guard also blocks synthetic/programmatic checked changes.
            disabled.isChecked=true;idleUi()
            assertFalse(f.flow.value.parameters.imprint.exposure)
            assertTrue(f.tagged("imprint-date-switch").isEnabled)
            assertTrue(f.tagged("imprint-date-preview").isEnabled)
            f.filmTools();f.tagged("film-border-switch").performClick();f.settle();f.imprintTools()
            val active=f.tagged("imprint-exposure-switch") as Switch
            assertTrue(active.isEnabled);assertEquals(1f,active.alpha,0f)
            active.performClick();f.settle()
            val saved=f.flow.value.parameters.imprint
            assertTrue(saved.exposure)
            assertFalse(checkNotNull(f.flow.value.imprintPlan).isEmpty)
            repeat(2) {
                f.filmTools();f.tagged("film-border-switch").performClick();f.settle();f.imprintTools()
                val off=f.tagged("imprint-exposure-switch") as Switch
                assertFalse(off.isEnabled);assertTrue(off.isChecked)
                assertFalse(off.createAccessibilityNodeInfo().isEnabled)
                assertEquals(saved,f.flow.value.parameters.imprint)
                assertTrue(checkNotNull(f.flow.value.imprintPlan).isEmpty)
                // A detached previously-enabled control must not edit the no-border state.
                active.isChecked=false;idleUi()
                assertEquals(saved,f.flow.value.parameters.imprint)
                f.filmTools();f.tagged("film-border-switch").performClick();f.settle();f.imprintTools()
                val on=f.tagged("imprint-exposure-switch") as Switch
                assertTrue(on.isEnabled);assertTrue(on.isChecked)
                assertTrue(on.createAccessibilityNodeInfo().isEnabled)
                assertEquals(saved,f.flow.value.parameters.imprint)
                assertFalse(checkNotNull(f.flow.value.imprintPlan).isEmpty)
            }
        }
    }
    @Test fun narrowLargeFontDateControlsStackWithoutOverlapping() {
        RuntimeEnvironment.setQualifiers("w320dp-h800dp-port-mdpi");RuntimeEnvironment.setFontScale(1.5f)
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.imprintTools();f.layout()
            val row=f.tagged("imprint-date-row") as LinearLayout
            val date=f.tagged("imprint-date-switch");val preview=f.tagged("imprint-date-preview")
            assertEquals(LinearLayout.VERTICAL,row.orientation)
            assertSame(row,date.parent);assertSame(row,preview.parent)
            assertTrue(date.height>=48);assertTrue(preview.height>0)
            assertTrue("Stacked date preview needs at least 8dp actual separation",
                preview.top-date.bottom>=(8*f.a.resources.displayMetrics.density+.5f).toInt())
            assertSelectorGap(f)
            assertTrue(preview.left>=0 && preview.right<=row.width)
            assertTrue(f.tagged("imprint-exposure-switch").height>=48)
            capture("narrow-large-font-date-data-panel",f.controlsRoot)
            val dialog=f.open();assertPickerControls(dialog)
            captureDialog("narrow-large-font-date-style-dialog",dialog,320,760)
            val datePicker=openDatePicker(dialog)
            capturePicker("narrow-large-font-native-date-picker",datePicker,320,760)
            click(datePicker,AlertDialog.BUTTON_NEGATIVE)
            click(dialog,AlertDialog.BUTTON_NEGATIVE)
        }
    }
    @Test fun chineseDateControlsUseNativePickersWithoutTypedEntry() {
        dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.imprintTools();f.layout();assertSelectorGap(f)
            capture("chinese-portrait-date-data-panel",f.root)
            val dialog=f.open();assertPickerControls(dialog)
            val root=dialog.window!!.decorView
            assertNull(root.findViewWithTag<View>("imprint-time-note"))
            captureDialog("chinese-portrait-date-style-dialog",dialog,400,760)
            val processLocale=java.util.Locale.getDefault()
            val datePicker=openDatePicker(dialog)
            assertEquals("The app picker must not mutate the process locale",processLocale,java.util.Locale.getDefault())
            val headerId=datePicker.context.resources.getIdentifier("date_picker_header_date","id","android")
            val header=datePicker.window!!.decorView.findViewById<TextView>(headerId)
            assertNotNull("The native calendar exposes its localized date header",header)
            val expectedHeader=android.icu.text.DateFormat.getInstanceForSkeleton("EMMMd",java.util.Locale.SIMPLIFIED_CHINESE)
                .apply{setContext(android.icu.text.DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)}
                .format(java.util.GregorianCalendar(2025,3,28).time)
            assertEquals(expectedHeader,header.text.toString())
            capturePicker("chinese-portrait-native-date-picker",datePicker,400,760)
            click(datePicker,AlertDialog.BUTTON_NEGATIVE)
            assertEquals("2025-04-28 12:34:56",draftDateTime(dialog))
            click(dialog,AlertDialog.BUTTON_NEGATIVE)
        }
    }
    @Test fun compareClearsLiveOverlayAndFreeCropFrameTransitionsRetainSettledImprint() {
        Fixture(Robolectric.buildActivity(MainActivity::class.java).setup()).use { f ->
            f.applyDate();val settings=f.flow.value.parameters.imprint
            f.tagged("composition").performClick();await("crop editor opens"){f.flow.value.compositionEditing}
            f.tagged("crop-mode:FREE").performClick()
            await("free crop presentation settles"){!f.flow.value.updating && f.flow.value.renderedParameters==f.flow.value.parameters}
            assertEquals(CropMode.FREE,f.flow.value.parameters.crop.mode)
            f.tagged("crop-confirm").performClick();f.settle()
            assertEquals(settings,f.flow.value.parameters.imprint);assertNotNull(f.flow.value.cropPresentation)
            f.filmTools();(f.tagged("film-border-switch") as Switch).performClick();f.settle()
            assertTrue(f.flow.value.parameters.filmBorder);assertNotNull(f.flow.value.presentation)
            assertEquals(settings,f.flow.value.parameters.imprint);assertFalse(checkNotNull(f.flow.value.imprintPlan).isEmpty)
            f.tagged("composition").performClick();await("frame editor opens"){f.flow.value.compositionEditing};f.layout()
            val view=photo(f.a)
            view.pinch(1.2,view.width/2.0,view.height/2.0)
            assertTrue("gesture uses the live composition path",view.isLiveComposition)
            val canvasBitmap=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)
            try {view.draw(Canvas(canvasBitmap))}finally{canvasBitmap.recycle()}
            assertTrue("live frame draw allocates stamp overlays",overlays(view).isNotEmpty())
            val oldOverlays=overlays(view).map{it.second}
            (field(f.a,"compare") as Button).performClick()
            await("compare clears imprint binding"){f.flow.value.compare && boundPlan(view)==null}
            assertTrue(overlays(view).isEmpty());assertTrue(oldOverlays.all{it.isRecycled})
            assertSame(f.flow.value.original,view.bitmap)
            (field(f.a,"compare") as Button).performClick()
            await("comparison exit rebinds imprint"){!f.flow.value.compare && boundPlan(view)===f.flow.value.imprintPlan}
            f.tagged("crop-confirm").performClick();f.settle()
            f.filmTools();(f.tagged("film-border-switch") as Switch).performClick();f.settle()
            assertFalse(f.flow.value.parameters.filmBorder);assertNotNull(f.flow.value.cropPresentation)
            assertEquals(CropMode.FREE,f.flow.value.parameters.crop.mode)
            assertEquals(settings,f.flow.value.parameters.imprint)
            val plan=checkNotNull(f.flow.value.imprintPlan);assertFalse(plan.isEmpty)
            assertEquals(f.flow.value.rendered!!.width,plan.width);assertEquals(f.flow.value.rendered!!.height,plan.height)
            assertEquals(f.frozen,f.source.photoMetadata.toJson());assertSame(f.source,f.flow.value.photo)
        }
    }
}
