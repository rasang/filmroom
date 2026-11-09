package dev.filmroom.effects

import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

internal class EffectMotionGpuBlur:AutoCloseable {
    private var program=0
    private val scratch=IntArray(1)
    private val framebuffer=IntArray(1)
    private var width=0
    private var height=0
    private val quad=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)}
    private fun shader(type:Int,text:String):Int{val shader=glCreateShader(type);glShaderSource(shader,text);glCompileShader(shader);val status=IntArray(1);glGetShaderiv(shader,GL_COMPILE_STATUS,status,0);if(status[0]==0){val log=glGetShaderInfoLog(shader);glDeleteShader(shader);error(log)};return shader}
    fun render(sourceTexture:Int,destinationTexture:Int,w:Int,h:Int){
        val previous=IntArray(1);glGetIntegerv(GL_FRAMEBUFFER_BINDING,previous,0)
        try {
            if(program==0){
                val vertex=shader(GL_VERTEX_SHADER,"attribute vec2 a;varying vec2 uv;void main(){uv=(a+1.0)*0.5;gl_Position=vec4(a,0.0,1.0);}")
                val weights=FloatArray(21){exp(-((it-10)*(it-10))/24.5).toFloat()};val sum=weights.sum()
                var fragment=0
                try{fragment=shader(GL_FRAGMENT_SHADER,"precision highp float;varying vec2 uv;uniform sampler2D s;uniform vec2 d;uniform vec2 bounds;void main(){vec4 c=vec4(0.0);"+weights.indices.joinToString("\n"){"c+=texture2D(s,clamp(uv+d*${it-10},bounds,vec2(1.0)-bounds))*${weights[it]/sum};"}+" gl_FragColor=vec4(c.rgb,1.0);}")
                    program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);val status=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,status,0);check(status[0]!=0){glGetProgramInfoLog(program)}
                }finally{glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment)}
                glGenTextures(1,scratch,0);glGenFramebuffers(1,framebuffer,0)
            }
            if(width!=w||height!=h){for(texture in intArrayOf(scratch[0],destinationTexture)){glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,null)};width=w;height=h}
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer[0]);glViewport(0,0,w,h);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);glUseProgram(program)
            glUniform1i(glGetUniformLocation(program,"s"),0);glUniform2f(glGetUniformLocation(program,"bounds"),.5f/w,.5f/h)
            val a=glGetAttribLocation(program,"a");glEnableVertexAttribArray(a);quad.position(0);glVertexAttribPointer(a,2,GL_FLOAT,false,0,quad)
            for(pass in 0..1){glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,if(pass==0)scratch[0]else destinationTexture,0);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE){"Motion Gaussian framebuffer unavailable"};glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,if(pass==0)sourceTexture else scratch[0]);glUniform2f(glGetUniformLocation(program,"d"),if(pass==0)1f/w else 0f,if(pass==1)1f/h else 0f);glDrawArrays(GL_TRIANGLE_STRIP,0,4)}
            glDisableVertexAttribArray(a);check(glGetError()==GL_NO_ERROR){"Motion Gaussian draw failed"}
        }finally{glBindFramebuffer(GL_FRAMEBUFFER,previous[0])}
    }
    fun invalidateSize(){width=0;height=0}
    override fun close(){if(program!=0)glDeleteProgram(program);program=0;glDeleteTextures(1,scratch,0);glDeleteFramebuffers(1,framebuffer,0);scratch[0]=0;framebuffer[0]=0;invalidateSize()}
}
