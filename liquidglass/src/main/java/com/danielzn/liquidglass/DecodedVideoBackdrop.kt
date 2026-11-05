package com.danielzn.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
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
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A live, small picture of a video as a glass backdrop, for videos the glass can't sample because
 * they play on their own plane (a SurfaceView). Instead of copying that surface (a PixelCopy of a 4K
 * frame can cost a weak GPU tens of ms), this decodes a small **proxy** of the same clip (e.g.
 * 480x270 H.264) itself, preferring a software decoder, into a SurfaceTexture on its own GL thread,
 * draws each frame into a [width] x [height] framebuffer and reads it back into one of two reused
 * bitmaps. Its clock follows the main player: call [sync] with the player's position now and then.
 *
 * The proxy never becomes a display layer (no SurfaceView), so a video system that treats a second
 * video *layer* specially (picture-in-picture paths) isn't involved; only a second decoder instance
 * at a tiny size is.
 *
 * Frames are taken at most [framesPerSecond] (the proxy's own rate or less); attached glass is
 * invalidated after each one. [colorGain] corrects the picture's brightness per channel (a copy is
 * often dimmer than the same video shown with the display's own picture processing).
 */
@RequiresApi(Build.VERSION_CODES.O)
class DecodedVideoBackdrop @JvmOverloads constructor(
    /** Where the real video is on screen (its SurfaceView): the picture is drawn over that rectangle. */
    private val videoView: View,
    val width: Int = 480,
    val height: Int = 270
) : BackdropUnderlay, GlassAttachable {

    /** Pictures per second at most (decoded frames between them are skipped). */
    @Volatile var framesPerSecond: Float = 30f

    /** Copy statistics (for measuring): frames read back, mean and worst ms per readback (GL thread). */
    @Volatile var frames = 0; private set
    @Volatile var meanReadbackMs = 0f; private set
    @Volatile var worstReadbackMs = 0f; private set
    private var totalReadbackMs = 0.0

    private val panes = ArrayList<View>()
    private val main = Handler(Looper.getMainLooper())
    private val buffers = arrayOf(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    )
    @Volatile private var front = -1
    private var version = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private val loc = IntArray(2)

    /** Per-channel gain applied when drawing (1 = unchanged). */
    fun setColorGain(r: Float, g: Float, b: Float) {
        paint.colorFilter = if (r == 1f && g == 1f && b == 1f) null else ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        )
        version++
    }

    override val underlayVersion: Int get() = version

    override fun drawUnderlay(canvas: Canvas) {
        val i = front
        if (i < 0) return
        videoView.getLocationOnScreen(loc)
        dst.set(loc[0], loc[1], loc[0] + videoView.width, loc[1] + videoView.height)
        canvas.drawBitmap(buffers[i], null, dst, paint)
    }

    override fun attach(glass: View) { if (!panes.contains(glass)) panes.add(glass) }
    override fun detach(glass: View) { panes.remove(glass) }

    // ---- playback ----------------------------------------------------------------------------

    private var glThread: HandlerThread? = null
    private var gl: Handler? = null
    private var decodeThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var file: File? = null

    /** Clock: the proxy's position = basePos + (now - baseWall), in ms, looping over [durationMs]. */
    @Volatile private var basePosMs = 0L
    @Volatile private var baseWallMs = 0L
    @Volatile private var seekToMs = -1L
    @Volatile var durationMs = 0L; private set

    /**
     * Decode into this surface instead (a [WindowCompositor]'s video texture): no GL thread of our own, the
     * compositor owns the texture and outlives clips and pauses.
     */
    @Volatile var externalOutput: Surface? = null

    /** Starts (or switches to) [proxy] at [positionMs] of the main clip. */
    fun open(proxy: File, positionMs: Long) {
        close()
        Log.i(TAG, "open ${proxy.name} at $positionMs ms")
        file = proxy
        basePosMs = positionMs
        baseWallMs = SystemClock.uptimeMillis()
        seekToMs = positionMs
        playing = true
        val ext = externalOutput
        if (ext != null) {
            decodeThread = Thread({ decodeLoop(proxy, ext) }, "glass-video-decode").also { it.start() }
            return
        }
        val t = HandlerThread("glass-video-gl").also { it.start() }
        glThread = t
        val h = Handler(t.looper)
        gl = h
        h.post { setUpGl() }
    }

    /** The main player is at [positionMs] now: re-seek if the proxy drifted more than 200 ms. */
    fun sync(positionMs: Long) {
        if (!playing) return
        val now = SystemClock.uptimeMillis()
        val mine = position(now)
        val d = durationMs
        var diff = Math.abs(mine - positionMs)
        if (d > 0) diff = Math.min(diff, d - diff)
        if (diff > 200) {
            basePosMs = positionMs
            baseWallMs = now
            seekToMs = positionMs
        }
    }

    /** How late (ms, negative = early) a frame at [pts] is at clock [now], across the loop point. */
    private fun lateness(now: Long, pts: Long): Long {
        var late = now - pts
        val d = durationMs
        if (d > 0) {
            if (late > d / 2) late -= d
            if (late < -d / 2) late += d
        }
        return late
    }

    private fun position(now: Long): Long {
        val p = basePosMs + (now - baseWallMs)
        val d = durationMs
        return if (d > 0) p % d else p
    }

    /** Stops decoding and frees the decoder and GL (the last picture stays drawable). */
    fun close() {
        playing = false
        decodeThread?.join(1000)
        decodeThread = null
        val h = gl
        val t = glThread
        if (h != null && t != null) {
            h.post { tearDownGl() }
            t.quitSafely()
            t.join(1000)
        }
        gl = null
        glThread = null
    }

    // ---- GL thread -----------------------------------------------------------------------------

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var eglConfig: EGLConfig? = null
    private var rim: Any? = null   // GlassRimRenderer (API 33+)
    private var haveTexture = false
    private val overlays = java.util.concurrent.CopyOnWriteArrayList<GlassRimOverlay>()

    /**
     * Also draws [o] (all its glass panes, in its own compositor layer) from every new picture: the UI
     * isn't invalidated for it. Without pictures read back for [attach]ed glass, nothing is read back.
     */
    @RequiresApi(33)
    fun addOverlay(o: GlassRimOverlay) {
        if (!overlays.contains(o)) overlays.add(o)
        o.onChanged = Runnable { gl?.post(redrawOverlays) }
        gl?.post(redrawOverlays)
    }

    @RequiresApi(33)
    fun removeOverlay(o: GlassRimOverlay) {
        overlays.remove(o)
        o.onChanged = null
        gl?.post { (rim as? GlassRimRenderer)?.forget(o) }
    }

    /** The panes moved or changed (no new picture): draw the overlays again with the last one. */
    private val redrawOverlays = Runnable { if (haveTexture) renderOverlays(false) }

    private fun renderOverlays(newFrame: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val r = rim as? GlassRimRenderer ?: return false
        var any = false
        for (o in overlays) if (r.render(o, oesTex, texMatrix, newFrame)) any = true
        return any
    }

    /** Its state in one line (diagnostics). */
    /**
     * The decoder gave no picture for [STALL_MS] (seen on a TV: started, fed, silent): it is released and a
     * new one started at the clock. False: it is left alone (measuring the stall).
     */
    @Volatile var restartOnStall = true
    /** Decoders restarted after a stall (diagnostics; a check fails on stalls without restarts). */
    @Volatile var stalls = 0; private set

    fun describe(): String = "playing=$playing file=${file?.name} gl=${glThread != null} picture=$haveTexture stalls=$stalls " +
        "readbacks=$frames sinkFrames=$sinkFrames overlays=${overlays.size} sinks=${sinks.size} duration=${durationMs}ms"

    /** Receives each picture as a GPU-backed bitmap (main thread), e.g. [LiveRim.setFrame]. */
    fun interface FrameSink { fun onFrame(frame: Bitmap) }

    private val sinks = java.util.concurrent.CopyOnWriteArrayList<FrameSink>()

    /**
     * Also delivers every picture to [sink] as a hardware bitmap: drawn by the GPU into a buffer of an
     * ImageReader, wrapped without a copy (no readback, no PixelCopy).
     */
    @RequiresApi(29)
    fun addFrameSink(sink: FrameSink) { if (!sinks.contains(sink)) sinks.add(sink) }
    fun removeFrameSink(sink: FrameSink) { sinks.remove(sink) }

    /** Statistics: pictures delivered to sinks, mean GPU-side ms to draw one into its buffer (GL thread). */
    @Volatile var sinkFrames = 0; private set
    @Volatile var meanSinkMs = 0f; private set

    private var reader: android.media.ImageReader? = null
    private var readerEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private val held = ArrayDeque<android.media.Image>()
    private val flippedQuad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // a window surface's first row is its top (the readback quad is for glReadPixels' bottom-up rows)
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    @RequiresApi(29)
    private fun renderToReader() {
        if (reader == null) {
            val r = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 4,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return@setOnImageAvailableListener
                val hb = img.hardwareBuffer
                if (hb == null) { img.close(); return@setOnImageAvailableListener }
                val bmp = Bitmap.wrapHardwareBuffer(hb, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                hb.close()
                held.addLast(img)
                while (held.size > 2) held.removeFirst().close()   // the UI may still draw the previous one
                if (bmp != null) for (k in sinks) k.onFrame(bmp)
            }, main)
            reader = r
            readerEgl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig!!, r.surface, intArrayOf(EGL14.EGL_NONE), 0)
        }
        if (readerEgl == EGL14.EGL_NO_SURFACE) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        EGL14.eglMakeCurrent(eglDisplay, readerEgl, readerEgl, eglContext)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uOes"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        flippedQuad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, flippedQuad)
        GLES20.glEnableVertexAttribArray(aPos)
        flippedQuad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, flippedQuad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(eglDisplay, readerEgl)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        sinkFrames++
        meanSinkMs += (ms - meanSinkMs) / sinkFrames
    }

    /** Anything that wants pictures now: an overlay with a shown pane, or shown attached glass. */
    private fun wanted(): Boolean {
        if (sinks.isNotEmpty()) return true
        // An overlay waiting for its first picture (it takes over only once it has one), or with glass.
        for (o in overlays) if (o.panes.isNotEmpty() || (!o.firstFrameSeen && o.surface != null)) return true
        return panes.isNotEmpty()
    }
    private var decoderSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private var lastTakenMs = 0L
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v; v flipped so glReadPixels' bottom-up rows come out top-down
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    private fun setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(eglDisplay, v, 0, v, 1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, n, 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        eglConfig = configs[0]
        if (Build.VERSION.SDK_INT >= 33) rim = GlassRimRenderer(eglDisplay, configs[0]!!, eglContext)

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        oesTex = t[0]; fboTex = t[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        program = buildProgram()

        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener({ onFrame(it) }, gl)
        surfaceTexture = st
        val s = Surface(st)
        decoderSurface = s
        Log.i(TAG, "GL ready (egl ${EGL14.eglGetError()}), texture $oesTex, fbo status ${GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)}")
        val f2 = file ?: return
        decodeThread = Thread({ decodeLoop(f2, s) }, "glass-video-decode").also { it.start() }
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPos; attribute vec4 aUv; uniform mat4 uTex; varying vec2 vUv;
            void main() { gl_Position = vec4(aPos.xy, 0.0, 1.0); vUv = (uTex * vec4(aUv.xy, 0.0, 1.0)).xy; }""")
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES uOes; varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uOes, vUv); }""")
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun onFrame(st: SurfaceTexture) {
        st.updateTexImage()
        val now = SystemClock.uptimeMillis()
        val fps = framesPerSecond
        if (fps > 0f && now - lastTakenMs < (1000f / fps) - 4) return
        if (!wanted()) return
        lastTakenMs = now
        st.getTransformMatrix(texMatrix)
        haveTexture = true
        if (sinks.isNotEmpty() && Build.VERSION.SDK_INT >= 29) renderToReader()
        if (overlays.isNotEmpty()) {
            renderOverlays(true)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
        // Pictures for glass drawn by the UI (attached panes): only if there is such glass.
        if (panes.isEmpty()) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uOes"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        pixels.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val back = if (front == 0) 1 else 0
        pixels.position(0)
        buffers[back].copyPixelsFromBuffer(pixels)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        frames++
        if (frames == 1) Log.i(TAG, "first picture read back in ${"%.1f".format(ms)} ms")
        totalReadbackMs += ms
        meanReadbackMs = (totalReadbackMs / frames).toFloat()
        if (ms > worstReadbackMs) worstReadbackMs = ms
        main.post {
            front = back
            version++
            for (i in panes.indices) if (panes[i].isShown) panes[i].invalidate()
        }
    }

    private fun tearDownGl() {
        if (readerEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, readerEgl)
        readerEgl = EGL14.EGL_NO_SURFACE
        reader?.let { r -> main.post { held.forEach { it.close() }; held.clear(); r.close() } }
        reader = null
        if (Build.VERSION.SDK_INT >= 33) (rim as? GlassRimRenderer)?.release()
        rim = null
        haveTexture = false
        decoderSurface?.release(); decoderSurface = null
        surfaceTexture?.release(); surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(2, intArrayOf(oesTex, fboTex), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // ---- decode thread ---------------------------------------------------------------------------

    private fun decodeLoop(f: File, surface: Surface) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                if (fm.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; format = fm; break }
            }
            val fmt = format ?: return
            ex.selectTrack(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = createDecoder(mime)
            codec.configure(fmt, surface, null, 0)
            codec.start()
            Log.i(TAG, "decoding ${f.name} with ${codec.name}, ${durationMs} ms")
            var outputs = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // Setting up GL and the decoder takes a while (seconds on a slow device): start where the clock is
            // now, not where it was at open().
            seekToMs = position(SystemClock.uptimeMillis())
            var lastShownMs = 0L
            var lastCatchUpMs = 0L
            var lastOutMs = SystemClock.uptimeMillis()
            var warned = false
            while (playing) {
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastOutMs > STALL_MS && !warned) {
                    stalls++
                    if (restartOnStall) {
                        Log.w(TAG, "decoder stall: no picture for ${nowMs - lastOutMs} ms, restarting it")
                        try { codec?.stop() } catch (_: Exception) {}
                        codec?.release()
                        codec = createDecoder(mime).also { it.configure(fmt, surface, null, 0); it.start() }
                        seekToMs = position(nowMs)
                        inputDone = false
                        lastOutMs = nowMs
                    } else {
                        Log.w(TAG, "decoder stall: no picture for ${nowMs - lastOutMs} ms (left alone)")
                        warned = true
                    }
                }
                val c = codec!!
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1
                    ex.seekTo(seek * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    c.flush()
                    inputDone = false
                }
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            // Loop: back to the start; the clock wraps with durationMs.
                            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            c.queueInputBuffer(ii, 0, 0, 0, 0)
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    lastOutMs = SystemClock.uptimeMillis()
                    val pts = info.presentationTimeUs / 1000
                    if (outputs++ == 0) Log.i(TAG, "first decoded frame at $pts ms (clock ${position(SystemClock.uptimeMillis())} ms)")
                    // Show it when the clock reaches it; frames already more than 100 ms late are dropped.
                    while (playing && seekToMs < 0) {
                        val now = position(SystemClock.uptimeMillis())
                        val ahead = pts - now
                        if (ahead <= 0 || ahead > (durationMs / 2).coerceAtLeast(1000)) break
                        Thread.sleep(Math.min(ahead, 20))
                    }
                    val wall = SystemClock.uptimeMillis()
                    val late = lateness(position(wall), pts)
                    // Behind by more than 300 ms (a slow start, a busy device): jump ahead to the clock instead
                    // of decoding through the backlog, at most once a second. A picture at least every 250 ms,
                    // even a late one, so the glass never stalls on an old frame.
                    if (late > 300 && wall - lastCatchUpMs > 1000) {
                        lastCatchUpMs = wall
                        val d = durationMs
                        seekToMs = if (d > 0) (position(wall) + 300) % d else position(wall) + 300
                    }
                    val show = late in -50..100 || (late > 100 && wall - lastShownMs > 250)
                    if (show) lastShownMs = wall
                    c.releaseOutputBuffer(oi, show)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "proxy decode stopped", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
        }
    }

    /** A software decoder when the system has one for [mime] (no hardware decoder session used), else any. */
    private fun createDecoder(mime: String): MediaCodec {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val soft = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
            if (soft) return MediaCodec.createByCodecName(info.name)
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private companion object {
        const val TAG = "DecodedVideoBackdrop"
        const val STALL_MS = 2500L
    }
}
