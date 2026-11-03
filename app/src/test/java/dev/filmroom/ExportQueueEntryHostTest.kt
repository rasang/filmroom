package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.ui.ExportQueueEntry
import dev.filmroom.ui.ExportQueueSummary
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportQueueEntryHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(v:View,w:Int,h:Int) {v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    @Test fun summaryCountsOnlyQueuedAndRunningNotPausedOrFailed() {
        val jobs=ExportJobState.entries.map { ExportJob(it.name,0,EditorState(),1,1,ExportFormat.PNG,95,it) }
        assertEquals(ExportQueueSummary(3,true,true),ExportQueueSummary.from(jobs))
        assertEquals(ExportQueueSummary(2,false,true),ExportQueueSummary.from(jobs.filter{it.state!=ExportJobState.RUNNING}))
        assertEquals(ExportQueueSummary(0,false),ExportQueueSummary.from(emptyList()))
    }
    @Test fun visibleBesideExportStableAtEveryCountAndFontAndOpensList() {
        val target=File("build/ui-host-renders").apply{mkdirs()}
        try {for((w,h) in listOf(320 to 640,360 to 780,411 to 891,740 to 320))for(scale in listOf(1f,2f)) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val a=controller.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val entry=all(root).filterIsInstance<ExportQueueEntry>().single()
                val export=all(root).filterIsInstance<Button>().single{it.contentDescription=="导出"}
                assertEquals("Export is always icon-only","",export.text.toString())
                assertSame(export.parent,entry.parent)
                var dimensions:Pair<Int,Int>?=null
                for(count in listOf(0,1,9,10,99,100,10000)) {
                    entry.bind(ExportQueueSummary(count,count>0));layout(root,w,h)
                    assertTrue(entry.isShown);assertTrue(entry.width>=if(w<384)44 else 48);assertTrue(entry.height>=48)
                    assertTrue(entry.left>=export.right)
                    val group=entry.parent as View
                    assertTrue("export group clipped at $w font $scale",entry.right<=group.width)
                    assertTrue(group.width<=w-24)
                    assertEquals("Queue is icon-only", "", entry.text.toString())
                    val toolbar=group.parent as ViewGroup
                    assertTrue("Toolbar must stay one row",toolbar.height in 64..80)
                    for(control in all(toolbar).filterIsInstance<Button>()) {
                        val position=IntArray(2);control.getLocationInWindow(position)
                        assertTrue("Target clipped horizontally",position[0]>=0 && position[0]+control.width<=w)
                        assertTrue("Adaptive touch width ${control.contentDescription}: ${control.width} at $w font$scale",control.width>=if(w<384)44 else 48)
                        assertTrue("48dp touch height",control.height>=48)
                        val queuePosition=IntArray(2);entry.getLocationInWindow(queuePosition)
                        assertTrue("All toolbar actions share one row",kotlin.math.abs(queuePosition[1]+entry.height/2-position[1]-control.height/2)<=1)
                    }
                    val current=entry.width to entry.height
                    if(dimensions!=null)assertEquals(dimensions,current)
                    dimensions=current
                }
                entry.bind(ExportQueueSummary(3,true));layout(root,w,h)
                val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(output))
                File(target,"queue-${w}x${h}-font${scale.toInt()}.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)};output.recycle()
                assertTrue(entry.contentDescription.contains("3 项待完成"))
                entry.performClick()
                assertEquals(ExportQueueActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
            }
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }

    @Test fun iconToolbarAtScreenshotDensityKeepsAllSevenActionsOnOneRow() {
        val target=File("build/ui-host-renders").apply{mkdirs()}
        try {for(widthDp in listOf(320,360,411))for(scale in listOf(1f,2f)) {
            RuntimeEnvironment.setQualifiers("w${widthDp}dp-h891dp-440dpi");RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
                val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val density=c.get().resources.displayMetrics.density
                val width=(widthDp*density).toInt();val height=(891*density).toInt()
                val entry=all(root).filterIsInstance<ExportQueueEntry>().single()
                for(count in listOf(0,1,100)) {
                    entry.bind(ExportQueueSummary(count,count>0));layout(root,width,height)
                    val toolbar=(entry.parent as View).parent as ViewGroup
                    val actions=all(toolbar).filterIsInstance<Button>();assertEquals(7,actions.size)
                    val y=IntArray(2).also{entry.getLocationInWindow(it)}[1]+entry.height/2
                    for(action in actions) {
                        val pos=IntArray(2);action.getLocationInWindow(pos)
                        assertTrue(kotlin.math.abs(y-pos[1]-action.height/2)<=1);assertTrue(pos[0]>=0&&pos[0]+action.width<=width)
                        assertTrue("Width ${action.contentDescription} ${action.width} density$density at $widthDp font$scale",action.width>=((if(widthDp<384)44 else 48)*density).toInt());assertTrue(action.height>=(48*density).toInt())
                    }
                    val out=Bitmap.createBitmap(width,toolbar.height,Bitmap.Config.ARGB_8888);toolbar.draw(Canvas(out))
                    File(target,"queue-icon-${widthDp}dp-440dpi-font${scale.toInt()}-count$count.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)};out.recycle()
                }
            }
        }}finally{RuntimeEnvironment.setFontScale(1f)}
    }
    @Test fun unifiedQueueGlyphNativeFrameContactSheet() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-480dpi");RuntimeEnvironment.setFontScale(1f)
        val out=Bitmap.createBitmap(920,940,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(out);canvas.drawColor(android.graphics.Color.rgb(17,20,19))
        val labels=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.rgb(184,187,178);textSize=16f;textAlign=android.graphics.Paint.Align.CENTER
        }
        val counts=listOf(0,1,12,99,100)
        for(row in 0..4)for((column,count) in counts.withIndex()) {
            val entry=ExportQueueEntry(RuntimeEnvironment.getApplication())
            entry.bind(ExportQueueSummary(count,row>0&&count>0))
            layout(entry,144,144)
            ExportQueueEntry::class.java.getDeclaredField("motionPhase").apply{isAccessible=true}.setFloat(entry,(row-1).coerceAtLeast(0)/4f)
            canvas.save();canvas.translate(column*184f+20,row*188f+8);entry.draw(canvas);canvas.restore()
            canvas.drawText(if(row==0)"等待 · $count" else "处理帧 $row · $count",column*184f+92,row*188f+178,labels)
        }
        val path=File("build/ui-host-renders/queue-unified-native-contact-sheet.png");path.parentFile!!.mkdirs()
        path.outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)};out.recycle()
    }

    @Test fun rearHighlightTravelsAlongWhiteRailWithoutChangingFront() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-480dpi");RuntimeEnvironment.setFontScale(1f)
        val entry=ExportQueueEntry(RuntimeEnvironment.getApplication())
        entry.bind(ExportQueueSummary(2,true));layout(entry,144,144)
        fun frame(phase:Float):Bitmap {
            ExportQueueEntry::class.java.getDeclaredField("motionPhase").apply{isAccessible=true}.setFloat(entry,phase)
            return Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888).also{entry.draw(Canvas(it))}
        }
        val frames=listOf(0f,.25f,.5f,.75f,1f).map(::frame)
        val white=dev.filmroom.ui.FilmroomPalette.ink
        val railSamples=(48..99).map{it to 36}+(45..87).map{108 to it}
        val signatures=frames.take(4).map { bitmap -> railSamples.map{(x,y)->bitmap.getPixel(x,y)} }
        assertEquals("All four phases move the gradient",4,signatures.toSet().size)
        for(signature in signatures) {
            assertTrue("White rail remains",white in signature)
            assertTrue("Many intermediate shades form a soft head/tail",signature.distinct().size>12)
            assertTrue("A strong amber head remains visible",signature.any{android.graphics.Color.green(it)<180})
            assertTrue("Gradient includes soft pale-yellow tail",signature.any{android.graphics.Color.green(it) in 205..232})
        }
        // Adjacent pixels on each straight rail have no hard white/yellow cut.
        for(bitmap in frames.take(4)) {
            for(x in 49..99)assertTrue(kotlin.math.abs(android.graphics.Color.green(bitmap.getPixel(x,36))-android.graphics.Color.green(bitmap.getPixel(x-1,36)))<=16)
            for(y in 46..87)assertTrue(kotlin.math.abs(android.graphics.Color.green(bitmap.getPixel(108,y))-android.graphics.Color.green(bitmap.getPixel(108,y-1)))<=16)
        }
        for(y in 48..111)for(x in 30..99)for(frame in frames.drop(1))assertEquals(frames[0].getPixel(x,y),frame.getPixel(x,y))
        for(y in 0..143)for(x in 0..143)assertEquals("Loop closes without a jump",frames[0].getPixel(x,y),frames[4].getPixel(x,y))
        entry.bind(ExportQueueSummary(2,false))
        val waiting=frame(.25f);assertEquals(white,waiting.getPixel(57,36));assertEquals(white,waiting.getPixel(108,75))
        (frames+waiting).forEach{it.recycle()}
    }

    @Test fun unreadCompletionDotIsSeparateFromPendingCountAndAccessible() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-480dpi");RuntimeEnvironment.setFontScale(1f)
        val entry=ExportQueueEntry(RuntimeEnvironment.getApplication());layout(entry,144,144)
        entry.bind(ExportQueueSummary(0,false,true))
        assertTrue(entry.contentDescription.contains("有新完成的导出"));assertFalse(entry.animating)
        val unseen=Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888);entry.draw(Canvas(unseen))
        assertEquals(android.graphics.Color.rgb(229,163,93),unseen.getPixel(117,24))
        entry.bind(ExportQueueSummary(0,false,false))
        val seen=Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888);entry.draw(Canvas(seen))
        assertFalse(entry.contentDescription.contains("有新完成的导出"))
        assertNotEquals(unseen.getPixel(117,24),seen.getPixel(117,24))
        assertEquals(144,entry.width);assertEquals(144,entry.height)
        unseen.recycle();seen.recycle()
    }

    @Test fun cleanUnifiedQueuePreview() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-440dpi");RuntimeEnvironment.setFontScale(1f)
        val folder=File("build/ui-host-renders/queue-unified-preview").apply{mkdirs()}
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val toolbarEntry=all(root).filterIsInstance<ExportQueueEntry>().single()
            toolbarEntry.bind(ExportQueueSummary(1,true));toolbarEntry.setForeground(false)
            layout(root,990,2145)
            val toolbar=(toolbarEntry.parent as View).parent as ViewGroup
            all(toolbar).filterIsInstance<Button>().single{it.contentDescription=="导出"}.apply{isEnabled=true;alpha=1f}
            for(frame in 0 until 36) {
                val out=Bitmap.createBitmap(990,520,Bitmap.Config.ARGB_8888);val canvas=Canvas(out)
                canvas.drawColor(android.graphics.Color.rgb(17,20,19))
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color=android.graphics.Color.rgb(244,241,233);textSize=28f
                }
                canvas.drawText("导出 · 任务队列",24f,42f,paint)
                ExportQueueEntry::class.java.getDeclaredField("motionPhase").apply{isAccessible=true}.setFloat(toolbarEntry,frame/36f)
                canvas.save();canvas.translate(0f,65f);toolbar.draw(canvas);canvas.restore()
                for((column,state) in listOf(ExportQueueSummary(0,false),ExportQueueSummary(2,false),ExportQueueSummary(2,true)).withIndex()) {
                    val entry=ExportQueueEntry(c.get());entry.bind(state);layout(entry,132,132)
                    ExportQueueEntry::class.java.getDeclaredField("motionPhase").apply{isAccessible=true}.setFloat(entry,frame/36f)
                    canvas.save();canvas.translate(column*330f+66,260f);canvas.scale(1.5f,1.5f);entry.draw(canvas);canvas.restore()
                    paint.textAlign=android.graphics.Paint.Align.CENTER;paint.textSize=24f
                    canvas.drawText(listOf("空闲","等待中","处理中")[column],column*330f+165,494f,paint)
                }
                File(folder,"frame-%02d.png".format(frame)).outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(frame==0)File(folder,"队列图标预览.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)}
                out.recycle()
            }
        }
    }

    @Test fun unreadDotNativeToolbarPreviews() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-440dpi");RuntimeEnvironment.setFontScale(1f)
        val target=File("build/ui-host-renders/unread-dot-final").apply{mkdirs()}
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val root=controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val entry=all(root).filterIsInstance<ExportQueueEntry>().single()
            layout(root,990,2145)
            val toolbar=(entry.parent as View).parent as ViewGroup
            all(toolbar).filterIsInstance<Button>().single{it.contentDescription=="导出"}.apply{isEnabled=true;alpha=1f}
            for((name,state) in listOf("completed-unread" to ExportQueueSummary(0,false,true),"running-and-unread" to ExportQueueSummary(1,true,true))) {
                entry.bind(state);entry.setForeground(false)
                ExportQueueEntry::class.java.getDeclaredField("motionPhase").apply{isAccessible=true}.setFloat(entry,.25f)
                layout(root,990,2145)
                assertTrue(entry.contentDescription.contains("有新完成的导出"))
                assertEquals(121,entry.width);assertEquals(132,entry.height)
                val out=Bitmap.createBitmap(990,toolbar.height,Bitmap.Config.ARGB_8888);toolbar.draw(Canvas(out))
                File(target,"$name-360dp-440dpi.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)};out.recycle()
            }
        }
    }

    @Test fun animationStopsForWaitingPausedBackgroundAndDetach() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val entry=all(controller.get().findViewById(android.R.id.content)).filterIsInstance<ExportQueueEntry>().single()
            entry.setForeground(true);entry.bind(ExportQueueSummary(3,true));assertTrue(entry.animating)
            entry.visibility=View.GONE;assertFalse(entry.animating)
            entry.visibility=View.VISIBLE;assertTrue(entry.animating)
            entry.bind(ExportQueueSummary(3,false));assertFalse(entry.animating)
            entry.bind(ExportQueueSummary(1,true));assertTrue(entry.animating)
            entry.setForeground(false);assertFalse(entry.animating)
            entry.setForeground(true);assertTrue(entry.animating)
            (entry.parent as ViewGroup).removeView(entry);assertFalse(entry.animating)
        }
    }
}
