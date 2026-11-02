package com.danielzn.liquidglass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.min

/**
 * Liquid Glass as a [Drawable]: set it as any view's background and that view becomes glass
 * (the lens pipeline: refraction and dispersion at the rim, rim light, blur, tint) over
 * [backdropSource] with [backdropUnderlay] under it. No layout changes needed.
 *
 * - Draws only when something changed: a property, its bounds, the host view's position on
 *   screen (a pre-draw check, only on frames the window draws anyway), [invalidateBackdrop],
 *   or a new frame from a [SurfaceBackdrop] (attached while the host is attached).
 * - No per-frame allocation; the lens parameters are rebuilt only on a property or size change.
 * - API 33+ (AGSL). Otherwise, or with no backdrop, [drawFallback] paints the shape
 *   ([fallbackColor] by default; override it for a richer fallback).
 *
 * The drawable needs its host: the view it is the background of ([getCallback]).
 */
open class LiquidGlassDrawable @JvmOverloads constructor(
    cornerRadius: Float = 0f,
    look: GlassLook = GlassLook()
) : Drawable(), GlassSurface {

    private val look = look.copy()

    /** Corner radius (px); anything over half the short side is a capsule. */
    var cornerRadius: Float = cornerRadius
        set(value) {
            if (field == value) return
            field = value
            paramsDirty = true
            invalidateSelf()
        }

    /** Painted by [drawFallback] when the lens can't draw. */
    var fallbackColor: Int = 0x99202024.toInt()
        set(value) {
            if (field == value) return
            field = value
            fallbackPaint.color = value
            invalidateSelf()
        }

    /** The view drawn as the backdrop (null: the host's parent). */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            field = value
            invalidateSelf()
        }

    /** Screen-space content under the backdrop (see [SurfaceBackdrop]). */
    var backdropUnderlay: BackdropUnderlay? = null
        set(value) {
            if (field === value) return
            val h = host
            if (h != null) (field as? GlassAttachable)?.detach(h)
            field = value
            if (h != null && h.isAttachedToWindow) (value as? GlassAttachable)?.attach(h)
            invalidateSelf()
        }

    /** Re-record when the host moves on screen (default true). */
    var tracksPosition: Boolean = true

    /** Glass off: only [drawFallback] (e.g. Reduce Transparency, or a measurement). */
    var lensEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override val glassBackdropSource: View?
        get() = backdropSource ?: host?.parent as? View
    override val glassUnderlay: BackdropUnderlay?
        get() = backdropUnderlay
    override var glassCapturing: Boolean = false

    /** True when the last draw was lens glass (false: the fallback). */
    var isLensActive: Boolean = false
        private set

    protected val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackColor }
    private val shapeRect = RectF()
    private var lens: Any? = null  // GlassLensRenderer on API 33+ (Any so this class loads below 33)
    private var params: GlassLensRenderer.LensParams? = null
    private var paramsDirty = true
    private var alpha = 255

    private var trackedHost: View? = null
    private val hostLoc = IntArray(2)
    private val lastHostLoc = IntArray(2)
    private val sourceLoc = IntArray(2)
    private val lastSourceLoc = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val h = trackedHost
        if (h != null && tracksPosition && h.isShown) checkMoved(h)
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(preDraw)
            (backdropUnderlay as? GlassAttachable)?.attach(v)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnPreDrawListener(preDraw)
            (backdropUnderlay as? GlassAttachable)?.detach(v)
            releaseLens()
        }
    }

    /**
     * The view this is drawn in: the callback, or, inside a container drawable (a
     * StateListDrawable for focus states, a LayerDrawable), the container's own host.
     */
    val host: View?
        get() {
            var cb: Any? = callback
            var depth = 0
            while (cb is Drawable && depth < 8) {
                cb = cb.callback
                depth++
            }
            return cb as? View
        }

    /** Copies [value] into this glass's look and redraws. */
    fun setLook(value: GlassLook) {
        look.set(value)
        paramsDirty = true
        invalidateSelf()
    }

    /** A copy of the current look. */
    fun getLook(): GlassLook = look.copy()

    /** The backdrop's content changed in a way the glass can't see: redraw once. */
    fun invalidateBackdrop() {
        backdropVersion++
        invalidateSelf()
    }

    private var backdropVersion = 0
    override val glassBackdropVersion: Int get() = backdropVersion

    /**
     * Called at the start of every draw: a subclass may set [backdropSource] and
     * [backdropUnderlay] here (e.g. from a registry), without that causing another draw.
     */
    protected open fun onPrepareBackdrop(host: View?) {}

    private var preparing = false

    override fun invalidateSelf() {
        if (!preparing) super.invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (glassCapturing) return
        val h = host
        preparing = true
        try {
            onPrepareBackdrop(h)
        } finally {
            preparing = false
        }
        if (h != null && trackedHost !== h) track(h)
        val b = bounds
        if (b.isEmpty) return
        // A fade (state changes, a parent's StateListDrawable) sets our alpha: the lens honours it
        // through a layer, only while it is under 255.
        val layer = if (alpha < 255 && lensEnabled) canvas.saveLayerAlpha(b.left.toFloat(), b.top.toFloat(),
            b.right.toFloat(), b.bottom.toFloat(), alpha) else -1
        isLensActive = lensEnabled && h != null && drawLens(canvas, h)
        if (layer >= 0) canvas.restoreToCount(layer)
        if (!isLensActive) drawFallback(canvas, cornerRadiusFor(b.width(), b.height()))
        if (h != null) rememberPosition(h)
        (backdropUnderlay as? GlassAttachable)?.onGlassDrawn()
    }

    /** Paints the shape when there is no lens glass; [radius] is already capped (capsules). */
    protected open fun drawFallback(canvas: Canvas, radius: Float) {
        shapeRect.set(bounds)
        canvas.drawRoundRect(shapeRect, radius, radius, fallbackPaint)
    }

    fun cornerRadiusFor(w: Int, h: Int): Float = min(cornerRadius, min(w, h) / 2f).coerceAtLeast(0f)

    private fun drawLens(canvas: Canvas, h: View): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!canvas.isHardwareAccelerated) return false
        if (glassBackdropSource == null) return false
        val renderer = (lens as? GlassLensRenderer) ?: GlassLensRenderer().also { lens = it }
        if (!renderer.isAvailable) return false
        val b = bounds
        val p = currentParams(b.width(), b.height(), h.resources.displayMetrics.density)
        return renderer.draw(canvas, h, this, b.left, b.top, b.width(), b.height(), p, GlassGeometry.lensMargin(p.blurRadius))
    }

    private fun currentParams(w: Int, hgt: Int, density: Float): GlassLensRenderer.LensParams {
        val cached = params
        if (cached != null && !paramsDirty) return cached
        val p = GlassGeometry.lensParams(look, w.toFloat(), hgt.toFloat(), cornerRadius, density)
        params = p
        paramsDirty = false
        return p
    }

    private var lastW = -1
    private var lastH = -1

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        // Moving (scrolling) keeps the shape: the parameters only depend on the size.
        if (bounds.width() != lastW || bounds.height() != lastH) {
            lastW = bounds.width()
            lastH = bounds.height()
            paramsDirty = true
        }
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b, cornerRadiusFor(b.width(), b.height()))
        outline.alpha = 0f  // the glass is translucent: elevation shadows stay soft
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        fallbackPaint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fallbackPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun track(h: View) {
        trackedHost?.let { old ->
            old.removeOnAttachStateChangeListener(attachListener)
            if (old.isAttachedToWindow) attachListener.onViewDetachedFromWindow(old)
        }
        trackedHost = h
        h.addOnAttachStateChangeListener(attachListener)
        if (h.isAttachedToWindow) attachListener.onViewAttachedToWindow(h)
    }

    private fun releaseLens() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (lens as? GlassLensRenderer)?.release()
        lens = null
    }

    private fun rememberPosition(h: View) {
        h.getLocationOnScreen(lastHostLoc)
        val source = glassBackdropSource
        if (source != null) source.getLocationOnScreen(lastSourceLoc) else { lastSourceLoc[0] = 0; lastSourceLoc[1] = 0 }
    }

    private fun checkMoved(h: View) {
        h.getLocationOnScreen(hostLoc)
        val source = glassBackdropSource
        if (source != null) source.getLocationOnScreen(sourceLoc) else { sourceLoc[0] = 0; sourceLoc[1] = 0 }
        if (hostLoc[0] != lastHostLoc[0] || hostLoc[1] != lastHostLoc[1] ||
            sourceLoc[0] != lastSourceLoc[0] || sourceLoc[1] != lastSourceLoc[1]
        ) {
            invalidateSelf()
        }
    }
}
