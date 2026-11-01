package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.ui.WrappingActionLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host framework measurements only, not a screenshot or Android device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w320dp-h740dp-mdpi")
class ResponsiveLayoutHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)
        (0 until v.childCount).flatMap{descendants(v.getChildAt(it))} else emptyList())
    private fun layout(view:View,width:Int,height:Int?=null) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height ?: 0,if(height==null)View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
    }
    @Test fun actionFlowWrapsWholeControlsAndRetainsAllLabels() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val flow=WrappingActionLayout(context,6)
        for (name in listOf("撤销","重做","看效果","信息")) {
            flow.addView(Button(context).apply{text=name;textSize=28f;setPadding(8,10,8,10)})
        }
        layout(flow,296)
        val buttons=descendants(flow).filterIsInstance<Button>()
        assertEquals(4,buttons.size)
        for (button in buttons) {
            assertTrue(button.left>=0 && button.right<=flow.width)
            assertTrue(button.bottom<=flow.height)
            assertEquals("Short action text must remain on one line",1,button.lineCount)
            assertEquals(0,button.layout.getEllipsisCount(0))
        }
        for (a in buttons.indices) for(b in a+1 until buttons.size) {
            val x=buttons[a];val y=buttons[b]
            assertTrue("Buttons must not overlap",x.right<=y.left || y.right<=x.left || x.bottom<=y.top || y.bottom<=x.top)
        }
    }
    @Test fun actionFlowUsesAnotherRowInsteadOfClippingAtLargeTextSize() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val flow=WrappingActionLayout(context,6)
        repeat(4){flow.addView(Button(context).apply{text="看效果";textSize=32f;setPadding(12,10,12,10)})}
        layout(flow,280)
        assertTrue(flow.getChildAt(3).top>flow.getChildAt(0).top)
        assertTrue(flow.height>=flow.getChildAt(3).bottom)
    }
    @Test fun allMainActionButtonsAndStockCardsUseContentHeightAndNoEllipsis() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {controller->
            val activity=controller.get()
            val all=descendants(activity.window.decorView)
            val labels=setOf("导入","导出","导入照片","胶片说明")
            val buttons=all.filterIsInstance<Button>().filter{it.text.toString() in labels || it.text.contains("Portra") || it.text.contains("Gold") || it.text.contains("Fujicolor")}
            assertTrue(buttons.size>=7)
            for(button in buttons) {
                assertEquals("Fixed height clips enlarged text: ${button.text}",ViewGroup.LayoutParams.WRAP_CONTENT,button.layoutParams.height)
                assertNull("No ellipsis: ${button.text}",button.ellipsize)
            }
            for(label in listOf("撤销","重做","对比原片","照片信息"))assertTrue(all.any{it.contentDescription?.toString()==label})
        }
    }
    @Test fun longFilenameDoesNotExpandToolbarOrCoverPhoto(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val activity=controller.get()
            val source=dev.filmroom.io.PhotoSource(java.io.File(activity.cacheDir,"fixture.raw"),"长文件名".repeat(40)+".dng","image/x-adobe-dng",4096,3072,100)
            MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}
                .invoke(activity,EditorUiState(photo=source))
            val all=descendants(activity.window.decorView)
            assertTrue(all.any{it.contentDescription?.toString()=="照片信息"})
            assertFalse(all.filterIsInstance<TextView>().any{it.text.toString()==source.name})
            val welcome=all.filterIsInstance<TextView>().first{it.text.toString()=="把照片带进暗房"}
            assertEquals("Hidden welcome scroll must not intercept photo gestures",View.GONE,(welcome.parent.parent as View).visibility)
        }
    }
    @Test @Config(qualifiers="w640dp-h360dp-mdpi") fun landscapeUsesSideBySidePhotoAndTools(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val all=descendants(controller.get().window.decorView)
            val photo=all.filterIsInstance<dev.filmroom.ui.PhotoView>().single()
            val imageColumn=photo.parent.parent as android.widget.LinearLayout
            val body=imageColumn.parent as android.widget.LinearLayout
            assertEquals(android.widget.LinearLayout.HORIZONTAL,body.orientation)
        }
    }
    @Test fun longPaperChoiceIsNotSingleLineOrEllipsized() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use {controller->
            val activity=controller.get()
            val method=MainActivity::class.java.getDeclaredMethod("choiceAdapter",List::class.java,Boolean::class.javaPrimitiveType)
            method.isAccessible=true
            val adapter=method.invoke(activity,listOf("Crystal Archive II · 彩色相纸"),true) as android.widget.ListAdapter
            val parent=android.widget.ListView(activity)
            val label=adapter.getView(0,null,parent) as TextView
            assertNull(label.ellipsize)
            assertTrue(label.maxLines>1)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,label.layoutParams.height)
        }
    }
}
