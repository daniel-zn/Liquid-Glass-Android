package com.danielzn.liquidglass

import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Live glass over a video without touching the UI: every glass pane of one window is drawn by one
 * compositor layer of its own (a [SurfaceControl] placed between the video and the window's UI), from
 * the video's decoded frames (a [DecodedVideoBackdrop] renders it on its GL thread). A new video
 * frame recomposes only this layer; the window's views are never invalidated or re-recorded for it.
 *
 * As the [LiquidGlassDrawable.backdropUnderlay], the drawable doesn't draw its lens: it reports its
 * position, size and lens parameters here whenever the UI draws it anyway, and this layer draws the
 * rim (refraction, dispersion, rim light) and the clear inside's veil. No blur at run time: the video
 * source is expected to be encoded already softened (a small proxy).
 *
 * In another window (a menu, Control Center), [windowCopy] is what's behind besides the video: the
 * copy of the window underneath, drawn over the video in the shader.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GlassRimOverlay(
    val name: String,
    val windowCopy: PixelCopyBackdrop? = null
) : BackdropUnderlay, GlassAttachable {

    /** One pane as drawn: position and size in the layer's pixels, its lens, its opacity. */
    internal class Pane(val x: Float, val y: Float, val w: Float, val h: Float, val p: GlassLensRenderer.LensParams, val alpha: Float)

    private class Entry(val key: Any, val host: WeakReference<View>, var pane: Pane) {
        var left = 0f        // the glass's bounds in its host
        var top = 0f
        var ownAlpha = 1f    // the drawable's own alpha
    }

    private val entries = ArrayList<Entry>()
    @Volatile internal var panes: Array<Pane> = emptyArray(); private set
    /** Bumped whenever [panes] or the layer change. */
    @Volatile var version = 0; private set
    /** Called (main thread) when something to draw changed; the video source redraws then. */
    @Volatile var onChanged: Runnable? = null

    @Volatile var surface: Surface? = null; private set
    private var control: SurfaceControl? = null
    @Volatile var surfaceW = 0; private set
    @Volatile var surfaceH = 0; private set
    /** Where the video is in the layer's pixels (the decoded frame is stretched over it). */
    @Volatile var videoX = 0f
    @Volatile var videoY = 0f
    @Volatile var videoW = 0f
    @Volatile var videoH = 0f
    /**
     * A backdrop view of the window whose drawing moves into this layer, under the glass, so it can't
     * darken the glass drawn over it (a readability scrim, a dim): its background (a solid colour, or
     * rendered once into a picture) at its place, with its live alpha. The glass refracts it as glass
     * drawn in the UI refracts the scrimmed backdrop.
     */
    class Moved internal constructor(internal val view: View, internal val bitmap: android.graphics.Bitmap?, internal val color: Int,
                                     internal val saved: android.graphics.drawable.Drawable?) {
        @Volatile internal var x = 0f
        @Volatile internal var y = 0f
        @Volatile internal var w = 0f
        @Volatile internal var h = 0f
        @Volatile internal var alpha = 0f
    }

    private class Slot(val moved: Moved, val pass: Boolean, val own: Boolean)

    /** Slots 0 and 1 are under [windowCopy] (they belong to the window under it), slot 2 over it. */
    private val slots = arrayOfNulls<Slot>(SLOTS)
    internal fun slotMoved(i: Int): Moved? = slots[i]?.moved
    internal fun slotPass(i: Int): Boolean = slots[i]?.pass == true

    /**
     * Moves [view]'s background into slot [slot] (0-2): drawn here ([pass] true) and refracted by the
     * glass; the view itself draws nothing until [restoreMoved] (its alpha and visibility still count).
     */
    fun move(slot: Int, view: View, pass: Boolean = true) {
        val bg = view.background
        val color = (bg as? android.graphics.drawable.ColorDrawable)?.color ?: 0
        var bmp: android.graphics.Bitmap? = null
        if (bg != null && bg !is android.graphics.drawable.ColorDrawable && view.width > 0 && view.height > 0) {
            // Gradients: half resolution is plenty (smooth, filtered when drawn).
            val bw = (view.width / 2).coerceAtLeast(1)
            val bh = (view.height / 2).coerceAtLeast(1)
            bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.scale(bw / view.width.toFloat(), bh / view.height.toFloat())
            val old = android.graphics.Rect(bg.bounds)
            bg.setBounds(0, 0, view.width, view.height)
            bg.draw(c)
            bg.bounds = old
        }
        if (bg == null) return
        restoreSlot(slot)
        slots[slot] = Slot(Moved(view, bmp, color, bg), pass, true)
        view.background = null
        observe(view)
        refresh()
    }

    /** Refracts (doesn't draw) [from]'s slot [fromSlot] in slot [slot]: e.g. home's scrim under another window's glass. */
    fun share(slot: Int, from: GlassRimOverlay, fromSlot: Int) {
        val m = from.slots[fromSlot]?.moved ?: return
        restoreSlot(slot)
        slots[slot] = Slot(m, false, false)
        changed()
    }

    private fun restoreSlot(i: Int) {
        val sl = slots[i] ?: return
        if (sl.own) sl.moved.view.background = sl.moved.saved
        slots[i] = null
    }

    /** Gives the moved backgrounds back to their views. */
    fun restoreMoved() {
        for (i in 0 until SLOTS) restoreSlot(i)
        changed()
    }

    private val slotLoc = IntArray(2)

    private fun refreshSlots(): Boolean {
        var any = false
        for (sl in slots) {
            if (sl == null || !sl.own) continue
            val m = sl.moved
            val v = m.view
            val a = if (v.isShown) v.alpha else 0f
            v.getLocationInWindow(slotLoc)
            val x = slotLoc[0] - originX
            val y = slotLoc[1] - originY
            if (a != m.alpha || x != m.x || y != m.y || v.width.toFloat() != m.w || v.height.toFloat() != m.h) {
                m.alpha = a; m.x = x; m.y = y; m.w = v.width.toFloat(); m.h = v.height.toFloat()
                any = true
            }
        }
        return any
    }

    /** Called once (main thread) when the layer has shown its first picture since [resetFirstFrame]. */
    @Volatile var onFirstFrame: Runnable? = null
    @Volatile internal var firstFrameSeen = false
    fun resetFirstFrame() { firstFrameSeen = false }

    /**
     * Frozen: keeps its last picture, presents nothing new for video frames (only when its panes change).
     * Every presented frame costs a composition; while another window's glass is up, this one can wait.
     */
    @Volatile var frozen = false

    /** Shows or hides the layer (hidden: its last picture never lingers when the video stops). */
    fun setShown(shown: Boolean) {
        wantShown = shown
        val sc = control ?: return
        SurfaceControl.Transaction().setVisibility(sc, shown).apply()
    }

    private var wantShown = true

    /** The layer's top-left corner in its window's coordinates (panes report window positions). */
    @Volatile var originX = 0f
    @Volatile var originY = 0f
    /** Where [windowCopy] goes in the layer's pixels. */
    @Volatile var copyX = 0f
    @Volatile var copyY = 0f

    /** Statistics (GL thread): layer frames drawn, mean and worst ms per frame. */
    @Volatile var frames = 0; internal set
    @Volatile var meanMs = 0f; internal set
    @Volatile var worstMs = 0f; internal set

    /**
     * Creates the layer as a child of [parent] at z [layer] (relative to the parent's own content:
     * -1 = just under a window's UI; +1 = just over a video SurfaceView's picture), [w] x [h] pixels,
     * applied at once.
     */
    fun attachTo(parent: SurfaceControl, layer: Int, w: Int, h: Int) {
        detachSurface()
        val sc = SurfaceControl.Builder().setName(name).setBufferSize(w, h).setFormat(PixelFormat.RGBA_8888)
            .setOpaque(false).setParent(parent).build()
        SurfaceControl.Transaction().setLayer(sc, layer).setVisibility(sc, wantShown).apply()
        control = sc
        surfaceW = w
        surfaceH = h
        surface = Surface(sc)
        changed()
    }

    /**
     * Creates the layer in a window (another window's glass: Control Center, a menu), at z [layer]
     * relative to that window's own content (-1: just under its UI), [w] x [h] pixels.
     */
    fun attachToWindow(window: android.view.AttachedSurfaceControl, layer: Int, w: Int, h: Int) {
        detachSurface()
        val sc = SurfaceControl.Builder().setName(name).setBufferSize(w, h).setFormat(PixelFormat.RGBA_8888)
            .setOpaque(false).build()
        val t = window.buildReparentTransaction(sc) ?: return
        t.setLayer(sc, layer).setVisibility(sc, wantShown)
        window.applyTransactionOnDraw(t)
        control = sc
        surfaceW = w
        surfaceH = h
        surface = Surface(sc)
        changed()
    }

    /** Removes the layer (its GL surface is dropped on the next render). */
    fun detachSurface() {
        val sc = control ?: return
        val s = surface
        surface = null
        control = null
        changed()
        SurfaceControl.Transaction().reparent(sc, null).apply()
        s?.release()
        sc.release()
    }

    // ---- panes (main thread) -----------------------------------------------------------------

    /** [key]'s glass on [host] is at x, y (layer pixels), w x h, with lens [p] and opacity [alpha]. */
    internal fun update(key: Any, host: View, left: Float, top: Float, w: Float, h: Float, p: GlassLensRenderer.LensParams, ownAlpha: Float) {
        var e = entries.firstOrNull { it.key === key }
        if (e == null) {
            e = Entry(key, WeakReference(host), Pane(0f, 0f, w, h, p, 0f))
            entries.add(e)
        } else e.pane = Pane(e.pane.x, e.pane.y, w, h, p, e.pane.alpha)
        e.left = left
        e.top = top
        e.ownAlpha = ownAlpha
        observe(host)
        refresh()
    }

    fun remove(key: Any) {
        if (entries.removeAll { it.key === key }) refresh()
    }

    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }

    private fun observe(host: View) {
        val o = host.rootView.viewTreeObserver
        if (o === observer && o.isAlive) return
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
        o.addOnPreDrawListener(preDraw)
        observer = o
    }

    /** Shown panes now (measuring). */
    fun paneCount(): Int = panes.size

    private val hostLoc = IntArray(2)

    /** Where the glass is and how opaque, now: its host's place in the window and every ancestor's alpha. */
    private fun follow(e: Entry, h: View): Boolean {
        h.getLocationInWindow(hostLoc)
        val x = hostLoc[0] + e.left - originX
        val y = hostLoc[1] + e.top - originY
        var a = e.ownAlpha
        var v: View? = h
        while (v != null) {
            a *= v.alpha
            v = v.parent as? View
        }
        val p = e.pane
        if (x == p.x && y == p.y && a == p.alpha) return false
        e.pane = Pane(x, y, p.w, p.h, p.p, a)
        return true
    }

    /**
     * Re-checks which panes are shown, where and how opaque (every UI frame of the window, cheap: a short
     * list), so the layer follows fades, slides and scrolling frame by frame.
     */
    fun refresh() {
        for (e in entries) e.host.get()?.let { if (shown(e)) follow(e, it) }
        var n = 0
        for (e in entries) if (shown(e)) n++
        val next = arrayOfNulls<Pane>(n)
        var i = 0
        for (e in entries) if (shown(e)) next[i++] = e.pane
        @Suppress("UNCHECKED_CAST")
        val arr = next as Array<Pane>
        val slotsChanged = refreshSlots()
        if (!same(arr, panes) || slotsChanged) {
            panes = arr
            changed()
        }
    }

    private fun shown(e: Entry): Boolean {
        val h = e.host.get() ?: return false
        return h.isAttachedToWindow && h.isShown && h.windowVisibility == View.VISIBLE
    }

    private fun same(a: Array<Pane>, b: Array<Pane>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    private fun changed() {
        version++
        onChanged?.run()
    }

    // ---- as an underlay ----------------------------------------------------------------------

    override fun drawUnderlay(canvas: android.graphics.Canvas) {}
    override val underlayVersion: Int get() = 0

    override fun attach(glass: View) {
        windowCopy?.attach(glass)
    }

    override fun detach(glass: View) {
        windowCopy?.detach(glass)
        if (entries.removeAll { it.host.get() === glass || it.host.get() == null }) refresh()
    }

    override fun onGlassDrawn() {
        windowCopy?.onGlassDrawn()
    }

    companion object {
        const val TAG = "GlassRimOverlay"
        internal const val SLOTS = 3
    }
}

/** Draws [GlassRimOverlay]s with the video's external texture, on the video source's GL thread. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassRimRenderer(private val display: EGLDisplay, private val config: EGLConfig, private val context: EGLContext) {

    private class Target(val surface: Surface, val egl: EGLSurface) {
        var drawnVersion = -1
        var drawnEmpty = false
    }

    private val targets = HashMap<GlassRimOverlay, Target>()
    private var program = 0
    private var copyTex = 0
    private val copyVersions = HashMap<GlassRimOverlay, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val loc = HashMap<String, Int>()

    /**
     * Draws [o] if needed: [newFrame] (a new video picture) or its panes changed. Returns false when
     * it has nothing to draw (no layer or no shown pane, already cleared).
     */
    fun render(o: GlassRimOverlay, oesTex: Int, texMatrix: FloatArray, newFrame: Boolean): Boolean {
        val s = o.surface
        var t = targets[o]
        if (t != null && t.surface !== s) {
            EGL14.eglDestroySurface(display, t.egl)
            targets.remove(o)
            t = null
        }
        if (s == null || !s.isValid) return false
        if (t == null) {
            val egl = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
                Log.w(GlassRimOverlay.TAG, "no EGL window surface (${EGL14.eglGetError()})")
                return false
            }
            t = Target(s, egl)
            targets[o] = t
        }
        val panes = o.panes
        val v = o.version
        // Waiting for a first picture (re-armed after a hand-back): always draw, so the callback comes.
        val waiting = !o.firstFrameSeen
        if (panes.isEmpty()) {
            if (t.drawnEmpty && t.drawnVersion == v && !waiting) return false
        } else if ((!newFrame || o.frozen) && t.drawnVersion == v && !waiting) {
            return false
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        EGL14.eglMakeCurrent(display, t.egl, t.egl, context)
        if (program == 0) build()
        // The context is shared with the decoder's readback, which leaves its offscreen framebuffer bound:
        // draw into this layer's window surface (the default framebuffer).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, o.surfaceW, o.surfaceH)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawSlots(o)
        if (panes.isNotEmpty()) drawPanes(o, panes, oesTex, texMatrix)
        EGL14.eglSwapBuffers(display, t.egl)
        t.drawnVersion = v
        t.drawnEmpty = panes.isEmpty()
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        o.frames++
        o.meanMs += (ms - o.meanMs) / o.frames
        if (ms > o.worstMs) o.worstMs = ms
        GlassStats.overlayFrames++
        if (!o.firstFrameSeen) {
            o.firstFrameSeen = true
            o.onFirstFrame?.let { main.post(it) }
        }
        return true
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var slotProgram = 0
    /** Per moved picture: its texture (pictures are uploaded once). */
    private val slotTex = HashMap<GlassRimOverlay.Moved, Int>()

    private fun texFor(m: GlassRimOverlay.Moved): Int {
        slotTex[m]?.let { return it }
        val bmp = m.bitmap ?: return 0
        val tx = IntArray(1)
        GLES20.glGenTextures(1, tx, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        slotTex[m] = tx[0]
        return tx[0]
    }

    /** Premultiplied colour of a solid moved view at its alpha. */
    private fun colorOf(m: GlassRimOverlay.Moved): FloatArray {
        val c = m.color
        val a = ((c ushr 24) and 255) / 255f * m.alpha
        return floatArrayOf(((c shr 16) and 255) / 255f * a, ((c shr 8) and 255) / 255f * a, (c and 255) / 255f * a, a)
    }

    /** The layer's own content under the glass: the moved views drawn here (passes), in slot order. */
    private fun drawSlots(o: GlassRimOverlay) {
        var any = false
        for (i in 0 until GlassRimOverlay.SLOTS) if (o.slotPass(i) && (o.slotMoved(i)?.alpha ?: 0f) > 0.001f) any = true
        if (!any) return
        if (slotProgram == 0) {
            slotProgram = link("attribute vec2 aPos; uniform vec4 uRect; uniform vec2 uSurf; varying vec2 vUv; void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); vec2 px = uRect.xy + vUv * uRect.zw; gl_Position = vec4(px.x / uSurf.x * 2.0 - 1.0, 1.0 - px.y / uSurf.y * 2.0, 0.0, 1.0); }",
                "precision mediump float; uniform sampler2D uTexS; uniform float uIsColor; uniform vec4 uColor; uniform float uA; varying vec2 vUv; void main() { gl_FragColor = uIsColor > 0.5 ? uColor : texture2D(uTexS, vUv) * uA; }")
        }
        GLES20.glUseProgram(slotProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(slotProgram, "uSurf"), o.surfaceW.toFloat(), o.surfaceH.toFloat())
        val aPos = GLES20.glGetAttribLocation(slotProgram, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        full.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, full)
        for (i in 0 until GlassRimOverlay.SLOTS) {
            val m = o.slotMoved(i) ?: continue
            if (!o.slotPass(i) || m.alpha <= 0.001f) continue
            GLES20.glUniform4f(GLES20.glGetUniformLocation(slotProgram, "uRect"), m.x, m.y, m.w, m.h)
            val isColor = m.bitmap == null
            GLES20.glUniform1f(GLES20.glGetUniformLocation(slotProgram, "uIsColor"), if (isColor) 1f else 0f)
            if (isColor) {
                val c = colorOf(m)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(slotProgram, "uColor"), c[0], c[1], c[2], c[3])
            } else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texFor(m))
                GLES20.glUniform1i(GLES20.glGetUniformLocation(slotProgram, "uTexS"), 2)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(slotProgram, "uA"), m.alpha)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** The slots as the lens refracts them (texture units 2-4). */
    private fun bindSlotsForLens(o: GlassRimOverlay) {
        for (i in 0 until GlassRimOverlay.SLOTS) {
            val m = o.slotMoved(i)
            val on = m != null && m.alpha > 0.001f
            // Every sampler on a unit of its own, used or not (sampler2D and the video's external sampler
            // on one unit make the draw fail on some drivers).
            GLES20.glUniform1i(u("uS${i}Tex"), 2 + i)
            GLES20.glUniform1f(u("uS${i}On"), if (on) 1f else 0f)
            if (!on) continue
            m!!
            GLES20.glUniform4f(u("uS${i}Rect"), m.x, m.y, m.w.coerceAtLeast(1f), m.h.coerceAtLeast(1f))
            val isColor = m.bitmap == null
            GLES20.glUniform1f(u("uS${i}IsColor"), if (isColor) 1f else 0f)
            if (isColor) {
                val c = colorOf(m)
                GLES20.glUniform4f(u("uS${i}Color"), c[0], c[1], c[2], c[3])
            } else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texFor(m))
                GLES20.glUniform1i(u("uS${i}Tex"), 2 + i)
                GLES20.glUniform1f(u("uS${i}A"), m.alpha)
            }
        }
    }

    private val full: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    private fun drawPanes(o: GlassRimOverlay, panes: Array<GlassRimOverlay.Pane>, oesTex: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        bindSlotsForLens(o)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(u("uVideo"), 0)
        GLES20.glUniformMatrix4fv(u("uTex"), 1, false, texMatrix, 0)
        val copy = o.windowCopy?.frame
        if (copy != null) {
            if (copyTex == 0) {
                val tx = IntArray(1)
                GLES20.glGenTextures(1, tx, 0)
                copyTex = tx[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, copyTex)
            val cv = o.windowCopy!!.underlayVersion
            if (copyVersions[o] != cv) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, copy, 0)
                copyVersions[o] = cv
            }
            GLES20.glUniform1i(u("uCopy"), 1)
            GLES20.glUniform1f(u("uHasCopy"), 1f)
            // The copy is scaled down: it covers the window it was taken of (the layer's size here).
            GLES20.glUniform4f(u("uCopyRect"), o.copyX, o.copyY, o.surfaceW.toFloat(), o.surfaceH.toFloat())
        } else {
            GLES20.glUniform1i(u("uCopy"), 1)
            GLES20.glUniform1f(u("uHasCopy"), 0f)
        }
        GLES20.glUniform4f(u("uVideoRect"), o.videoX, o.videoY, o.videoW.coerceAtLeast(1f), o.videoH.coerceAtLeast(1f))
        GLES20.glUniform1f(u("uSurfH"), o.surfaceH.toFloat())
        GLES20.glUniform2f(u("uSurfSize"), o.surfaceW.toFloat(), o.surfaceH.toFloat())
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        val sw = o.surfaceW.toFloat()
        val sh = o.surfaceH.toFloat()
        for (pane in panes) {
            val p = pane.p
            GLES20.glUniform2f(u("uOrigin"), pane.x, pane.y)
            GLES20.glUniform2f(u("uSize"), pane.w, pane.h)
            GLES20.glUniform4f(u("uShape"), p.lens1CX, p.lens1CY, p.lens1HW, p.lens1HH)
            GLES20.glUniform4f(u("uRadii"), p.radius1TL, p.radius1TR, p.radius1BR, p.radius1BL)
            GLES20.glUniform1f(u("uBevel"), p.bevel)
            GLES20.glUniform1f(u("uRefract"), p.refract)
            GLES20.glUniform1f(u("uFalloff"), p.falloff)
            GLES20.glUniform1f(u("uRefractDir"), if (p.outward) 1f else -1f)
            GLES20.glUniform1f(u("uDispersion"), p.dispersion)
            GLES20.glUniform2f(u("uLight"), p.lightX, p.lightY)
            GLES20.glUniform1f(u("uSpec"), p.spec)
            GLES20.glUniform1f(u("uBandMax"), p.rimBandMax)
            GLES20.glUniform1f(u("uSat"), p.saturation / 100f)
            GLES20.glUniform1f(u("uDim"), p.dim)
            GLES20.glUniform1f(u("uClear"), if (p.clearInterior) 1f else 0f)
            GLES20.glUniform1f(u("uVeil"), p.clearVeil)
            GLES20.glUniform1f(u("uConvex"), if (p.convex) 1f else 0f)
            GLES20.glUniform1f(u("uAlpha"), pane.alpha)
            val x0 = pane.x / sw * 2f - 1f
            val x1 = (pane.x + pane.w) / sw * 2f - 1f
            val y0 = 1f - pane.y / sh * 2f
            val y1 = 1f - (pane.y + pane.h) / sh * 2f
            quad.position(0)
            quad.put(x0).put(y1).put(x1).put(y1).put(x0).put(y0).put(x1).put(y0)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun u(name: String): Int = loc.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    fun forget(o: GlassRimOverlay) {
        targets.remove(o)?.let { EGL14.eglDestroySurface(display, it.egl) }
        copyVersions.remove(o)
    }

    fun release() {
        for (t in targets.values) EGL14.eglDestroySurface(display, t.egl)
        targets.clear()
        if (program != 0) GLES20.glDeleteProgram(program)
        if (copyTex != 0) GLES20.glDeleteTextures(1, intArrayOf(copyTex), 0)
        for (t in slotTex.values) GLES20.glDeleteTextures(1, intArrayOf(t), 0)
        slotTex.clear()
        if (slotProgram != 0) GLES20.glDeleteProgram(slotProgram)
        slotProgram = 0
        program = 0
        copyTex = 0
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.w(GlassRimOverlay.TAG, "shader: " + GLES20.glGetShaderInfoLog(sh))
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun build() {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.w(GlassRimOverlay.TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; void main() { gl_Position = vec4(aPos, 0.0, 1.0); }"))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        program = p
        loc.clear()
    }

    private companion object {
        /** The lens of GlassLensRenderer (clear tier: rim only), sampling the video texture directly. */
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uVideo;
            uniform mat4 uTex;
            uniform sampler2D uCopy;
            uniform float uHasCopy;
            uniform vec2 uSurfSize;
            uniform float uS0On, uS1On, uS2On, uS0IsColor, uS1IsColor, uS2IsColor, uS0A, uS1A, uS2A;
            uniform vec4 uS0Rect, uS1Rect, uS2Rect, uS0Color, uS1Color, uS2Color;
            uniform sampler2D uS0Tex, uS1Tex, uS2Tex;

            // A moved backdrop view over c (premultiplied "over").
            vec3 over(vec3 c, vec2 s, float on, float isColor, vec4 rect, vec4 color, sampler2D tex, float a) {
                if (on < 0.5) return c;
                vec2 uv = (s - rect.xy) / rect.zw;
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return c;
                vec4 k = isColor > 0.5 ? color : texture2D(tex, uv) * a;
                return k.rgb + c * (1.0 - k.a);
            }
            uniform vec4 uCopyRect;
            uniform vec4 uVideoRect;
            uniform float uSurfH;
            uniform vec2 uOrigin;
            uniform vec2 uSize;
            uniform vec4 uShape;
            uniform vec4 uRadii;
            uniform float uBevel, uRefract, uFalloff, uRefractDir, uDispersion, uSpec, uBandMax, uSat, uDim, uClear, uVeil, uConvex, uAlpha;
            uniform vec2 uLight;

            vec3 behind(vec2 s) {
                vec2 vu = clamp((s - uVideoRect.xy) / uVideoRect.zw, 0.0, 1.0);
                vec3 c = texture2D(uVideo, (uTex * vec4(vu.x, 1.0 - vu.y, 0.0, 1.0)).xy).rgb;
                c = over(c, s, uS0On, uS0IsColor, uS0Rect, uS0Color, uS0Tex, uS0A);
                c = over(c, s, uS1On, uS1IsColor, uS1Rect, uS1Color, uS1Tex, uS1A);
                if (uHasCopy > 0.5) {
                    vec4 w = texture2D(uCopy, clamp((s - uCopyRect.xy) / uCopyRect.zw, 0.0, 1.0));
                    c = w.rgb + c * (1.0 - w.a);
                }
                c = over(c, s, uS2On, uS2IsColor, uS2Rect, uS2Color, uS2Tex, uS2A);
                return c;
            }

            float sdBox4(vec2 p, vec2 b, vec4 r) {
                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
                vec2 q = abs(p) - b + rx;
                return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
            }
            float lensSDF(vec2 p) { return sdBox4(p - uShape.xy, uShape.zw, uRadii); }

            // GlassGeometry.snellShift. Slope capped at 4 so the bend covers the lip.
            float snellShift(float t, float thick, float bev) {
                float u = 1.0 - clamp(t, 0.0, 1.0);
                float slope = min(u * u * 4.0 * (thick / max(bev, 1.0)), 4.0);
                float th1 = atan(slope);
                float th2 = asin(clamp(sin(th1) / 1.5, -1.0, 1.0));
                return thick * tan(th1 - th2);
            }

            void main() {
                vec2 s = vec2(gl_FragCoord.x, uSurfH - gl_FragCoord.y);
                vec2 p = s - uOrigin;
                if (p.x < 0.0 || p.y < 0.0 || p.x > uSize.x || p.y > uSize.y) { gl_FragColor = vec4(0.0); return; }
                float d = lensSDF(p);
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0) * uAlpha;
                if (cov <= 0.004) { gl_FragColor = vec4(0.0); return; }
                float bandW = clamp(uBevel * 0.3, 2.0, uBandMax);
                float lip = uConvex > 0.5 ? uBevel * 0.82 : uBevel;
                bool interior = -d > max(lip, 3.0 + bandW) + 1.0;
                if (interior && uClear > 0.5) {
                    float v = uVeil * cov;
                    gl_FragColor = vec4(vec3(0.5 * v), v);
                    return;
                }
                vec2 n = vec2(lensSDF(p + vec2(1.0, 0.0)) - lensSDF(p - vec2(1.0, 0.0)),
                              lensSDF(p + vec2(0.0, 1.0)) - lensSDF(p - vec2(0.0, 1.0)));
                float nl = length(n);
                n = nl > 0.0001 ? n / nl : vec2(0.0, -1.0);
                float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);
                float slope;
                float shift;
                if (interior) { slope = 0.0; shift = 0.0; }
                else if (uConvex > 0.5) {
                    shift = snellShift(t, uRefract, uBevel);
                    slope = shift / max(uRefract, 1.0);
                } else if (uFalloff > 0.001) {
                    float gB = pow(5.0, -uFalloff);
                    slope = (pow(1.0 + 4.0 * t, -uFalloff) - gB) / (1.0 - gB);
                    shift = uRefractDir * slope * uRefract;
                } else { slope = (1.0 - t) * (1.0 - t); shift = uRefractDir * slope * uRefract; }
                vec2 off = n * shift;
                vec3 col = vec3(behind(s + off * (1.0 - uDispersion * slope)).r,
                                behind(s + off).g,
                                behind(s + off * (1.0 + uDispersion * slope)).b);
                float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
                if (uSat <= 1.0) col = mix(vec3(lum), col, uSat);
                else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    col = clamp(mix(vec3(lum), col, 1.0 + (uSat - 1.0) * mix(0.3, 1.0, room * hl)), 0.0, 1.0);
                }
                col *= 1.0 - uDim;
                if (uConvex > 0.5) col *= 1.0 - 0.14 * smoothstep(0.12, 0.55, slope);
                float facing = dot(n, -uLight);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * uSpec;
                col = clamp(col + vec3(spec), 0.0, 1.0);
                if (uClear > 0.5) {
                    float a = uConvex > 0.5 ? smoothstep(0.03, 0.16, slope) : (1.0 - smoothstep(0.35, 1.0, t));
                    float v = uVeil * (1.0 - a);
                    vec3 rgb = clamp((col - vec3(spec)) * a + vec3(0.5 * v) + vec3(spec), 0.0, 1.0);
                    gl_FragColor = vec4(rgb * cov, clamp(a + v + spec, 0.0, 1.0) * cov);
                } else {
                    gl_FragColor = vec4(col * cov, cov);
                }
            }
        """
    }
}
