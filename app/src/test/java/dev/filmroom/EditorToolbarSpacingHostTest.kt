package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.ui.ExportQueueEntry
import dev.filmroom.ui.ExportQueueSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/** Actual Android view measurements on the host, not device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class EditorToolbarSpacingHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)
        (0 until v.childCount).flatMap{all(v.getChildAt(it))} else emptyList())

    @Test fun groupedWhitespaceNeverWrapsHidesOrOverlapsAccessibleTargets() {
        try {
            for(dpi in listOf(160,440)) for(scale in listOf(1f,1.3f,2f))
                for((width,height) in listOf(308 to 740,320 to 740,360 to 780,393 to 852,411 to 891,740 to 320)) {
                    RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${dpi}dpi")
                    RuntimeEnvironment.setFontScale(scale)
                    Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                        val a=controller.get()
                        val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        val queue=all(root).filterIsInstance<ExportQueueEntry>().single()
                        val toolbar=(queue.parent as View).parent as ViewGroup
                        val density=a.resources.displayMetrics.density
                        fun dp(n:Int)=(n*density).roundToInt()
                        // Repeated measure catches stale compact labels and margins after resizing.
                        for(w in listOf(width,320,width)) for(count in listOf(0,3,100)) {
                            queue.bind(ExportQueueSummary(count,count>0,true))
                            root.measure(View.MeasureSpec.makeMeasureSpec(dp(w),View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(dp(height),View.MeasureSpec.EXACTLY))
                            root.layout(0,0,root.measuredWidth,root.measuredHeight)
                            val actions=all(toolbar).filterIsInstance<Button>()
                            assertEquals("Import, undo, redo, reset, export, queue, settings remain reachable",7,actions.size)
                            val target=if(dp(w)>=dp(384))dp(48) else dp(44)
                            val side=minOf(dp(if(w>=600)16 else if(w>=384)12 else if(w>=344)8 else 4),((dp(w)-target*7)/2).coerceAtLeast(0))
                            val available=dp(w)-side*2
                            assertTrue(actions.any{it.contentDescription=="恢复预设"})
                            assertTrue(actions.any{it.contentDescription=="设置"})
                            val positions=actions.map { action ->
                                assertTrue(action.isShown)
                                assertTrue("Compact targets retain the approved 44dp floor; normal targets48dp",action.width>=target-1)
                                assertTrue(action.height>=dp(48)-1)
                                assertFalse(action.contentDescription.isNullOrBlank())
                                if(action.text.isNotEmpty()){
                                    assertEquals("Visible toolbar labels never wrap",1,action.lineCount)
                                    assertEquals(0,action.layout.getEllipsisCount(0))
                                    assertTrue("Toolbar label is not clipped",action.layout.getLineWidth(0)<=action.width-action.compoundPaddingLeft-action.compoundPaddingRight+1)
                                }
                                val pos=IntArray(2);action.getLocationInWindow(pos)
                                assertTrue("Clipped $w/$dpi/$scale: ${action.contentDescription}",pos[0]>=0 && pos[0]+action.width<=dp(w))
                                Triple(pos[0],pos[1]+action.height/2,action.width)
                            }
                            assertTrue("All seven targets stay on one row",positions.map{it.second}.max()-positions.map{it.second}.min()<=1)
                            val gaps=positions.zipWithNext{left,right->right.first-left.first-left.third}
                            assertTrue("No overlapping touch targets: $gaps",gaps.all{it>=0})
                            val spare=(available-target*7).coerceAtLeast(0)
                            val inner=minOf(dp(4),spare/12)
                            val group=minOf(dp(16),(spare-inner*3)/3)
                            val expected=intArrayOf(group,inner,inner,group,inner,group)
                            expected[3]+=spare-expected.sum()
                            gaps.forEachIndexed{i,value->assertTrue("Group spacing $w/$dpi expected=${expected.toList()} actual=$gaps",kotlin.math.abs(value-expected[i])<=1)}
                            assertEquals(side,positions.first().first)
                            assertTrue(kotlin.math.abs(dp(w)-positions.last().first-positions.last().third-side)<=1)
                            assertTrue("Group boundaries are at least as clear as group internals",gaps[0]>=gaps[1] && gaps[3]>=gaps[2] && gaps[5]>=gaps[4])
                            actions.filter{it!==queue}.forEach{
                                assertEquals("Consistent visual glyph width",dp(22),it.compoundDrawables[0].bounds.width())
                                assertEquals("Toolbar actions use stable semantic icons", "",it.text.toString())
                                assertFalse(it.tooltipText.isNullOrBlank())
                            }
                            val export=actions.single{it.contentDescription=="导出"}
                            assertEquals("",export.text.toString())
                            assertNotNull(export.compoundDrawables[0])
                            assertEquals("导出",export.tooltipText)
                            assertSame(export.parent,queue.parent)
                        }
                    }
                }
        } finally {RuntimeEnvironment.setFontScale(1f)}
    }
}
