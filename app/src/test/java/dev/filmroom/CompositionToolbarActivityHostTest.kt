package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import dev.filmroom.border.BorderCatalog
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import dev.filmroom.model.CropMode
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Real Activity/listeners and crop gestures; tiny scientific render is stubbed, not a device FPS test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompositionToolbarActivityHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    @Suppress("UNCHECKED_CAST") private fun state(vm:EditorViewModel)=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    private fun await(done:()->Boolean){repeat(1500){idle();if(done())return;Thread.sleep(5)};error("Toolbar Activity timeout")}
    @Test fun repeatedCropKeepsToolAndIconInstancesAndControlsReadLatestCrop(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a=controller.get();val vm=model(a);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            vm.localDetailEnabled=false
            val photo=field(a,"photo") as PhotoView;photo.disableDetail()
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            val original=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
            val initial=original.copy(Bitmap.Config.ARGB_8888,false);val params=EditorState()
            state.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"toolbar-source"),"fixture.jpg","image/jpeg",640,480,0),original=original,rendered=initial,parameters=params,renderedParameters=params)
            await{photo.bitmap===initial}
            fun toggle()=all(root).filterIsInstance<Switch>().single{it.text.toString()=="边框水印"}
            fun category(id:String){all(field(a,"categoryBar") as View).single{it.tag==id}.performClick();idle()}
            val action=field(a,"compositionButton") as Button
            toggle().performClick();await{state.value.presentation!=null && state.value.canExport}
            category("adjust")
            val tools=field(a,"tools") as ViewGroup
            all(tools).single{it.tag=="brightness"}.performClick();idle()
            val originalSlider=all(tools).filterIsInstance<SeekBar>().single()
            fun confirm(){all(tools).single{it.tag=="crop-confirm"}.performClick();await{!state.value.compositionEditing && state.value.canExport}}
            action.performClick();await{photo.isCompositionEditing}
            val views=all(tools);val categories=all(field(a,"categoryBar") as View)
            val icon=action.compoundDrawables[0];val background=action.background
            val compareBackground=(field(a,"compare") as Button).background
            photo.pinch(2.0,photo.width/2.0,photo.height/2.0)
            repeat(40){photo.pan(if(it%2==0)1.0 else -.25,.3);idle()
                assertEquals(views,all(tools));assertEquals(categories,all(field(a,"categoryBar") as View))
                assertSame(icon,action.compoundDrawables[0]);assertSame(background,action.background)
                assertSame(compareBackground,(field(a,"compare") as Button).background)
            }
            photo.finishCompositionGesture();confirm()
            val crop=state.value.parameters.composition;assertNotEquals(CompositionTransform(),crop)
            // This slider/listener was created BEFORE all40 crop changes.
            val slider=originalSlider
            val listener=SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply{isAccessible=true}.get(slider) as SeekBar.OnSeekBarChangeListener
            listener.onStartTrackingTouch(slider);slider.progress=140;listener.onProgressChanged(slider,140,true);listener.onStopTrackingTouch(slider)
            assertEquals(crop,state.value.parameters.composition);assertEquals(.8f,state.value.parameters.brightness,.0001f)
            await{state.value.canExport}
            // Reset button created before another crop must also preserve that crop.
            val reset=all(tools).single{it.contentDescription=="重置亮度"}
            action.performClick();await{state.value.compositionEditing};vm.composition(crop.copy(centerX=.48),true);idle();confirm();val second=state.value.parameters.composition
            reset.performClick();assertEquals(second,state.value.parameters.composition);assertEquals(0f,state.value.parameters.brightness,0f)
            await{state.value.canExport}
            category("print");all(tools).single{it.tag=="paper"}.performClick();idle()
            val paperButton=all(tools).filterIsInstance<Button>().last()
            action.performClick();await{state.value.compositionEditing};vm.composition(second.copy(centerY=.53),true);idle();confirm();val third=state.value.parameters.composition
            paperButton.performClick()
            val dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog)
            val list=dialog.listView;val selected=if(list.checkedItemPosition==0)1 else 0
            list.performItemClick(list.adapter.getView(selected,null,list),selected,selected.toLong())
            assertEquals(third,state.value.parameters.composition);await{state.value.canExport}
            val paper=state.value.parameters.paperStock
            (field(a,"undo") as Button).performClick();assertEquals(third,state.value.parameters.composition);assertNotEquals(paper,state.value.parameters.paperStock)
            await{state.value.canExport}
            category("film")
            val stock=all(tools).filterIsInstance<Button>().first{it.tag is String && (it.tag as String).startsWith("kodak_") && it.tag!=state.value.parameters.filmStock}
            action.performClick();await{state.value.compositionEditing};vm.composition(third.copy(centerX=.52),true);idle();confirm();val fourth=state.value.parameters.composition
            stock.performClick();await{state.value.parameters.filmStock==stock.tag};assertEquals(stock.tag,state.value.parameters.filmStock);assertEquals(fourth,state.value.parameters.composition)
            await{state.value.canExport}
            toggle().performClick();await{!state.value.parameters.filmBorder && !state.value.compositionEditing && action.visibility==View.VISIBLE}
            assertEquals(fourth,state.value.parameters.composition)
            (field(a,"undo") as Button).performClick();await{state.value.parameters.filmBorder && state.value.canExport}
            assertEquals(fourth,state.value.parameters.composition)
            // Restore processing preset now preserves presentation and output preferences.
            vm.parameters(state.value.parameters.copy(brightness=.7f));await{state.value.canExport}
            val beforeReset=state.value.parameters
            vm.reset();await{state.value.parameters.brightness==0f && state.value.canExport}
            val resetState=state.value.parameters
            assertEquals(beforeReset.filmStock,resetState.filmStock)
            assertEquals(beforeReset.filmBorder,resetState.filmBorder)
            assertEquals(beforeReset.negativeWhiteHoles,resetState.negativeWhiteHoles)
            assertEquals(beforeReset.composition,resetState.composition);assertEquals(beforeReset.crop,resetState.crop)
            assertEquals(beforeReset.outputColorSpace,resetState.outputColorSpace);assertEquals(beforeReset.outputGamut,resetState.outputGamut)
            (field(a,"undo") as Button).performClick();await{state.value.canExport && state.value.parameters==beforeReset}
            assertEquals(beforeReset,state.value.parameters)
        }finally{controller.pause().stop().destroy();BorderCatalog.clearCache();BorderCatalog.initialize(app.assets)}
    }

    @Test fun unframedCropPanelHasNineModesAndExplicitCancelConfirmUndo(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val a=controller.get();val vm=model(a);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            vm.localDetailEnabled=false;(field(a,"photo") as PhotoView).disableDetail()
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            val source=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888).apply{
                val canvas=Canvas(this);canvas.drawColor(Color.rgb(132,181,204))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(242,202,115)};canvas.drawCircle(73f,16f,7f,paint)
                paint.color=Color.rgb(54,102,91)
                canvas.drawPath(android.graphics.Path().apply{moveTo(0f,57f);lineTo(27f,23f);lineTo(60f,62f);lineTo(81f,32f);lineTo(96f,50f);lineTo(96f,72f);lineTo(0f,72f);close()},paint)
                paint.color=Color.rgb(32,72,75);canvas.drawRect(0f,59f,96f,72f,paint)
            }
            state.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"crop-panel-source"),"fixture.jpg","image/jpeg",640,480,0),original=source)
            vm.parameters(EditorState());await{state.value.canExport}
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
            val action=field(a,"compositionButton") as Button;val tools=field(a,"tools") as ViewGroup
            fun tagged(tag:String)=all(tools).single{it.tag==tag}
            fun capture(name:String,scrollModesToEnd:Boolean=false){
                val output=System.getenv("FILMROOM_CROP_PANEL_QA_DIR") ?: return
                await{!state.value.updating && state.value.renderedParameters==state.value.parameters}
                val directory=File(output).canonicalFile
                val repository=generateSequence(File(System.getProperty("user.dir")).canonicalFile){it.parentFile}.firstOrNull{File(it,"settings.gradle.kts").isFile}
                require(repository==null || !directory.toPath().startsWith(repository.toPath()))
                directory.mkdirs()
                root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
                if(scrollModesToEnd){
                    val strip=all(tools).filterIsInstance<android.widget.HorizontalScrollView>().single{(it.tag as? String)?.startsWith("strip:crop-modes:")==true}
                    val maximum=(strip.getChildAt(0).width-strip.width).coerceAtLeast(0)
                    assertTrue("Nine modes extend beyond the viewport",maximum>0)
                    strip.scrollTo(maximum,0);assertEquals(maximum,strip.scrollX)
                }
                val rendered=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)
                try{root.draw(Canvas(rendered));File(directory,"$name.png").outputStream().use{check(rendered.compress(Bitmap.CompressFormat.PNG,100,it))}}
                finally{rendered.recycle()}
                File(directory,"README.txt").writeText("Actual MainActivity native-graphics host draw at400x800. Authored synthetic landscape only; scientific engine is stubbed. Not an emulator/device screenshot, personal photograph, or film-color acceptance. Buttons and panel are actual runtime views. Nine plain-crop modes use a horizontally scrollable strip; start/end screenshots show both ends. Framed mode permits only landscape/portrait135.\n")
            }
            val before=state.value.parameters
            assertEquals(View.VISIBLE,action.visibility);action.performClick();await{state.value.compositionEditing}
            assertEquals(9,all(tools).count{(it.tag as? String)?.startsWith("crop-mode:")==true})
            tagged("crop-mode:FREE").performClick();assertEquals(CropMode.FREE,state.value.parameters.crop.mode)
            capture("unframed-free-modes-start")
            if(System.getenv("FILMROOM_CROP_PANEL_QA_DIR")!=null){
                val strip=all(tools).filterIsInstance<android.widget.HorizontalScrollView>().single{(it.tag as? String)?.startsWith("strip:crop-modes:")==true}
                capture("unframed-free-modes-end",scrollModesToEnd=true);strip.scrollTo(0,0)
            }
            tagged("crop-mode:SQUARE").performClick();assertEquals(CropMode.SQUARE,state.value.parameters.crop.mode)
            assertFalse("Draft must not be exportable",state.value.canExport)
            tagged("crop-cancel").performClick();await{!state.value.compositionEditing && state.value.canExport}
            assertEquals(before,state.value.parameters);assertTrue(all(tools).any{it is Switch})
            action.performClick();await{state.value.compositionEditing};tagged("crop-mode:FREE").performClick()
            a.onBackPressedDispatcher.onBackPressed();await{!state.value.compositionEditing && state.value.canExport}
            assertFalse(a.isFinishing);assertEquals(before,state.value.parameters)
            action.performClick();await{state.value.compositionEditing}
            // A mode rebuild must retain the strip and expose the newly selected end item.
            for(mode in listOf(CropMode.RATIO_9_16,CropMode.RATIO_16_9)){
                tagged("crop-mode:${mode.name}").performClick()
                await{(tagged("crop-mode:${mode.name}") as Button).isSelected}
                root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
                val strip=all(tools).filterIsInstance<android.widget.HorizontalScrollView>().single{(it.tag as? String)?.startsWith("strip:crop-modes:")==true}
                val chosen=tagged("crop-mode:${mode.name}")
                assertTrue("Selected $mode remains visible after rebuild",chosen.left>=strip.scrollX && chosen.right<=strip.scrollX+strip.width)
                assertTrue("Last ratios require horizontal scroll",strip.scrollX>0)
            }
            tagged("crop-confirm").performClick()
            await{!state.value.compositionEditing && state.value.canExport}
            assertEquals(CropMode.RATIO_16_9,state.value.parameters.crop.mode)
            (field(a,"undo") as Button).performClick();await{state.value.canExport}
            assertEquals(before,state.value.parameters)
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            all(tools).filterIsInstance<Switch>().single{it.tag=="film-border-switch"}.performClick();await{state.value.canExport}
            val framed=state.value.parameters
            action.performClick();await{state.value.compositionEditing}
            assertEquals(2,all(tools).count{(it.tag as? String)?.startsWith("crop-mode:")==true})
            capture("framed135-landscape-panel")
            tagged("crop-mode:RATIO_2_3").performClick();assertEquals(2.0/3.0,state.value.parameters.composition.targetAspect!!,0.0)
            capture("framed135-portrait-panel")
            tagged("crop-cancel").performClick();await{state.value.canExport}
            assertEquals(framed,state.value.parameters)
        }finally{controller.pause().stop().destroy();BorderCatalog.clearCache();BorderCatalog.initialize(app.assets)}
    }

    @Test fun pendingBorderToggleKeepsGeometryOfActuallyDisplayedBitmap(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val a=controller.get();val vm=model(a);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false;val photo=field(a,"photo") as PhotoView;photo.disableDetail()
            val params=EditorState(filmBorder=true)
            val border=dev.filmroom.border.BorderSnapshot.fixed135(params.filmStock,"authored-pending-fixture",96,64)
            val bitmap=Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
            val source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            val imported=PhotoSource(File(app.cacheDir,"pending-source"),"fixture.jpg","image/jpeg",96,64,0)
            state.value=EditorUiState(photo=imported,original=source,rendered=bitmap,parameters=params.copy(filmBorder=false),renderedParameters=params,presentation=border,compositionSource=source,updating=true)
            await{photo.bitmap===bitmap}
            fun shown(name:String)=PhotoView::class.java.getDeclaredField(name).apply{isAccessible=true}.get(photo)
            assertEquals(border,shown("presentation"));assertNull(shown("cropPresentation"))
            val action=field(a,"compositionButton") as Button
            assertFalse("OFF cannot edit through the old framed adapter",action.isEnabled)
            action.performClick();assertFalse(state.value.compositionEditing);assertFalse(photo.isCompositionEditing)
            val crop=dev.filmroom.crop.CropSnapshot.create(96,64,settings=dev.filmroom.model.CropSettings(CropMode.SQUARE))
            val cropped=Bitmap.createBitmap(crop.outputWidth,crop.outputHeight,Bitmap.Config.ARGB_8888)
            val croppedParams=params.copy(filmBorder=false,crop=crop.settings)
            state.value=state.value.copy(rendered=cropped,parameters=croppedParams.copy(filmBorder=true),renderedParameters=croppedParams,presentation=null,cropPresentation=crop)
            await{photo.bitmap===cropped}
            assertNull(shown("presentation"));assertEquals(crop,shown("cropPresentation"))
            assertFalse("ON cannot write a4:3/free crop into135 while old plain bitmap is displayed",action.isEnabled)
            action.performClick();assertFalse(state.value.compositionEditing);assertFalse(photo.isCompositionEditing)
            state.value=state.value.copy(rendered=bitmap,parameters=params,renderedParameters=params,presentation=border,cropPresentation=null,updating=false)
            await{photo.bitmap===bitmap && action.isEnabled}
            action.performClick();await{state.value.compositionEditing && photo.isCompositionEditing}
            state.value=state.value.copy(rendered=cropped,parameters=croppedParams,renderedParameters=croppedParams,presentation=null,cropPresentation=crop,compositionEditing=false)
            await{photo.bitmap===cropped && action.isEnabled && !photo.isCompositionEditing}
            action.performClick();await{state.value.compositionEditing && photo.isCompositionEditing}
        }finally{controller.pause().stop().destroy()}
    }

    @Test fun recreationKeepsDraftPanelAndCancelRestoresCommittedParameters(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val before=controller.get();val vm=model(before);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false;(field(before,"photo") as PhotoView).disableDetail()
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            val source=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888)
            state.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"rotation-crop-source"),"fixture.jpg","image/jpeg",640,480,0),original=source)
            vm.parameters(EditorState());await{state.value.canExport}
            val committed=state.value.parameters
            (field(before,"compositionButton") as Button).performClick();await{state.value.compositionEditing}
            vm.chooseCompositionMode(CropMode.FREE);vm.composition(CompositionTransform(.51,.49,1.5,1.2),false);idle()
            val draft=state.value.parameters;assertNotEquals(committed,draft);assertFalse(state.value.canExport)
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land-mdpi");controller.recreate();idle()
            val after=controller.get();assertSame(vm,model(after));assertTrue(state.value.compositionEditing);assertEquals(draft,state.value.parameters)
            val tools=field(after,"tools") as ViewGroup
            assertEquals(9,all(tools).count{(it.tag as? String)?.startsWith("crop-mode:")==true})
            all(tools).single{it.tag=="crop-cancel"}.performClick();await{!state.value.compositionEditing && state.value.canExport}
            assertEquals(committed,state.value.parameters);assertTrue(all(tools).any{it is Switch})
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")}
    }

    @Test fun inlineCompositionToolAndCategoryCommitOnceButBackCancels(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val a=controller.get();val vm=model(a);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            // The host ActivityManager does not model a phone's free-memory budget.
            // Match existing crop fixtures; keep real compositor/admission policy active.
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            vm.localDetailEnabled=false;(field(a,"photo") as PhotoView).disableDetail()
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            val source=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888)
            state.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"inline-crop"),"fixture.jpg","image/jpeg",640,480,0),original=source)
            vm.parameters(EditorState());await{state.value.canExport}
            val baseline=state.value.parameters
            fun enter(){
                all(field(a,"categoryBar") as View).single{it.tag=="film"}.performClick();idle()
                val action=field(a,"compositionButton") as Button
                assertTrue(action.isShown);action.performClick();await{state.value.compositionEditing}
                assertTrue(all(field(a,"tools") as View).any{it.tag=="filmStock"})
                assertTrue(all(field(a,"categoryBar") as View).any{it.tag=="adjust"})
                assertTrue(action.isSelected)
            }
            enter();vm.chooseCompositionMode(CropMode.SQUARE);idle()
            all(field(a,"tools") as View).single{it.tag=="filmFormatMm"}.performClick()
            assertFalse("Tool change must synchronously close the composition draft",state.value.compositionEditing)
            try{await{!state.value.compositionEditing && state.value.canExport}}catch(e:Exception){throw AssertionError("After tool switch: editing=${state.value.compositionEditing}, updating=${state.value.updating}, refining=${state.value.previewRefining}, job=${EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(vm)}, cropPresentation=${state.value.cropPresentation}, error=${state.value.message}, params=${state.value.parameters}, rendered=${state.value.renderedParameters}",e)}
            assertEquals(CropMode.SQUARE,state.value.parameters.crop.mode)
            vm.undo();await{state.value.canExport};assertEquals("One switch means one crop undo",baseline,state.value.parameters)
            enter();vm.chooseCompositionMode(CropMode.RATIO_16_9);idle()
            all(field(a,"categoryBar") as View).single{it.tag=="adjust"}.performClick()
            await{!state.value.compositionEditing && state.value.canExport}
            assertEquals(CropMode.RATIO_16_9,state.value.parameters.crop.mode)
            assertTrue(all(field(a,"tools") as View).any{it.tag=="filmExposure"})
            vm.undo();await{state.value.canExport};assertEquals(baseline,state.value.parameters)
            enter();vm.chooseCompositionMode(CropMode.FREE);idle()
            a.onBackPressedDispatcher.onBackPressed();await{!state.value.compositionEditing && state.value.canExport}
            assertEquals("Back discards instead of committing",baseline,state.value.parameters)
            assertTrue(all(field(a,"tools") as View).any{it.tag=="filmStock"})
        }finally{controller.pause().stop().destroy()}
    }

    @Test fun cropPanelStaysSingleLineAndActionsFitNarrowLandscapeAndLargeFont(){
        val app=RuntimeEnvironment.getApplication()
        try{for((w,h)in listOf(400 to 800,800 to 400,320 to 640))for(font in listOf(1f,2f))for(framed in listOf(false,true)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi");RuntimeEnvironment.setFontScale(font)
            app.getSharedPreferences("local-session",0).edit().clear().commit()
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            try{
                val a=controller.get();val vm=model(a);val mutable=state(vm)
                val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
                await{(operation.get(vm) as? Job)?.isCompleted==true}
                vm.localDetailEnabled=false;(field(a,"photo") as PhotoView).disableDetail()
                val source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888);val params=EditorState(filmBorder=framed)
                val border=if(framed)dev.filmroom.border.BorderSnapshot.fixed135(params.filmStock,"layout-fixture",96,64)else null
                mutable.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"layout-crop"),"fixture","image/jpeg",96,64,0),original=source,rendered=source,
                    parameters=params,renderedParameters=params,compositionSource=source,presentation=border,
                    cropPresentation=if(framed)null else dev.filmroom.crop.CropSnapshot.create(96,64),compositionEditing=true)
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                await{all(field(a,"tools") as View).any{it.tag=="crop-confirm"}}
                repeat(2){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)}
                val tools=field(a,"tools") as ViewGroup
                assertTrue("Crop actions stay directly visible at large text",tools.isShown)
                val modes=all(tools).filterIsInstance<Button>().filter{(it.tag as? String)?.startsWith("crop-mode:")==true}
                assertEquals(if(framed)2 else 9,modes.size)
                val scroll=all(tools).filterIsInstance<android.widget.HorizontalScrollView>().single{(it.tag as? String)?.startsWith("strip:crop-modes:")==true}
                assertFalse(scroll.isHorizontalScrollBarEnabled);assertFalse(scroll.isVerticalScrollBarEnabled)
                if(!framed)assertTrue("Ratios scroll instead of wrapping",scroll.getChildAt(0).width>scroll.width)
                val title=all(tools).filterIsInstance<android.widget.TextView>().single{it.tag=="crop-title"}
                assertEquals("构图",title.text.toString());assertEquals(1,title.lineCount)
                val cancel=all(tools).filterIsInstance<Button>().single{it.tag=="crop-cancel"};val confirm=all(tools).filterIsInstance<Button>().single{it.tag=="crop-confirm"}
                assertTrue(cancel.right<=confirm.left);assertEquals(cancel.top,confirm.top)
                for(button in modes+listOf(cancel,confirm)){
                    assertEquals("No wrapping at $w x $h font$font",1,button.lineCount)
                    assertTrue(button.width>=48);assertTrue(button.height>=48)
                    assertTrue("Label height is not clipped",button.height>=button.lineHeight+button.compoundPaddingTop+button.compoundPaddingBottom)
                }
                val actions=confirm.parent as ViewGroup;assertTrue(confirm.right<=actions.width)
                assertNotNull("Normal categories remain available",(field(a,"categoryBar") as View).let{(it.parent as View).background})
                if(w>h){
                    assertTrue(all(tools).any{it.tag=="parameter-section:filmStock"})
                    val cropSection=all(tools).single{it.tag=="parameter-section:composition"}
                    assertTrue("No secondary tab strip remains in landscape",all(tools).none{it.tag=="strip:tools:film"})
                    val vertical=tools.parent as android.widget.ScrollView
                    assertTrue(((field(a,"categoryBar") as View).parent as View).bottom<=vertical.top)
                    vertical.scrollTo(0,cropSection.top)
                    val visible=android.graphics.Rect()
                    assertTrue("Crop confirm reachable by vertical scrolling",confirm.getGlobalVisibleRect(visible))
                    assertEquals("Crop confirm must not be vertically clipped",confirm.height,visible.height())
                }else{
                assertTrue(all(tools).any{it.tag=="filmStock"});assertTrue(all(tools).any{it.tag=="composition"})
                val toolStrip=(field(a,"compositionButton") as Button).parent as ViewGroup
                val selectedTools=(0 until toolStrip.childCount).map{toolStrip.getChildAt(it)}.filter{it.isSelected}
                assertEquals("Exactly one active tool while composing",listOf("composition"),selectedTools.map{it.tag})
                }
                assertTrue(modes.all{it.compoundDrawables[1] is dev.filmroom.ui.CropAspectDrawable})
                val output=File("build/ui-host-renders/composition-inline").apply{mkdirs()}
                val capture=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(capture))
                File(output,"${w}x${h}-font${font}-border${framed}.png").outputStream().use{capture.compress(Bitmap.CompressFormat.PNG,100,it)};capture.recycle()
            }finally{controller.pause().stop().destroy()}
        }}finally{RuntimeEnvironment.setFontScale(1f);RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")}
    }

    @Test fun borderOptionsRetainWhitePreferenceAndNeverWrapAtNarrowLargeText(){
        val app=RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi");RuntimeEnvironment.setFontScale(2f)
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val a=controller.get();val vm=model(a);val mutable=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false;(field(a,"photo") as PhotoView).disableDetail()
            val source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val tools=field(a,"tools") as ViewGroup
            fun bind(p:EditorState){
                mutable.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"holes-controls"),"fixture","image/jpeg",96,64,0),original=source,rendered=source,parameters=p,renderedParameters=p)
                await{all(tools).filterIsInstance<Switch>().any{it.tag=="negative-white-holes-switch" && it.isChecked==p.negativeWhiteHoles && it.isEnabled==(p.filmBorder && p.negativeWhiteHolesSupported)}}
                if(!tools.isShown){
                    all(root).filterIsInstance<Button>().single{it.text.toString().startsWith("调整") && it.text.toString()!="调整"}.performClick();idle()
                }
                val dialogRoot=checkNotNull(ShadowAlertDialog.getLatestAlertDialog()).window!!.decorView
                repeat(2){dialogRoot.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));dialogRoot.layout(0,0,320,640)}
                repeat(2){root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,320,640)}
            }
            for(stock in listOf("kodak_portra_400","kodak_ektachrome_100"))for(white in listOf(true,false))for(border in listOf(false,true)){
                bind(EditorState(filmStock=stock,filmBorder=border,negativeWhiteHoles=white))
                val controls=all(tools).filterIsInstance<Switch>().filter{it.tag in listOf("film-border-switch","negative-white-holes-switch")}
                assertEquals(2,controls.size)
                assertEquals(listOf("边框水印","负片白孔"),controls.map{it.text.toString()})
                controls.forEach{assertEquals(1,it.lineCount);assertTrue(it.height>=48)}
                val strip=all(tools).filterIsInstance<android.widget.HorizontalScrollView>().single{it.tag=="border-options-scroll"}
                assertFalse(strip.isHorizontalScrollBarEnabled)
                assertTrue("Overflow scrolls instead of squeezing labels",strip.getChildAt(0).width>strip.width)
                assertTrue(controls[0].right<=controls[1].left)
                System.getenv("FILMROOM_HOLES_UI_QA_DIR")?.let{path->
                    val directory=File(path).canonicalFile
                    require(!directory.toPath().startsWith(File(System.getProperty("user.dir")).canonicalFile.toPath()))
                    directory.mkdirs()
                    val row=strip.getChildAt(0)
                    val prefix="${stock}-border${border}-white${white}"
                    fun save(name:String,w:Int,h:Int,draw:(Canvas)->Unit){
                        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        try{draw(Canvas(image));File(directory,"$prefix-$name.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
                        finally{image.recycle()}
                    }
                    // Full actual Android row, not reconstructed text or mock switches.
                    save("full-row",row.width,row.height){row.draw(it)}
                    val maximum=(row.width-strip.width).coerceAtLeast(0)
                    assertTrue(maximum>0)
                    for(end in listOf(false,true)){
                        val expected=if(end)maximum else 0
                        strip.scrollTo(expected,0)
                        assertEquals("Actual scroll position",expected,strip.scrollX)
                        // Explicit viewport translation avoids Robolectric's direct ScrollView.draw
                        // snapshot path omitting its child display-list scroll transform.
                        save(if(end)"end" else "start",strip.width,strip.height){canvas->
                            canvas.clipRect(0,0,strip.width,strip.height)
                            canvas.translate((row.left-strip.scrollX).toFloat(),(row.top-strip.scrollY).toFloat())
                            row.draw(canvas)
                        }
                        if(end)assertTrue("White-hole control is inside end viewport",controls[1].right<=strip.scrollX+strip.width && controls[1].left>=strip.scrollX)
                    }
                    File(directory,"$prefix-layout.txt").writeText("Actual Activity row in large-font dialog; native Android Canvas. row=${row.width}x${row.height}; viewport=${strip.width}x${strip.height}; maxScroll=$maximum; endScroll=${strip.scrollX}; whiteControl=${controls[1].left}..${controls[1].right}. Full-row is unscrolled; start/end explicitly apply real viewport translation. Host render, not device screenshot.\n")
                }
            }
        }finally{controller.pause().stop().destroy();RuntimeEnvironment.setFontScale(1f);RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")}
    }

    @Test fun compositionGlyphUsesCompareGeometryAndOptionalBeforeAfterSheet(){
        val context=RuntimeEnvironment.getApplication();val next=context.getDrawable(R.drawable.ic_composition)!!
        val compare=context.getDrawable(R.drawable.ic_compare)!!
        assertEquals(compare.intrinsicWidth,next.intrinsicWidth);assertEquals(compare.intrinsicHeight,next.intrinsicHeight)
        val out=System.getenv("FILMROOM_DRAG_QA_DIR") ?: return
        val bitmap=Bitmap.createBitmap(720,330,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(26,30,27))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(244,241,233);textSize=22f}
        for((row,label)in listOf("Before: system crop","After: composition normal","After: composition editing").withIndex()){
            val y=row*110;canvas.drawText(label,20f,y+60f,paint)
            val drawable=context.getDrawable(if(row==0)android.R.drawable.ic_menu_crop else R.drawable.ic_composition)!!.mutate()
            drawable.setTint(if(row==2)Color.rgb(237,211,182)else Color.rgb(244,241,233));drawable.setBounds(445,y+24,505,y+84);drawable.draw(canvas)
            compare.setBounds(565,y+24,625,y+84);compare.draw(canvas)
        }
        File(out).mkdirs();File(out,"composition-icon-before-after.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
