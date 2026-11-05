package com.danielzn.liquidglass

import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Live glass inside a window, at no extra compositor layer: the window's own surface is taken
 * ([Window.takeSurface]) and every frame of it is composited here, bottom up: the views [GlassRimOverlay]
 * moved out of the window (scrim, dims), every glass rim drawn from the video picture, and the window's UI
 * (its views, recorded before each UI frame and rendered by our own [HardwareRenderer] into a texture).
 * A new video picture redraws only this composite (a blit and the rims); the views re-render only when they
 * change. The screen keeps the video on its plane and this window on one graphics plane: no GPU
 * composition by SurfaceFlinger.
 *
 * Create it in onCreate, before setContentView. Pictures for the rims: decode into [videoSurface].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class WindowCompositor(private val window: Window, val overlay: GlassRimOverlay) : SurfaceHolder.Callback2 {

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("window-compositor").apply { start() }
    private val gl = Handler(thread.looper)

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: GlassRimRenderer? = null

    private var uiTex = 0
    private var videoTex = 0
    private var uiSt: SurfaceTexture? = null
    private var videoSt: SurfaceTexture? = null
    private var uiSurface: Surface? = null
    /** Where the rims' video comes from: decode the proxy into this (null until GL is up). */
    @Volatile var videoSurface: Surface? = null; private set
    /** Called (main thread) once [videoSurface] exists. */
    var onVideoSurface: Runnable? = null

    private val uiMtx = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val videoMtx = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile private var uiNew = false
    @Volatile private var videoNew = false
    @Volatile private var haveUi = false
    /** A video picture is there to draw the rims from (false: the views draw their still glass themselves). */
    @Volatile var videoLive = false; private set

    private var w = 0
    private var h = 0
    private val root = RenderNode("window-ui")
    private var hw: HardwareRenderer? = null
    private var decor: View? = null
    /** The view tree draws with RenderNodes (elevation shadows, effects): see [enableHardwareViews]. */
    var hardwareViews = false; private set

    /** Statistics: composites, mean CPU ms of one (incl. swap); UI frames, mean ms to record and render one. */
    @Volatile var composites = 0; private set
    @Volatile var meanCompositeMs = 0f; private set
    @Volatile var uiFrames = 0; private set
    @Volatile var meanUiMs = 0f; private set

    init {
        window.takeSurface(this)
        window.setFormat(PixelFormat.TRANSLUCENT)
        overlay.onChanged = Runnable { requestComposite() }
        gl.post { setUpGl() }
    }

    // ---- the window's surface ------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width
        h = height
        overlay.attachToSurface(holder.surface, width, height)
        val d = window.decorView
        if (decor !== d) {
            decor = d
            hardwareViews = enableHardwareViews(d)
            d.viewTreeObserver.addOnPreDrawListener(preDraw)
        }
        gl.post {
            uiSt?.setDefaultBufferSize(width, height)
            main.post { startRenderer() }
        }
        Log.i(TAG, "window surface ${width}x$height, hardware views $hardwareViews")
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) { recordUi() }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        overlay.detachSurface()
        val l = java.util.concurrent.CountDownLatch(1)
        gl.post { renderer?.forget(overlay); l.countDown() }
        l.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener { recordUi(); true }

    /**
     * The window's views keep drawing with RenderNodes (so elevation shadows, hardware layers and effects
     * work) although no ViewRootImpl renderer exists: the flag lives in the view tree's AttachInfo.
     */
    private fun enableHardwareViews(d: View): Boolean = try {
        val f = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }
        val ai = f.get(d)
        if (ai == null) false else {
            ai.javaClass.getDeclaredField("mHardwareAccelerated").apply { isAccessible = true }.setBoolean(ai, true)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "views stay unaccelerated (no shadows): $t")
        false
    }

    private fun startRenderer() {
        val s = uiSurface ?: return
        if (hw != null) return
        hw = HardwareRenderer().apply {
            setContentRoot(root)
            setSurface(s)
            isOpaque = false
        }
        recordUi()
    }

    /** Records the window's views and renders them into the UI texture (main thread, before a UI frame). */
    fun recordUi() {
        val r = hw ?: return
        val d = decor ?: return
        if (w <= 0) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        root.setPosition(0, 0, w, h)
        val c = root.beginRecording(w, h)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        d.draw(c)
        root.endRecording()
        r.createRenderRequest().setVsyncTime(System.nanoTime()).setWaitForPresent(false).syncAndDraw()
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        uiFrames++
        meanUiMs += (ms - meanUiMs) / uiFrames
    }

    // ---- GL -------------------------------------------------------------------------------------

    private fun setUpGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val c = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attrs, 0, c, 0, 1, n, 0)
        cfg = c[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
        renderer = GlassRimRenderer(dpy, cfg!!, ctx)
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        uiTex = t[0]; videoTex = t[1]
        for (id in t) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        uiSt = SurfaceTexture(uiTex).apply {
            if (w > 0) setDefaultBufferSize(w, h)
            setOnFrameAvailableListener({ uiNew = true; requestComposite() }, gl)
        }
        uiSurface = Surface(uiSt)
        videoSt = SurfaceTexture(videoTex).apply {
            setOnFrameAvailableListener({ videoNew = true; requestComposite() }, gl)
        }
        val vs = Surface(videoSt)
        videoSurface = vs
        main.post {
            onVideoSurface?.run()
            startRenderer()
        }
    }

    private var scheduled = false
    private val frame = Choreographer.FrameCallback { scheduled = false; composite() }

    /** Composites at the next vsync (any number of requests in one frame make one composite). */
    fun requestComposite() {
        if (Looper.myLooper() != thread.looper) { gl.post { requestComposite() }; return }
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun composite() {
        val r = renderer ?: return
        EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
        if (uiNew) { uiNew = false; uiSt?.updateTexImage(); uiSt?.getTransformMatrix(uiMtx); haveUi = true }
        if (videoNew) { videoNew = false; videoSt?.updateTexImage(); videoSt?.getTransformMatrix(videoMtx) }
        val ms = r.composite(overlay, videoTex, videoMtx, videoLive, uiTex, uiMtx, haveUi)
        if (ms >= 0) {
            composites++
            meanCompositeMs += (ms - meanCompositeMs) / composites
        }
    }

    /** The video picture stopped (screensaver, covered, no proxy): the rims are drawn no more. */
    fun setVideoLive(live: Boolean) {
        videoLive = live
        requestComposite()
    }

    fun release() {
        hw?.destroy()
        hw = null
        decor?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
        gl.post {
            renderer?.release()
            uiSt?.release(); videoSt?.release()
            uiSurface?.release(); videoSurface?.release()
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuffer)
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(dpy, ctx)
        }
        thread.quitSafely()
    }

    companion object {
        const val TAG = "WindowCompositor"
    }
}
