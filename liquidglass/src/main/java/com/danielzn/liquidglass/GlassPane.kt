package com.danielzn.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.min

/**
 * A lean Liquid Glass container for screens that must stay idle when nothing moves (TV, kiosks,
 * launchers): the same optical model as [LiquidGlassView]'s lens pipeline (refraction at the
 * rim, dispersion, rim light, blur, tint), without its touch, sensor, luminance-meter and CPU
 * pipelines.
 *
 * - Draws only when something changed: a property, its size, its position on screen (checked
 *   in a pre-draw listener, so only on frames the window draws anyway), [invalidateBackdrop]
 *   (the caller's backdrop content changed) or a new frame from a [SurfaceBackdrop] it is
 *   attached to. A static screen renders nothing.
 * - No hardware layer and no per-frame allocation: the lens parameters are rebuilt only when
 *   a property or the size changes.
 * - API 33+ (AGSL). Below that, or when the shader can't compile, it fills its shape with
 *   [fallbackColor].
 *
 * The backdrop is [backdropSource] (minus this view and any glass in it) with
 * [backdropUnderlay] under it. Choose a source that holds only what is behind the glass
 * (a background container), so foreground animations don't re-render the glass.
 */
open class GlassPane @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), GlassSurface {

    /** The look, in pixels where it is a length. Set with [setLook] or the setters below. */
    class Look(
        /** Gaussian blur radius of the backdrop (px). */
        @JvmField var blurRadius: Float = 24f,
        /** Width of the bevel band at the rim (px). */
        @JvmField var bevel: Float = 24f,
        /** How far the rim bends the backdrop (px, sampled inward). */
        @JvmField var refraction: Float = 40f,
        /** Refraction profile: > 0 inverse-power falloff (bend piles up at the rim), 0 = square bevel. */
        @JvmField var falloff: Float = 2f,
        /** Chromatic dispersion at the rim, 0-0.9. */
        @JvmField var dispersion: Float = 0.1f,
        /** Rim light strength, 0 = none. */
        @JvmField var specular: Float = 1f,
        /** Backdrop saturation, 100 = unchanged. */
        @JvmField var saturation: Float = 120f,
        /** Mixed over the backdrop (straight-alpha ARGB: the alpha is the amount). */
        @JvmField var tint: Int = 0,
        /** Darkens the result, 0-1 (after the tint). */
        @JvmField var dim: Float = 0f,
        /** Coloured-glass body tint (absorption + scatter), straight-alpha ARGB, 0 = none. */
        @JvmField var glassTint: Int = 0,
        /** Light direction (unit vector, screen space; default from the top left). */
        @JvmField var lightX: Float = -0.6f,
        @JvmField var lightY: Float = -0.8f
    ) {
        fun copy(): Look = Look(
            blurRadius, bevel, refraction, falloff, dispersion, specular, saturation, tint, dim,
            glassTint, lightX, lightY
        )
    }

    private val look = Look()

    /** Corner radius (px); anything over half the short side is a capsule. */
    var cornerRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            paramsDirty = true
            invalidate()
        }

    /** Drawn when the lens can't be (API < 33, no backdrop, shader failure). */
    var fallbackColor: Int = 0x99202024.toInt()
        set(value) {
            if (field == value) return
            field = value
            fallbackPaint.color = value
            invalidate()
        }

    /** The view drawn as the backdrop (null: the parent). Not this view or its descendants. */
    var backdropSource: View? = null
        set(value) {
            if (field === value) return
            require(value == null || !isSelfOrDescendant(value)) { "backdropSource can't be the glass or inside it" }
            field = value
            invalidate()
        }

    /** Screen-space content under the backdrop (see [SurfaceBackdrop]). */
    var backdropUnderlay: BackdropUnderlay? = null
        set(value) {
            if (field === value) return
            (field as? SurfaceBackdrop)?.detach(this)
            field = value
            if (isAttachedToWindow) (value as? SurfaceBackdrop)?.attach(this)
            invalidate()
        }

    /** Re-record when this view or its source moves on screen (default true). */
    var tracksPosition: Boolean = true

    override val glassBackdropSource: View?
        get() = backdropSource ?: parent as? View
    override val glassUnderlay: BackdropUnderlay?
        get() = backdropUnderlay
    override var glassCapturing: Boolean = false

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackColor }
    private val fallbackRect = RectF()
    private var lens: Any? = null  // GlassLensRenderer (API 33+); Any so this class loads below 33
    private var params: GlassLensRenderer.LensParams? = null
    private var paramsDirty = true
    private val density = resources.displayMetrics.density

    private val selfLoc = IntArray(2)
    private val lastSelfLoc = IntArray(2)
    private val sourceLoc = IntArray(2)
    private val lastSourceLoc = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (tracksPosition && isShown) checkMoved()
        true
    }

    init {
        setWillNotDraw(false)
    }

    /** Copies [value] into this glass's look and redraws. */
    fun setLook(value: Look) {
        look.blurRadius = value.blurRadius
        look.bevel = value.bevel
        look.refraction = value.refraction
        look.falloff = value.falloff
        look.dispersion = value.dispersion
        look.specular = value.specular
        look.saturation = value.saturation
        look.tint = value.tint
        look.dim = value.dim
        look.glassTint = value.glassTint
        look.lightX = value.lightX
        look.lightY = value.lightY
        paramsDirty = true
        invalidate()
    }

    /** A copy of the current look. */
    fun getLook(): Look = look.copy()

    /** The backdrop's content changed (something the glass can't see change): redraw once. */
    fun invalidateBackdrop() = invalidate()

    /** True when the AGSL lens draws (false: [fallbackColor] is used). */
    val isLensActive: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (lens as? GlassLensRenderer)?.isAvailable != false

    override fun draw(canvas: Canvas) {
        if (glassCapturing) return
        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (!drawLens(canvas)) {
            fallbackRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val r = min(cornerRadius, min(width, height) / 2f)
            canvas.drawRoundRect(fallbackRect, r, r, fallbackPaint)
        }
        rememberPosition()
    }

    private fun drawLens(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!canvas.isHardwareAccelerated) return false
        if (glassBackdropSource == null) return false
        val renderer = (lens as? GlassLensRenderer) ?: GlassLensRenderer().also { lens = it }
        if (!renderer.isAvailable) return false
        val p = currentParams()
        return renderer.draw(canvas, this, p, lensMargin(p.blurRadius))
    }

    private fun currentParams(): GlassLensRenderer.LensParams {
        val cached = params
        if (cached != null && !paramsDirty) return cached
        val p = GlassGeometry.lensParams(look, width.toFloat(), height.toFloat(), cornerRadius, density)
        params = p
        paramsDirty = false
        return p
    }

    private fun lensMargin(blur: Float): Int = GlassGeometry.lensMargin(blur)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paramsDirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        (backdropUnderlay as? SurfaceBackdrop)?.attach(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        (backdropUnderlay as? SurfaceBackdrop)?.detach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (lens as? GlassLensRenderer)?.release()
        lens = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        (backdropUnderlay as? SurfaceBackdrop)?.onPaneVisibilityChanged()
    }

    private fun rememberPosition() {
        getLocationOnScreen(lastSelfLoc)
        val source = glassBackdropSource
        if (source != null) source.getLocationOnScreen(lastSourceLoc) else { lastSourceLoc[0] = 0; lastSourceLoc[1] = 0 }
    }

    private fun checkMoved() {
        getLocationOnScreen(selfLoc)
        val source = glassBackdropSource
        if (source != null) source.getLocationOnScreen(sourceLoc) else { sourceLoc[0] = 0; sourceLoc[1] = 0 }
        if (selfLoc[0] != lastSelfLoc[0] || selfLoc[1] != lastSelfLoc[1] ||
            sourceLoc[0] != lastSourceLoc[0] || sourceLoc[1] != lastSourceLoc[1]
        ) {
            invalidate()
        }
    }

    private fun isSelfOrDescendant(v: View): Boolean {
        if (v === this) return true
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}
