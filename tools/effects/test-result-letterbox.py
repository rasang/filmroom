#!/usr/bin/env python3
"""Offscreen Mesa/EGL regression of the production final-frame GLES shader.
No Android/device claims: verifies portrait and landscape surface mismatch pixels.
Run: EGL_PLATFORM=surfaceless python3 tools/effects/test-result-letterbox.py
"""
import ctypes as c
import os
import pathlib
import re
os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
egl=c.CDLL('libEGL.so.1')
def api(lib,name,ret,*args):
    f=getattr(lib,name);f.restype=ret;f.argtypes=args;return f
P=c.c_void_p; I=c.c_int; U=c.c_uint; F=c.c_float
api(egl,'eglGetDisplay',P,P);api(egl,'eglInitialize',U,P,P,P)
api(egl,'eglChooseConfig',U,P,P,P,I,P);api(egl,'eglBindAPI',U,U)
api(egl,'eglCreatePbufferSurface',P,P,P,P);api(egl,'eglCreateContext',P,P,P,P,P)
api(egl,'eglMakeCurrent',U,P,P,P,P);api(egl,'eglGetProcAddress',P,c.c_char_p)
d=egl.eglGetDisplay(None);assert egl.eglInitialize(d,None,None)
attrs=(I*13)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
config=P();n=I();assert egl.eglChooseConfig(d,attrs,c.byref(config),1,c.byref(n)) and n.value
assert egl.eglBindAPI(0x30A0)
surf=egl.eglCreatePbufferSurface(d,config,(I*5)(0x3057,128,0x3056,128,0x3038))
ctx=egl.eglCreateContext(d,config,None,(I*3)(0x3098,2,0x3038));assert ctx and surf
assert egl.eglMakeCurrent(d,surf,surf,ctx)
def gl(name,ret,*types):return c.CFUNCTYPE(ret,*types)(egl.eglGetProcAddress(name.encode()))
create=gl('glCreateShader',U,U);source=gl('glShaderSource',None,U,I,P,P);compile_=gl('glCompileShader',None,U)
get=gl('glGetShaderiv',None,U,U,P);log=gl('glGetShaderInfoLog',None,U,I,P,P)
def shader(kind,text):
    s=create(kind);v=c.c_char_p(text.encode());source(s,1,c.byref(v),None);compile_(s)
    ok=I();get(s,0x8B81,c.byref(ok));buf=c.create_string_buffer(8192);log(s,8192,None,buf)
    assert ok.value,buf.value;return s
code=(pathlib.Path(__file__).resolve().parents[2]/'app/src/main/java/dev/filmroom/effects/EffectGlRenderer.kt').read_text()
vs=re.search(r'private const val VERTEX="(.*?)"',code).group(1)
fs=re.search(r'private val FRAGMENT="""(.*?)"""',code,re.S).group(1)
p=gl('glCreateProgram',U)();attach=gl('glAttachShader',None,U,U)
attach(p,shader(0x8B31,vs));attach(p,shader(0x8B30,fs));gl('glLinkProgram',None,U)(p)
ok=I();gl('glGetProgramiv',None,U,U,P)(p,0x8B82,c.byref(ok));assert ok.value
gl('glUseProgram',None,U)(p)
loc=gl('glGetUniformLocation',I,U,c.c_char_p)
def f(name,value):gl('glUniform1f',None,I,F)(loc(p,name.encode()),value)
gl('glUniform1i',None,I,I)(loc(p,b'kind'),1);f('showResult',1);f('resultAspect',4/3)
# Solid red result: edge repetition is unambiguous outside the contained photo.
t=U();gl('glGenTextures',None,I,P)(1,c.byref(t));gl('glBindTexture',None,U,U)(0x0DE1,t.value)
param=gl('glTexParameteri',None,U,U,I)
for key in [0x2801,0x2800]:param(0x0DE1,key,0x2600)
for key in [0x2802,0x2803]:param(0x0DE1,key,0x812F)
tex=(c.c_ubyte*4)(255,0,0,255)
gl('glTexImage2D',None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,1,1,0,0x1908,0x1401,tex)
a=gl('glGetAttribLocation',I,U,c.c_char_p)(p,b'a')
vertices=(F*8)(-1,-1,1,-1,-1,1,1,1)
gl('glEnableVertexAttribArray',None,U)(a);gl('glVertexAttribPointer',None,U,I,U,U,I,P)(a,2,0x1406,0,0,vertices)
for w,h in [(96,128),(128,48),(128,96)]:
    f('screenAspect',w/h);gl('glViewport',None,I,I,I,I)(0,0,w,h)
    gl('glDrawArrays',None,U,I,I)(5,0,4)
    data=(c.c_ubyte*(w*h*4))();gl('glReadPixels',None,I,I,I,I,U,U,P)(0,0,w,h,0x1908,0x1401,data)
    def red(x,y):return data[(y*w+x)*4]
    assert red(w//2,h//2)>250
    if w/h<4/3:assert red(w//2,0)==0 and red(w//2,h-1)==0
    elif w/h>4/3:assert red(0,h//2)==0 and red(w-1,h//2)==0
    else:assert red(0,0)>250 and red(w-1,h-1)>250
    print(f'PASS production final frame {w}x{h}')
