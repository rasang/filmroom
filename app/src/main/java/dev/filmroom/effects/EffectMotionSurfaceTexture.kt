package dev.filmroom.effects

import android.graphics.SurfaceTexture
import android.opengl.GLES20.*
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class EffectMotionSurfaceTexture {
    private val newFrame=AtomicBoolean(false)
    private val surfaceReleased=AtomicBoolean(false)
    private val oes=IntArray(1)
    private val fbo=IntArray(1)
    private var program=0
    private val uniforms=HashMap<String,Int>()
    private val transform=FloatArray(16)
    private val quad=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)}
    private val stream:SurfaceTexture
    val surface:Surface
    private var targetWidth=0
    private var targetHeight=0
    private var lastTarget=0
    private data class MappingKey(val w:Int,val h:Int,val rotation:Int,val composition:EffectSourceComposition?,val edge:Int)
    private var mappingKey:MappingKey?=null
    private var mapping:EffectMotionSurfaceMapping?=null
    data class Frame(val width:Int,val height:Int,val timestampNs:Long)
    init {
        glGenTextures(1,oes,0);check(oes[0]!=0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES,oes[0]);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        var created:SurfaceTexture?=null
        try{created=SurfaceTexture(oes[0]);stream=created;stream.setOnFrameAvailableListener({newFrame.set(true)},frameEvents);surface=Surface(stream)}
        catch(t:Throwable){runCatching{created?.release()};glDeleteTextures(1,oes,0);oes[0]=0;throw t}
    }
    val hasPendingFrame get()=!surfaceReleased.get()&&newFrame.get()
    private fun location(name:String)=uniforms.getOrPut(name){glGetUniformLocation(program,name)}
    @Synchronized fun drawLatest(targetTexture:Int,rawWidth:Int,rawHeight:Int,rotationDegrees:Int,composition:EffectSourceComposition?,maxEdge:Int=720):Frame? {
        if(surfaceReleased.get()||!newFrame.compareAndSet(true,false))return null
        stream.updateTexImage();stream.getTransformMatrix(transform);val timestamp=stream.timestamp
        val key=MappingKey(rawWidth,rawHeight,rotationDegrees,composition,maxEdge)
        if(key!=mappingKey){mapping=EffectMotionSurfaceMapping.create(rawWidth,rawHeight,rotationDegrees,composition,maxEdge);mappingKey=key}
        val m=requireNotNull(mapping);val old=IntArray(1);glGetIntegerv(GL_FRAMEBUFFER_BINDING,old,0)
        try{
            initializeProgram();glActiveTexture(GL_TEXTURE0)
            if(targetWidth!=m.width||targetHeight!=m.height||targetTexture!=lastTarget){
                glBindTexture(GL_TEXTURE_2D,targetTexture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,m.width,m.height,0,GL_RGBA,GL_UNSIGNED_BYTE,null);targetWidth=m.width;targetHeight=m.height;lastTarget=targetTexture
            }
            glBindFramebuffer(GL_FRAMEBUFFER,fbo[0]);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,targetTexture,0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE){"Motion composition framebuffer unavailable"}
            glViewport(0,0,m.width,m.height);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);glUseProgram(program)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES,oes[0]);glUniform1i(location("video"),0);glUniformMatrix4fv(location("st"),1,false,transform,0)
            glUniform2f(location("sourcePixels"),m.sourceWidth.toFloat(),m.sourceHeight.toFloat())
            glUniform2f(location("origin"),m.origin.first.toFloat(),m.origin.second.toFloat());glUniform2f(location("axisU"),m.u.first.toFloat(),m.u.second.toFloat());glUniform2f(location("axisV"),m.v.first.toFloat(),m.v.second.toFloat())
            glUniform1i(location("rotation"),m.rotation/90)
            glUniform1i(location("composed"),if(m.origin==(0.0 to 0.0)&&m.u==(1.0 to 0.0)&&m.v==(0.0 to 1.0))0 else 1)
            val a=glGetAttribLocation(program,"a");glEnableVertexAttribArray(a);quad.position(0);glVertexAttribPointer(a,2,GL_FLOAT,false,0,quad);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(a)
            check(glGetError()==GL_NO_ERROR){"Motion surface composition draw failed"}
            return Frame(m.width,m.height,timestamp)
        }finally{glBindFramebuffer(GL_FRAMEBUFFER,old[0])}
    }
    @Synchronized fun discardPending():Boolean {if(surfaceReleased.get()||!newFrame.compareAndSet(true,false))return false;stream.updateTexImage();return true}
    fun invalidateTarget(){targetWidth=0;targetHeight=0;lastTarget=0}
    private fun shader(type:Int,source:String):Int {val s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);val ok=IntArray(1);glGetShaderiv(s,GL_COMPILE_STATUS,ok,0);if(ok[0]==0){val log=glGetShaderInfoLog(s);glDeleteShader(s);error(log)};return s}
    private fun initializeProgram(){
        if(program!=0)return
        val vs=shader(GL_VERTEX_SHADER,VERTEX);var fs=0
        try{fs=shader(GL_FRAGMENT_SHADER,FRAGMENT);program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0);check(ok[0]!=0){glGetProgramInfoLog(program)};glGenFramebuffers(1,fbo,0)}
        finally{glDeleteShader(vs);if(fs!=0)glDeleteShader(fs)}
    }
    @Synchronized fun releaseGl(){
        if(!surfaceReleased.get())runCatching{stream.detachFromGLContext()}
        if(program!=0)glDeleteProgram(program);program=0;uniforms.clear();mappingKey=null;mapping=null
        glDeleteFramebuffers(1,fbo,0);fbo[0]=0;glDeleteTextures(1,oes,0);oes[0]=0;invalidateTarget()
    }
    @Synchronized fun releaseSurface(){if(surfaceReleased.compareAndSet(false,true)){stream.setOnFrameAvailableListener(null);surface.release();stream.release()}}
    companion object {
        private val frameEvents:Handler by lazy {HandlerThread("effect-surface-events").let{it.start();Handler(it.looper)}}
        internal const val VERTEX="""attribute vec2 a;varying vec2 uv;void main(){uv=(a+1.0)*0.5;gl_Position=vec4(a,0.0,1.0);}"""
        internal const val FRAGMENT="""
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 uv;
uniform samplerExternalOES video;
uniform mat4 st;
uniform vec2 sourcePixels,origin,axisU,axisV;
uniform int rotation,composed;
vec3 linearize(vec3 x){return mix(x/12.92,pow((x+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),x));}
vec3 encode(vec3 x){return mix(x*12.92,1.055*pow(max(x,vec3(0.0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),x));}
vec3 rawSample(vec2 p){
 vec2 q=p;
 if(rotation==1)q=vec2(p.y,1.0-p.x);
 else if(rotation==2)q=vec2(1.0-p.x,1.0-p.y);
 else if(rotation==3)q=vec2(1.0-p.y,p.x);
 // q is raw top-left normalized. ST expects conventional bottom-left texture coordinates.
 vec2 tc=(st*vec4(q.x,1.0-q.y,0.0,1.0)).xy;
 return texture2D(video,tc).rgb;
}
vec3 sampleSource(vec2 p){return linearize(rawSample(p));}
void main(){
 if(composed==0){gl_FragColor=vec4(rawSample(uv),1.0);return;}
 vec2 p=clamp((origin+axisU*uv.x+axisV*uv.y)*sourcePixels-0.5,vec2(0.0),sourcePixels-1.0);
 vec2 lo=floor(p),f=p-lo;
 vec2 hi=min(lo+1.0,sourcePixels-1.0);
 vec3 a=sampleSource((lo+0.5)/sourcePixels);
 vec3 b=sampleSource((vec2(hi.x,lo.y)+0.5)/sourcePixels);
 vec3 c=sampleSource((vec2(lo.x,hi.y)+0.5)/sourcePixels);
 vec3 d=sampleSource((hi+0.5)/sourcePixels);
 gl_FragColor=vec4(encode(mix(mix(a,b,f.x),mix(c,d,f.x),f.y)),1.0);
}
"""
    }
}
