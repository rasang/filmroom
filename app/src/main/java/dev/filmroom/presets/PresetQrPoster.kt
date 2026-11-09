package dev.filmroom.presets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
import org.json.JSONObject

/** A local share artifact. The existing QR pixels and payload are never changed or filtered. */
internal object PresetQrPoster {
    private val background = Color.rgb(25,25,25)
    private val ink = Color.rgb(245,242,235)
    private val muted = Color.rgb(184,184,180)
    private val accent = Color.rgb(239,166,79)
    internal data class Copy(val action:String,val instruction:String,val noFilm:String,val noPaper:String)
    internal fun copy(language:String) = when(language) {
        "en" -> Copy("Scan to import recipe", "Use the scanner on Filmroom’s Recipes page", "No film", "No paper")
        "ja" -> Copy("スキャンしてレシピを読み込む", "Filmroomのレシピ画面からスキャン", "フィルムなし", "印画紙なし")
        "ko" -> Copy("스캔하여 레시피 가져오기", "Filmroom 레시피 페이지의 스캔 기능을 사용하세요", "필름 없음", "인화지 없음")
        else -> Copy("扫码导入配方", "使用 Filmroom 配方页的扫码功能", "无胶卷", "无相纸")
    }
    private fun layout(text:String,size:Float,width:Int,color:Int,bold:Boolean=false):StaticLayout {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize=size;this.color=color
            typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        }
        return StaticLayout.Builder.obtain(text,0,text.length,paint,width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
    }
    private fun fit(text:String,width:Int,maxSize:Float,minSize:Float,lines:Int,color:Int,bold:Boolean=false):StaticLayout {
        var size=maxSize;var result=layout(text,size,width,color,bold)
        while(result.lineCount>lines && size>minSize) { size-=2f;result=layout(text,size,width,color,bold) }
        // Never truncate the recipe name or a stock model. Extra lines grow the canvas.
        return result
    }
    fun render(context:Context,qr:Bitmap,name:String,parameters:EditorState):Bitmap {
        val words=copy(L10n.language(context))
        val catalog=JSONObject(context.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        fun stock(id:String,none:String):String {
            if(id=="none")return none
            for(i in 0 until catalog.length()) { val row=catalog.getJSONObject(i);if(row.getString("id")==id)return row.getString("name") }
            throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)
        }
        return render(context,qr,name,stock(parameters.filmStock,words.noFilm),stock(parameters.paperStock,words.noPaper),words)
    }
    internal fun render(context:Context,qr:Bitmap,name:String,film:String,paper:String,words:Copy):Bitmap {
        require(!qr.isRecycled && qr.width==qr.height && qr.width<=1480)
        val width=maxOf(1080,qr.width+160)
        val contentWidth=width-160
        val title=fit(name,contentWidth,76f,40f,3,accent,true)
        val labels=listOf(film,paper).map{fit(it,contentWidth-32,30f,24f,2,ink)}
        val action=fit(words.action,contentWidth,48f,32f,2,ink,true)
        val instruction=fit(words.instruction,contentWidth,28f,24f,2,muted)
        val qrTop=232+title.height+66+labels.sumOf{it.height+30}+36
        val height=qrTop+qr.height+54+action.height+24+instruction.height+132
        require(width.toLong()*height<=2048L*2048L)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.drawColor(background)
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            fun line(y:Float){p.color=Color.rgb(83,83,80);p.strokeWidth=1.5f;canvas.drawLine(64f,y,width-64f,y,p)}
            fun block(l:StaticLayout,x:Float,y:Float){canvas.save();canvas.translate(x,y);l.draw(canvas);canvas.restore()}
            val logo=checkNotNull(context.resources.getDrawableForDensity(R.mipmap.ic_launcher,640,context.theme))
            logo.setBounds(80,56,152,128);logo.draw(canvas)
            block(layout("Filmroom",54f,contentWidth-100,ink,true),176f,60f);line(166f)
            block(title,80f,218f)
            block(layout("FILM RECIPE",24f,contentWidth,muted),80f,232f+title.height)
            var y=284f+title.height
            for(label in labels){
                p.color=Color.rgb(88,88,84);p.style=Paint.Style.STROKE;p.strokeWidth=1.5f
                canvas.drawRect(80f,y-10f,width-80f,y+label.height+12f,p);p.style=Paint.Style.FILL
                block(label,96f,y);y+=label.height+30f
            }
            val qrLeft=(width-qr.width)/2
            // QR already includes a four-module quiet zone. No draw ever touches its square.
            canvas.drawBitmap(qr,qrLeft.toFloat(),qrTop.toFloat(),Paint().apply{isFilterBitmap=false;isAntiAlias=false})
            p.color=accent;p.strokeWidth=3f
            val left=qrLeft-16f;val top=qrTop-16f;val right=qrLeft+qr.width+16f;val bottom=qrTop+qr.height+16f
            for((x,dx) in listOf(left to 1f,right to -1f))for((cy,dy)in listOf(top to 1f,bottom to -1f)){
                canvas.drawLine(x,cy,x+28*dx,cy,p);canvas.drawLine(x,cy,x,cy+28*dy,p)
            }
            val actionY=qrTop+qr.height+54f
            block(action,80f,actionY);block(instruction,80f,actionY+action.height+24)
            p.color=accent;canvas.drawCircle(82f,height-64f,7f,p)
            block(layout("FILMROOM  /  RECIPE SHARE",20f,contentWidth,muted),104f,height-77f)
            return bitmap
        } catch(error:Throwable){bitmap.recycle();throw error}
    }
}
