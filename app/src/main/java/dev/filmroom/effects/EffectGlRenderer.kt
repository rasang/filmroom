package dev.filmroom.effects

import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns only GL resources, never the input bitmaps. Call all methods with a current GLES2 context. */
class EffectGlRenderer(private val kind:EffectKind,private val source:Bitmap,private val result:Bitmap) {
    private var program=0
    private var marksWidth=0
    private var marksHeight=0
    private val textures=IntArray(4)
    private val vertices=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)
    }
    private val uniforms=mutableMapOf<String,Int>()
    fun initialize() {
        check(program==0) { "Renderer already initialized" }
        val vs=compile(GL_VERTEX_SHADER,VERTEX)
        var fs=0
        try {
            fs=compile(GL_FRAGMENT_SHADER,FRAGMENT)
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program)
            val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0)
            check(ok[0]!=0) { "Effect shader link: ${glGetProgramInfoLog(program)}" }
            glGenTextures(4,textures,0)
            val overlay=if(kind==EffectKind.LIGHT_TABLE)EffectTextures.bloom(result)else EffectTextures.leak()
            val marks=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
            try { listOf(source,result,overlay,marks).forEachIndexed { i,b ->
                glBindTexture(GL_TEXTURE_2D,textures[i])
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GL_TEXTURE_2D,0,b,0)
            }
            } finally { overlay.recycle();marks.recycle() }
            check(glGetError()==GL_NO_ERROR) { "Effect texture upload failed" }
        } catch(t:Throwable) {release();throw t} finally {glDeleteShader(vs);if(fs!=0)glDeleteShader(fs)}
    }
    fun draw(width:Int,height:Int,timeMs:Long,slow:Boolean=false) {
        check(program!=0);if(width<=0||height<=0)return
        glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST)
        if(kind==EffectKind.OPTICAL_LEAK && (width!=marksWidth || height!=marksHeight)) {
            val marks=EffectTextures.marks(width,height,source.width.toFloat()/source.height)
            try {glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,textures[3]);GLUtils.texImage2D(GL_TEXTURE_2D,0,marks,0)
                check(glGetError()==GL_NO_ERROR){"Viewfinder marks upload failed"}
                marksWidth=width;marksHeight=height
            } finally {marks.recycle()}
        }
        glUseProgram(program)
        for(i in 0..3){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i])}
        glUniform1i(location("src"),0);glUniform1i(location("dst"),1);glUniform1i(location("overlay"),2);glUniform1i(location("marks"),3)
        glUniform1f(location("screenAspect"),width.toFloat()/height)
        glUniform1f(location("sourceAspect"),source.width.toFloat()/source.height)
        glUniform1f(location("sourceViewportAspect"),EffectGeometry.sourceViewportAspect(width,height,source.width.toFloat()/source.height))
        val layout=EffectGeometry.opticalLayout(source.width.toFloat()/source.height)
        glUniform2f(location("opticalPad"),layout.padX,layout.padY)
        glUniform2f(location("opticalLogicalSize"),layout.outerW,layout.outerW*height/width)
        glUniform1f(location("resultAspect"),result.width.toFloat()/result.height)
        glUniform1i(location("kind"),if(kind==EffectKind.LIGHT_TABLE)0 else 1)
        val s=EffectTimeline.at(kind,timeMs,slow)
        glUniform4f(location("light"),s.gain,s.lamp,s.bloom,s.cool)
        glUniform4f(location("optical"),s.cover,s.settling,s.leak,s.leakDrift)
        glUniform1f(location("spill"),s.spill);glUniform1f(location("showResult"),if(s.showResult)1f else 0f)
        val a=glGetAttribLocation(program,"a");glEnableVertexAttribArray(a);vertices.position(0)
        glVertexAttribPointer(a,2,GL_FLOAT,false,0,vertices);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(a)
    }
    fun release(){if(program!=0)glDeleteProgram(program);program=0;glDeleteTextures(4,textures,0);textures.fill(0);uniforms.clear();marksWidth=0;marksHeight=0}
    private fun location(name:String)=uniforms.getOrPut(name){glGetUniformLocation(program,name)}
    private fun compile(type:Int,text:String):Int {
        val shader=glCreateShader(type);glShaderSource(shader,text);glCompileShader(shader)
        val ok=IntArray(1);glGetShaderiv(shader,GL_COMPILE_STATUS,ok,0)
        if(ok[0]==0){val log=glGetShaderInfoLog(shader);glDeleteShader(shader);error("Effect shader compile: $log")};return shader
    }
    companion object {
        private const val VERTEX="attribute vec2 a; varying vec2 p; void main(){p=vec2((a.x+1.0)*0.5,(1.0-a.y)*0.5);gl_Position=vec4(a,0.,1.);}"
        // Texture uploads have their first row at v=0: p is deliberately top-left oriented.
        private val FRAGMENT="""
precision highp float;
varying vec2 p;
uniform sampler2D src,dst,overlay,marks;
uniform float screenAspect,sourceAspect,sourceViewportAspect,resultAspect,spill,showResult;
uniform int kind;
uniform vec4 light,optical;
uniform vec2 opticalPad,opticalLogicalSize;
vec2 contain(vec2 q,float viewportAspect,float imageAspect){
 vec2 scale=vec2(1.);if(viewportAspect>imageAspect)scale.x=imageAspect/viewportAspect;else scale.y=viewportAspect/imageAspect;
 return (q-.5)/scale+.5;
}
float inside(vec2 q){return step(0.,q.x)*step(q.x,1.)*step(0.,q.y)*step(q.y,1.);}
vec3 photo(sampler2D tex,vec2 q){return mix(vec3(24./255.),texture2D(tex,clamp(q,0.,1.)).rgb,inside(q));}
float field(vec2 q,vec2 c,vec2 r){vec2 v=(q-c)/r;return exp(-dot(v,v));}
float disk(float d){d=clamp(d,-1.,1.);return .5+(asin(d)+d*sqrt(max(0.,1.-d*d)))/3.14159265;}
vec3 screen(vec3 a,vec3 b){return 1.-(1.-a)*(1.-clamp(b,0.,1.));}
float rounded(vec2 uv,vec2 size,float radius){vec2 d=abs((uv-.5)*size)-(size*.5-radius);float sd=length(max(d,0.))+min(max(d.x,d.y),0.)-radius;return 1.-smoothstep(-.5,.5,sd);}
void main(){
 if(kind==0){
  float roomRadius=length((p-vec2(.35,.15))/(vec2(.65,.85)*1.41421356));
  vec3 room=roomRadius<.56?mix(vec3(48.,45.,39.)/255.,vec3(23.,24.,23.)/255.,roomRadius/.56):mix(vec3(23.,24.,23.)/255.,vec3(12.,14.,14.)/255.,clamp((roomRadius-.56)/.44,0.,1.));
  float spillRadius=length((p-vec2(.5,.47))/(vec2(.5,.53)*1.41421356));
  vec4 spillColor=spillRadius<.55?mix(vec4(vec3(204.,233.,236.)/255.*.27,.27),vec4(vec3(162.,198.,203.)/255.*.12,.12),spillRadius/.55):mix(vec4(vec3(162.,198.,203.)/255.*.12,.12),vec4(0.),clamp((spillRadius-.55)/.25,0.,1.));
  room=room*(1.-spillColor.a*spill)+spillColor.rgb*spill;
  vec2 board=(p-vec2(.035,.05))/vec2(.93,.88);
  vec2 logical=vec2(420.,420./1.1124);
  float boardMask=rounded(board,logical*vec2(.93,.88),9.);
  // Soft drop shadow (CSS 0 12px 24px), rendered behind the rounded table.
  vec2 bd=abs((p-vec2(.5,.49)-vec2(0.,12./logical.y))*logical)-logical*vec2(.465,.44);
  float shadow=exp(-dot(max(bd,0.),max(bd,0.))/(2.*12.*12.));
  room*=1.-.65*shadow;
  vec3 c=room;
  if(boardMask>.0){
   c=vec3(.22,.23,.215);
   vec2 inset=vec2(5.)/(logical*vec2(.93,.88));
   vec2 diffuser=(board-inset)/(1.-2.*inset);
   float diffuserMask=rounded(diffuser,logical*vec2(.93,.88)-10.,5.);
   if(diffuserMask>.0){
    float gradient=clamp((diffuser.x+diffuser.y)*.5,0.,1.);
    vec3 unlit=mix(vec3(.455,.439,.392),vec3(.357,.349,.325),gradient);
    float r=clamp(length((diffuser-.5)*1.4142),0.,1.);
    vec3 lamp=r<.18?vec3(1.,1.,.988):r<.85?mix(vec3(1.,1.,.988),vec3(.949,.969,.961),(r-.18)/.67):mix(vec3(.949,.969,.961),vec3(.875,.914,.902),(r-.85)/.15);
    c=mix(unlit,lamp,light.y);
    c=mix(c,vec3(.65,.84,.90),light.w);
    vec2 area=(board-vec2(.042,.067))/vec2(.916,.866);
    vec2 uv=contain(area,screenAspect*.93/.88*.916/.866,resultAspect);
    if(inside(area)>.5&&inside(uv)>.5){
     vec3 base=texture2D(dst,uv).rgb*light.x;
     c=clamp(base,0.,1.);
    }
   }
  }
  if(boardMask>.0){
   vec4 bloomSample=texture2D(overlay,p);
   c=screen(c,min(bloomSample.rgb*3.5,vec3(bloomSample.a))*light.z);
   // Inset glass rim highlights and bottom edge shade.
   vec2 bp=board*logical*vec2(.93,.88);
   float topRim=(1.-smoothstep(.5,1.5,abs(bp.y-5.)))*step(5.,bp.x)*step(bp.x,logical.x*.93-5.);
   float bottomRim=(1.-smoothstep(.5,1.5,abs(bp.y-(logical.y*.88-5.))))*step(5.,bp.x)*step(bp.x,logical.x*.93-5.);
   c=mix(c,vec3(1.,1.,.96),topRim*.23);c*=1.-bottomRim*.2;
   c=mix(room,c,boardMask);
  }
  vec2 led=(p-vec2(.5+2.5/logical.x,.965-2.5/logical.y))*logical;
  float ledDistance=length(led);
  float lit=step(.00001,light.y);
  // CSS 0 0 7px green shadow (sigma 3.5), underneath the 5px indicator.
  float ledGlow=exp(-max(0.,ledDistance-2.5)*max(0.,ledDistance-2.5)/(2.*3.5*3.5))*.5*lit;
  c=mix(c,vec3(185.,221.,195.)/255.,ledGlow);
  float dotMask=1.-smoothstep(2.,3.,ledDistance);
  c=mix(c,mix(vec3(69.,100.,89.)/255.,vec3(185.,221.,195.)/255.,lit),dotMask*mix(.35,1.,lit));
  c=mix(vec3(24./255.),c,rounded(p,logical,8.));
  gl_FragColor=vec4(c,1.);return;
 }
 vec2 resultUv=contain(p,screenAspect,resultAspect);
 vec3 color=photo(dst,resultUv);
 if(showResult<.5){
  vec2 pad=opticalPad;
  vec2 q=(p-pad)/(1.-2.*pad);

  float cover=optical.x;float angle=cover*1.57079633;float projected=pow(sin(angle),1.27);
  float edge=-.24+1.63*projected;float bow=.018*pow((q.x-.5)*2.,2.);
  float boundary=edge-bow+.012*(q.x-.5);float radius=.075+.035*cos(angle);
  float occult=disk((boundary-q.y)/radius);
  // Shift in the viewfinder plane first, then contain the source without stretching.
  vec2 uv=contain(q+vec2(0.,-cover*.018),sourceViewportAspect,sourceAspect);
  vec3 distant=pow(photo(src,uv),vec3(2.2))*(1.-.17*cover);
  float localY=(q.y+.2)/max(.15,edge+.2);vec2 plane=vec2((q.x-.5)/(1.+.08*(1.-projected))+.5,localY);
  vec3 nearColor=vec3(.195,.193,.171)+field(plane,vec2(.60,.31),vec2(.65,.35))*vec3(.17,.15,.109)
   -field(plane,vec2(.47,.32),vec2(.16,.19))*vec3(.105,.095,.073)
   +field(plane,vec2(.24+.12*cover,.57),vec2(.21,.48))*vec3(.025,.027,.025);
  nearColor*=1.-.21*exp(-pow((q.y-boundary+.065)/.11,2.));
  nearColor+=.012*optical.y*field(plane,vec2(.68,.38),vec2(.35,.5));
  color=pow(mix(distant,pow(max(nearColor,vec3(.005)),vec3(2.2)),occult),vec3(1./2.2));
  color*=1.-.36*pow(clamp(length((q-.5)*vec2(1.,.94))/.7,0.,1.),2.7);
  color+=(fract(sin(dot(floor(q*vec2(960.,640.)),vec2(12.9898,78.233)))*43758.5453)-.5)*.009;
  vec4 mark=texture2D(marks,clamp(q,0.,1.));
  color=color*(1.-mark.a)+mark.rgb;
  color=mix(vec3(.0706,.0745,.0627),color,rounded(q,opticalLogicalSize*(1.-2.*pad),5.));
  float eyeHeight=opticalLogicalSize.y;
  float topEdge=1.-smoothstep(.5,1.5,p.y*eyeHeight);
  float bottomEdge=1.-smoothstep(1.5,2.5,(1.-p.y)*eyeHeight);
  color=mix(color,vec3(64.,65.,59.)/255.,topEdge);
  color=mix(color,vec3(5.,6.,5.)/255.,bottomEdge);
  color=mix(vec3(24./255.),color,rounded(p,opticalLogicalSize,14.));
 }
 // The original cached Canvas light field, transformed exactly like CSS translateX then scaleX.
 vec2 leakUv=vec2((p.x-(-.08+.16*optical.w)-.5)/1.2+.5,p.y);
 vec3 flare=texture2D(overlay,clamp(leakUv,0.,1.)).rgb*inside(leakUv);
 color=screen(color,flare*optical.z);
 gl_FragColor=vec4(clamp(color,0.,1.),1.);
}
""".trimIndent()
    }
}
