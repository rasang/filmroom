package dev.filmroom.effects

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.view.Surface
import java.io.IOException

/** Owns an isolated GLES 2 context. Does not own the MediaCodec input Surface. */
internal class EffectEncoderEgl(surface: Surface) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY, "display")
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0), "initialize")
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0, "config")
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT, "context")
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE, "surface")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }
    fun makeCurrent() = check(EGL14.eglMakeCurrent(display, window, window, context), "make current")
    fun present(timestampNanos: Long) {
        check(EGLExt.eglPresentationTimeANDROID(display, window, timestampNanos), "timestamp")
        check(EGL14.eglSwapBuffers(display, window), "swap buffers")
    }
    private fun check(success: Boolean, operation: String) {
        if (!success) throw IOException("Video EGL $operation failed (0x${Integer.toHexString(EGL14.eglGetError())})")
    }
    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        window = EGL14.EGL_NO_SURFACE
    }
}
