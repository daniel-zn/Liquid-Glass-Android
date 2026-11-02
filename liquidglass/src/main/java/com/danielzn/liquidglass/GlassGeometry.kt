package com.danielzn.liquidglass

import kotlin.math.max
import kotlin.math.min

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
        val bevel = min(look.bevel, shortSide * 0.3f).coerceAtLeast(2f)
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
            outward = false,
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
            clearVeil = look.clearVeil.coerceIn(0f, 0.5f)
        )
    }

    /** Margin recorded around the pane so the blur reads real content at its edges (16 px steps). */
    fun lensMargin(blurRadius: Float): Int {
        val need = max(blurRadius * 3f, 32f)
        return (((need.toInt() + 15) / 16) * 16).coerceAtLeast(32)
    }

    /** Delay before the next copy of a [SurfaceBackdrop] (ms); [fps] <= 0 means at once (one-shot). */
    fun nextCopyDelay(fps: Float, lastRequestAt: Long, now: Long): Long {
        if (fps <= 0f) return 0L
        val interval = (1000f / fps).toLong()
        return (lastRequestAt + interval - now).coerceAtLeast(0L)
    }
}
