package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import dev.filmroom.ui.GlyphAlignedSectionTitleView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** Measures rendered alpha ink, not only the icon/view bounding box. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphAlignedSectionTitleHostTest {
    private fun verify(label:String,icon:Int,fontPx:Float,width:Int,wrapped:Boolean=false){
        val context=RuntimeEnvironment.getApplication()
        val size=(fontPx*1.15f).roundToInt()
        val title=GlyphAlignedSectionTitleView(context).apply{
            layoutDirection=View.LAYOUT_DIRECTION_LTR
            text=label;setTextSize(TypedValue.COMPLEX_UNIT_PX,fontPx);setTextColor(Color.WHITE)
            includeFontPadding=true;gravity=Gravity.CENTER_VERTICAL
            setHeadingIcon(context.getDrawable(icon)!!.mutate().apply{setTint(Color.WHITE)},size,6)
        }
        title.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        title.layout(0,0,width,title.measuredHeight)
        assertEquals("Resolved icon gutter",size+6,title.compoundPaddingLeft)
        if(wrapped)assertTrue("Fixture wraps",title.lineCount>1)
        val image=Bitmap.createBitmap(width,title.height,Bitmap.Config.ARGB_8888)
        try{
            title.draw(Canvas(image))
            fun ink(x0:Int,x1:Int,yLimit:Int):Rect{
                val bounds=Rect(width,title.height,-1,-1)
                for(y in 0 until yLimit.coerceAtMost(title.height))for(x in x0 until x1){
                    if(Color.alpha(image.getPixel(x,y))>=16){bounds.left=minOf(bounds.left,x);bounds.right=maxOf(bounds.right,x+1);bounds.top=minOf(bounds.top,y);bounds.bottom=maxOf(bounds.bottom,y+1)}
                }
                assertTrue("Visible ink $label $fontPx",bounds.right>bounds.left && bounds.bottom>bounds.top)
                return bounds
            }
            val iconInk=ink(0,size,title.height)
            val firstLineBottom=(title.baseline+title.paint.fontMetrics.descent).roundToInt()+1
            val textInk=ink(title.compoundPaddingLeft,width,firstLineBottom)
            assertTrue("Actual icon/text ink centers align: $label font=$fontPx icon=$iconInk text=$textInk",
                abs(iconInk.exactCenterY()-textInk.exactCenterY())<=.75f)
        }finally{image.recycle()}
    }
    @Test fun actualFourLanguageGlyphsAndDifferentIconsAlignAtNormalAndLargeText(){
        val labels=listOf("构图","画幅","胶片曝光","亮度","Composition","Film format","Exposure","Brightness","構図","フォーマット","露出","明るさ","구도","필름 포맷","노출","밝기")
        val icons=listOf(R.drawable.ic_composition,R.drawable.ic_tool_radius,R.drawable.ic_tool_exposure,R.drawable.ic_brightness)
        for(font in listOf(14f,28f,42f))for((index,label)in labels.withIndex())verify(label,icons[index%icons.size],font,600)
    }
    @Test fun wrappingAlignsWithFirstVisibleLineRatherThanWholeHeadingBox(){
        verify("Composition with a long localized heading",R.drawable.ic_composition,28f,180,true)
        verify("照片构图以及其他说明文字",R.drawable.ic_tool_radius,28f,180,true)
    }
}
