package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Activity rendered by host Android/Skia. Not an emulator/device screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraLayoutRenderHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList())
    private fun layout(root:View,w:Int,h:Int){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)}
    private fun fixture():Bitmap = System.getenv("FILMROOM_UI_PHOTO")?.let { path ->
        android.graphics.BitmapFactory.decodeFile(path,android.graphics.BitmapFactory.Options().apply{inSampleSize=8})
    } ?: Bitmap.createBitmap(384,512,Bitmap.Config.ARGB_8888).apply{
        val p=Paint().apply{shader=LinearGradient(0f,0f,384f,512f,intArrayOf(Color.rgb(45,77,92),Color.rgb(214,166,95),Color.rgb(37,49,45)),null,Shader.TileMode.CLAMP)}
        Canvas(this).drawRect(0f,0f,384f,512f,p)
    }
    @Suppress("UNCHECKED_CAST")
    private fun installFixture(activity:MainActivity,bitmap:Bitmap,parameters:EditorState=EditorState()){
        val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity) as EditorViewModel
        val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
        // Startup recovery/restore is asynchronous. Do not race synthetic fixture adoption
        // against its later continuation (which can rebind controls mid-layout assertions).
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        var ready=false
        for(attempt in 0 until 1000){
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if(model.state.value.startupReady && model.state.value.busy==null && (operation.get(model) as? kotlinx.coroutines.Job)?.isCompleted==true){ready=true;break}
            Thread.sleep(2)
        }
        check(ready){"Editor startup did not settle before layout fixture"}
        model.localDetailEnabled=false // Explicit disabled-path baseline; isolated candidate defaults on.
        check(!model.localDetailEnabled){"Layout baseline must not enable experimental detail"}
        mutable.value=EditorUiState(photo=PhotoSource(File(activity.cacheDir,"layout-fixture"),"长文件名".repeat(40)+".dng","image/x-adobe-dng",4096,3072,100),
            original=bitmap,rendered=bitmap,parameters=parameters,renderedParameters=parameters,engineUnavailable=null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun portraitAndLandscapeKeepPhotoAndOneSelectedControlVisible(){
        val target=File("build/ui-host-renders").apply{mkdirs()}
        for((w,h) in listOf(320 to 640,360 to 780,740 to 320))for(scale in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
                val activity=controller.get();val bitmap=fixture();installFixture(activity,bitmap)
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                for(category in listOf("胶片","调整","光效","颗粒","显影","印相","扫描")){
                    all(root).filterIsInstance<Button>().first{it.text.toString()==category}.performClick()
                    layout(root,w,h)
                    shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                    layout(root,w,h)
                    val photo=all(root).filterIsInstance<PhotoView>().single()
                    assertTrue("Photo collapsed: ${w}x${h} font$scale $category height=${photo.height}",photo.height>=120)
                    assertTrue("Photo width collapsed",photo.width>=160)
                    if(w<=h)assertTrue("Portrait keeps only one scalar",all(root).filterIsInstance<android.widget.SeekBar>().size<=1)
                    else{
                        val vertical=all(root).filterIsInstance<android.widget.ScrollView>().single{it.tag=="landscape-parameters-scroll"}
                        assertTrue("Current category has titled sections",(vertical.getChildAt(0) as ViewGroup).childCount>0)
                        assertTrue(all(vertical).filterIsInstance<android.widget.SeekBar>().size>1)
                        assertFalse("No secondary landscape tabs",all(vertical).any{(it.tag as? String)?.startsWith("strip:tools:")==true})
                    }
                    for(label in all(root).filterIsInstance<TextView>().filter{it.visibility==View.VISIBLE&&it.layout!=null}){
                        for(line in 0 until label.layout.lineCount)assertEquals("Truncated ${label.text}",0,label.layout.getEllipsisCount(line))
                    }
                    val host=MainActivity::class.java.getDeclaredField("toolHost").apply{isAccessible=true}.get(activity) as ViewGroup
                    assertFalse("Main parameter must not live in a vertical scroll slot",all(host).any{it is android.widget.ScrollView})
                    if(scale==1f){
                        for(v in all(host).filter{it.visibility==View.VISIBLE}){
                            if(v.parent is android.widget.LinearLayout && (v.parent as View).height>0)
                                assertTrue("Control clipped vertically: $category ${v.javaClass.simpleName}",v.top>=0 && v.bottom<=(v.parent as View).height)
                        }
                    }
                    val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(output))
                    File(target,"${w}x${h}-font${scale.toInt()}-$category.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)};output.recycle()
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun correctedTerminologyAndExposureUnitsFitPortraitAndLargeLandscape() {
        val target=File("build/ui-host-renders").apply{mkdirs()}
        val selections=mapOf("adjust" to listOf("brightness","saturation","contrast","warmth"),
            "film" to listOf("highlightProtectEv"),"develop" to listOf("dirAmount"),
            "grain" to listOf("grainDyeCloud"),"print" to listOf("paperGammaSlow","paperDeveloperExhaustion","normalizePrintExposure","preflash"))
        try {
            for((w,h) in listOf(320 to 640,740 to 320)) for(scale in listOf(1f,2f)) {
                RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(scale)
                Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                    val a=controller.get();installFixture(a,fixture())
                    val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    for((group,ids) in selections)for(id in ids) {
                        MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}.set(a,group)
                        MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(a,id)
                        MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)
                        layout(root,w,h);shadowOf(android.os.Looper.getMainLooper()).idle()
                        var view:View=root
                        var dialog:android.app.AlertDialog?=null
                        if(scale>1f && w<=h) {
                            val host=MainActivity::class.java.getDeclaredField("toolHost").apply{isAccessible=true}.get(a) as ViewGroup
                            all(host).filterIsInstance<Button>().single{it.text.startsWith("调整")}.performClick()
                            dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                            shadowOf(android.os.Looper.getMainLooper()).idle()
                            view=dialog.window!!.decorView
                            view.measure(View.MeasureSpec.makeMeasureSpec(w-16,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h-16,View.MeasureSpec.AT_MOST))
                            view.layout(0,0,w-16,view.measuredHeight)
                        }
                        if(w>h){
                            val section=all(root).single{it.tag=="parameter-section:$id"}
                            val vertical=all(root).filterIsInstance<android.widget.ScrollView>().single{it.tag=="landscape-parameters-scroll"}
                            vertical.scrollTo(0,section.top);layout(root,w,h)
                            view=section
                            val visible=android.graphics.Rect();assertTrue("Target section reachable $id",section.getGlobalVisibleRect(visible));assertTrue(visible.height()>0)
                        }
                        for(label in all(view).filterIsInstance<TextView>().filter{it.visibility==View.VISIBLE&&it.layout!=null}) {
                            for(line in 0 until label.layout.lineCount)assertEquals("Truncated terminology $id font$scale: ${label.text}",0,label.layout.getEllipsisCount(line))
                        }
                        if(id=="normalizePrintExposure") assertTrue("Selected toggle remains available",all(view).any{it is dev.filmroom.ui.SettledChoiceSelector})
                        else assertTrue("One selected scalar remains available $id",all(view).any{it is android.widget.SeekBar})
                        if(id=="brightness")assertTrue(all(view).filterIsInstance<TextView>().any{it.text.contains("档")})
                        if(id=="paperGammaSlow"||id=="brightness") {
                            val out=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(out))
                            File(target,"terminology-${w}x${h}-font${scale.toInt()}-$id.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)};out.recycle()
                        }
                        dialog?.dismiss()
                        shadowOf(android.os.Looper.getMainLooper()).idle()
                    }
                }
            }
        } finally {RuntimeEnvironment.setFontScale(1f)}
    }

    @Test fun expandedLargeTextLandscapeToolRemainsReachable(){
        RuntimeEnvironment.setQualifiers("w740dp-h320dp-mdpi");RuntimeEnvironment.setFontScale(2f)
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val activity=controller.get();installFixture(activity,fixture())
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            all(root).filterIsInstance<Button>().first{it.text.toString()=="调整"}.performClick()
            layout(root,740,320);shadowOf(android.os.Looper.getMainLooper()).idle();layout(root,740,320)
            val vertical=all(root).filterIsInstance<android.widget.ScrollView>().single{it.tag=="landscape-parameters-scroll"}
            val sections=vertical.getChildAt(0) as ViewGroup
            assertTrue("Large landscape exposes all adjustment sections",sections.childCount>=5)
            assertFalse(all(root).filterIsInstance<Button>().any{it.text.toString()=="调整胶片曝光"})
            for(index in 0 until sections.childCount){
                val section=sections.getChildAt(index)
                vertical.scrollTo(0,section.top);layout(root,740,320)
                val slider=all(section).filterIsInstance<android.widget.SeekBar>().single()
                val rect=android.graphics.Rect();assertTrue(slider.getGlobalVisibleRect(rect));assertEquals(slider.height,rect.height());assertTrue(slider.height>=48)
            }
            val output=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(output))
            File("build/ui-host-renders/740x320-font2-expanded-adjust.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)};output.recycle()
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }

    @Test fun meteringRailRendersInPortraitLandscapeAndLargeTextSheet() {
        val target=File("build/ui-host-renders").apply{mkdirs()}
        try {
            for((w,h) in listOf(320 to 640,740 to 320)) for(scale in listOf(1f,2f)) {
                RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(scale)
                Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                    val a=controller.get();installFixture(a,fixture(),EditorState(autoExposure=true))
                    val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(a,"metering")
                    MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)
                    layout(root,w,h);shadowOf(android.os.Looper.getMainLooper()).idle()
                    var renderRoot:View=root
                    if(scale>1f && w<=h) {
                        all(root).filterIsInstance<Button>().first{it.text.toString()=="调整自动曝光"}.performClick()
                        renderRoot=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
                        renderRoot.measure(View.MeasureSpec.makeMeasureSpec(w-16,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h-16,View.MeasureSpec.AT_MOST))
                        renderRoot.layout(0,0,w-16,renderRoot.measuredHeight)
                    }
                    shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                    if(w>h){
                        val section=all(root).single{it.tag=="parameter-section:autoExposure"}
                        val vertical=all(root).filterIsInstance<android.widget.ScrollView>().single{it.tag=="landscape-parameters-scroll"}
                        vertical.scrollTo(0,section.top);layout(root,w,h)
                    }
                    val rail=all(renderRoot).filterIsInstance<dev.filmroom.ui.SettledChoiceSelector>().single()
                    assertTrue(rail.height>=48)
                    val visible=android.graphics.Rect()
                    assertTrue(rail.getGlobalVisibleRect(visible))
                    assertEquals("Selected control must be fully reachable: ${w}x${h} font$scale",rail.height,visible.height())
                    assertTrue("Metering description=${rail.contentDescription}",rail.contentDescription.contains("中央重点"))
                    for(label in all(rail).filterIsInstance<TextView>().filter{it.visibility==View.VISIBLE}) {
                        assertNotNull(label.layout)
                        for(line in 0 until label.layout.lineCount) assertEquals(0,label.layout.getEllipsisCount(line))
                    }
                    val out=Bitmap.createBitmap(renderRoot.width,renderRoot.height,Bitmap.Config.ARGB_8888)
                    renderRoot.draw(Canvas(out))
                    File(target,"${w}x${h}-font${scale.toInt()}-metering.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)}
                    out.recycle()
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun autoExposureCompoundChoiceRendersAtDeviceDensities() {
        val target=File("build/ui-host-renders").apply{mkdirs()}
        for(density in listOf(440,480)) for(widthDp in listOf(360,411)) for(index in listOf(0,3,7)) {
            RuntimeEnvironment.setQualifiers("w${widthDp}dp-h780dp-${density}dpi");RuntimeEnvironment.setFontScale(1f)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity=controller.get();installFixture(activity,fixture(),EditorState(autoExposure=index!=0,metering=if(index==7)"highlight_weighted"else "center_weighted"))
                MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(activity,"autoExposure")
                MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(activity)
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val factor=activity.resources.displayMetrics.density
                val w=(widthDp*factor).toInt();val h=(780*factor).toInt()
                layout(root,w,h)
                val selector=all(root).filterIsInstance<dev.filmroom.ui.SettledChoiceSelector>().single()
                val viewport=selector.getChildAt(1) as android.widget.HorizontalScrollView
                val strip=viewport.getChildAt(0) as ViewGroup
                    // Snapshot immediately, including the very first layout, without an extra traversal.
                    val item=strip.getChildAt(index) as TextView
                    val visible=android.graphics.Rect()
                    assertTrue(item.getGlobalVisibleRect(visible))
                    assertEquals("Whole selected pill visible",item.width,visible.width())
                    assertEquals(item.height,visible.height())
                    assertTrue(kotlin.math.abs(item.left+item.width/2-viewport.scrollX-viewport.width/2)<=1)
                    val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(output))
                    File(target,"autoExposure-${widthDp}dp-${density}dpi-$index.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)};output.recycle()
            }
        }
    }

    @Test fun renderSemanticVectorContactSheet() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val toolNames=mutableMapOf<Int,MutableList<String>>()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val a=c.get()
            val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}
            val base=MainActivity::class.java.getDeclaredMethod("controls").apply{isAccessible=true}
            val extra=MainActivity::class.java.getDeclaredMethod("extraControls").apply{isAccessible=true}
            for(category in listOf("film","adjust","light","grain","develop","print","scan")) {
                group.set(a,category)
                for(control in ((base.invoke(a) as List<*>)+(extra.invoke(a) as List<*>)).filterNotNull()) {
                    val icon=control.javaClass.getDeclaredMethod("getIcon").apply{isAccessible=true}.invoke(control) as Int
                    val name=control.javaClass.getDeclaredMethod("getLabel").apply{isAccessible=true}.invoke(control) as String
                    toolNames.getOrPut(icon){mutableListOf()}.add(name)
                }
            }
        }
        val fields=R.drawable::class.java.fields.filter{it.name.startsWith("ic_tool_")}.sortedBy{it.name}
        assertTrue(fields.size>=40)
        val bitmap=Bitmap.createBitmap(720,((fields.size+5)/6)*90,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(17,20,19))
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=11f;textAlign=Paint.Align.CENTER}
        fields.forEachIndexed { i,field ->
            val x=(i%6)*120;val y=(i/6)*90
            val drawable=context.getDrawable(field.getInt(null))!!
            drawable.setBounds(x+36,y+8,x+84,y+56);drawable.draw(canvas)
            val names=toolNames[field.getInt(null)] ?: listOf(field.name.removePrefix("ic_tool_"))
            names.take(2).forEachIndexed { line,name -> canvas.drawText(name,x+60f,y+70f+line*14,label) }
        }
        File("build/ui-host-renders").mkdirs()
        File("build/ui-host-renders/parameter-vector-families.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

}
