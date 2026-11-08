package dev.filmroom.effects

import android.graphics.*
import kotlin.math.*

/** Native one-time raster assets, not HTML or a WebView. Canvas leak commands match paintLeak(150). */
internal object EffectTextures {
    fun marks(width:Int,height:Int,sourceAspect:Float):Bitmap {
        val g=EffectGeometry.marks(width,height,sourceAspect)
        // Rasterize once per viewport, capped for extreme display sizes. Geometry remains logical.
        val scale=min(2f,2048f/max(g.width,g.height))
        val b=Bitmap.createBitmap(ceil(g.width*scale).toInt().coerceAtLeast(1),ceil(g.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.scale(b.width/g.width,b.height/g.height)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb((.52*255).roundToInt(),12,15,10);style=Paint.Style.STROKE;strokeWidth=g.stroke}
        val cx=g.width/2;val cy=g.height/2
        val left=cx-g.frameWidth/2;val right=cx+g.frameWidth/2
        val top=cy-g.frameHeight/2;val bottom=cy+g.frameHeight/2
        val path=Path()
        for(v in arrayOf(floatArrayOf(left,top,1f,1f),floatArrayOf(right,top,-1f,1f),floatArrayOf(left,bottom,1f,-1f),floatArrayOf(right,bottom,-1f,-1f))){
            path.moveTo(v[0]+v[2]*g.arm,v[1]);path.lineTo(v[0],v[1]);path.lineTo(v[0],v[1]+v[3]*g.arm)
        }
        c.drawPath(path,p);c.drawCircle(cx,cy,g.radius,p)
        path.reset();path.moveTo(cx-g.crossHalf,cy);path.lineTo(cx+g.crossHalf,cy);path.moveTo(cx,cy-g.crossHalf);path.lineTo(cx,cy+g.crossHalf);c.drawPath(path,p)
        return b
    }
    fun leak():Bitmap {
        val b=Bitmap.createBitmap(480,325,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{blendMode=BlendMode.SCREEN}
        fun rgba(r:Int,g:Int,bl:Int,a:Float=1f)=Color.argb((a*255).roundToInt(),r,g,bl)
        fun glow(x:Float,y:Float,rx:Float,ry:Float,colors:IntArray,stops:FloatArray,opacity:Float){
            c.save();c.translate(x*480,y*325);c.scale(rx*480,ry*325)
            p.alpha=(opacity*255).roundToInt();p.shader=RadialGradient(0f,0f,1f,colors,stops,Shader.TileMode.CLAMP)
            c.drawRect(-1f,-1f,1f,1f,p);c.restore()
        }
        val drift=150f/610f
        glow(-.10f+.29f*drift,.40f,1.10f,1.30f,intArrayOf(rgba(255,117,32),rgba(255,67,13,.96f),rgba(205,21,8,.70f),rgba(120,0,0,0f)),floatArrayOf(0f,.32f,.68f,1f),1f)
        glow(.12f+.62f*drift,.47f,.70f,1.20f,intArrayOf(rgba(255,255,237),rgba(255,248,200),rgba(255,193,80,.95f),rgba(255,95,25,.58f),rgba(255,43,9,0f)),floatArrayOf(0f,.18f,.47f,.76f,1f),1f)
        glow(.91f,.89f,.84f,.86f,intArrayOf(rgba(255,210,129,.96f),rgba(255,111,39,.68f),rgba(190,9,2,0f)),floatArrayOf(0f,.35f,1f),.85f)
        val x=480*(.12f+.63f*drift);val spread=480*.19f
        p.alpha=(.82f*255).roundToInt();p.shader=LinearGradient(x-spread,0f,x+spread,0f,intArrayOf(rgba(255,157,63,0f),rgba(255,244,195,.9f),rgba(255,255,241),rgba(255,185,91,0f)),floatArrayOf(0f,.4f,.57f,1f),Shader.TileMode.CLAMP)
        val path=Path().apply{moveTo(x-spread,-70f);cubicTo(x-spread*.55f,325*.28f,x-spread*1.12f,325*.65f,x-spread*.72f,395f);lineTo(x+spread,395f);cubicTo(x+spread*.65f,325*.68f,x+spread*1.14f,325*.25f,x+spread*.8f,-70f);close()}
        c.drawPath(path,p);p.shader=null;p.color=rgba(255,229,175);p.alpha=(.30f*255).roundToInt();c.drawRect(0f,0f,480f,325f,p)
        return b
    }
    /** blur(5px) in the prototype's mobile-width scene; separable Gaussian, transparent edges. */
    fun bloom(result:Bitmap):Bitmap {
        val w=420;val h=(w/1.1124).roundToInt();val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val board=RectF(w*.035f,h*.05f,w*.965f,h*.93f)
        val area=RectF(board.left+board.width()*.042f,board.top+board.height()*.067f,board.right-board.width()*.042f,board.bottom-board.height()*.067f)
        val scale=min(area.width()/result.width,area.height()/result.height);val rw=result.width*scale;val rh=result.height*scale
        Canvas(b).drawBitmap(result,null,RectF(area.centerX()-rw/2,area.centerY()-rh/2,area.centerX()+rw/2,area.centerY()+rh/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val pixels=IntArray(w*h);b.getPixels(pixels,0,w,0,0,w,h)
        val radius=15;val kernel=FloatArray(radius*2+1){exp(-((it-radius)*(it-radius))/50.0).toFloat()};val sum=kernel.sum();for(i in kernel.indices)kernel[i]/=sum
        val temp=FloatArray(w*h*4);val out=IntArray(w*h)
        for(y in 0 until h)for(x in 0 until w)for(k in -radius..radius){val xx=x+k;if(xx !in 0 until w)continue;val color=pixels[y*w+xx];val a=(color ushr 24)/255f;val weight=kernel[k+radius];val i=(y*w+x)*4;temp[i]+=((color ushr 16)and 255)*a*weight;temp[i+1]+=((color ushr 8)and 255)*a*weight;temp[i+2]+=(color and 255)*a*weight;temp[i+3]+=a*255*weight}
        for(y in 0 until h)for(x in 0 until w){val sums=FloatArray(4);for(k in -radius..radius){val yy=y+k;if(yy !in 0 until h)continue;val i=(yy*w+x)*4;for(ch in 0..3)sums[ch]+=temp[i+ch]*kernel[k+radius]};val a=sums[3]
            fun channel(i:Int)=if(a<.01f)0 else (sums[i]*255/a).roundToInt().coerceIn(0,255)
            out[y*w+x]=Color.argb(a.roundToInt().coerceIn(0,255),channel(0),channel(1),channel(2))}
        b.setPixels(out,0,w,0,0,w,h);return b
    }
}
