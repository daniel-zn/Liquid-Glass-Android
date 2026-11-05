package com.danielzn.liquidglass

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Pure geometry for [GlassPane] (no Android types, unit-tested on the JVM) for [LiquidGlassDrawable]. */
internal object GlassGeometry {

    /** Shapes under this short side (dp) get a proportionally smaller bend. */
    const val REF_DP = 110f

    /** The lens parameters for a [w] x [h] px pane with [cornerRadius] px corners. */
    fun lensParams(look: GlassLook, w: Float, h: Float, cornerRadius: Float, density: Float): GlassLensRenderer.LensParams {
        val hw = max(w / 2f, 1f)
        val hh = max(h / 2f, 1f)
        val r = cornerRadius.coerceIn(0f, min(hw, hh))
        val shortSide = max(min(w, h), 1f)
        val refPx = REF_DP * density
        // A convex lip needs room to bend (a pill's top and bottom lips stay apart). The older
        // profile packs its bend into a few pixels, so a narrower cap was enough.
        val bevelCap = if (look.convex) 0.46f else 0.3f
        val bevel = min(look.bevel, shortSide * bevelCap).coerceAtLeast(2f)
        val refract = max(0f, min(look.refraction, 0.7f * shortSide * min(1f, shortSide / refPx)))
        return GlassLensRenderer.LensParams(
            blurRadius = (max(look.blurRadius, 0f) * 2f).toInt() / 2f,
            shape1CX = hw, shape1CY = hh, shape1HW = hw, shape1HH = hh,
            radius1TL = r, radius1TR = r, radius1BR = r, radius1BL = r,
            lens1CX = hw, lens1CY = hh, lens1HW = hw, lens1HH = hh,
            rimSoft = 0f,
            shape2CX = 0f, shape2CY = 0f, shape2HW = 0f, shape2HH = 0f, radius2 = 0f,
            blendK = 0f,
            bevel = bevel,
            refract = refract,
            falloff = max(look.falloff, 0f),
            outward = look.convex,
            rimBandMax = min(6f, shortSide * 0.05f).coerceAtLeast(2f),
            dispersion = look.dispersion.coerceIn(0f, 0.9f),
            lightX = look.lightX, lightY = look.lightY,
            spec = max(look.specular, 0f),
            tint = look.tint,
            adaptiveTint = false,
            glassTint = look.glassTint,
            dim = look.dim.coerceIn(0f, 1f),
            saturation = look.saturation,
            press = 0f,
            touchX = 0f, touchY = 0f, touchAmp = 0f,
            backdropScale = look.backdropScale.coerceIn(0.1f, 1f),
            clearInterior = look.clearInterior,
            clearVeil = look.clearVeil.coerceIn(0f, 0.5f),
            convex = look.convex
        )
    }

    /**
     * How far a convex lip samples along its outward normal, in px. [t] is 0 at the outer edge and
     * 1 at the inner end of [bevel]; [thick] is the lip's thickness. Squircle height
     * (1 − (1−t)⁴)^¼, then Snell's law at index 1.5. The slope is clamped so the outer pixel stays
     * finite. The same formula is in the three lens shaders (GlassLensRenderer, LiveRim,
     * GlassRimOverlay); keep them identical.
     */
    fun snellShift(t: Float, thick: Float, bevel: Float): Float {
        if (thick <= 0f || bevel <= 0f) return 0f
        val v = (1f - t.coerceIn(0f, 1f)).coerceIn(0.004f, 1f)
        val base = max(1f - v * v * v * v, 1e-4f)
        val dhdu = (v * v * v) / base.pow(0.75f)
        val slope = min(dhdu * (thick / max(bevel, 1f)), 64f)
        val th1 = atan(slope)
        val th2 = asin((sin(th1) / 1.5f).coerceIn(-1f, 1f))
        return thick * tan(th1 - th2)
    }

    /**
     * Margin recorded around the pane (16 px steps). [reach] is how far an outward lip samples
     * past the shape; the blur also needs a few of its own radii.
     */
    fun lensMargin(blurRadius: Float, reach: Float = 0f): Int {
        val need = max(max(blurRadius * 3f, reach + 8f), 32f)
        return (((need.toInt() + 15) / 16) * 16).coerceAtLeast(32)
    }

    /** Delay before the next copy of a [SurfaceBackdrop] (ms); [fps] <= 0 means at once (one-shot). */
    fun nextCopyDelay(fps: Float, lastRequestAt: Long, now: Long): Long {
        if (fps <= 0f) return 0L
        val interval = (1000f / fps).toLong()
        return (lastRequestAt + interval - now).coerceAtLeast(0L)
    }
}
