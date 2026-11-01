package com.danielzn.liquidglass

/**
 * How a [LiquidGlassDrawable] / [GlassPane] looks; lengths in pixels. Plain fields (Java
 * friendly); after changing a look that is in use, set it again ([LiquidGlassDrawable.setLook]).
 */
class GlassLook @JvmOverloads constructor(
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
    @JvmField var lightY: Float = -0.8f,
    /**
     * Records and blurs the backdrop at this scale (0.25 = a sixteenth of the pixels), then
     * upscales it under the full-resolution lens: much cheaper blur on weak GPUs, the rim stays
     * sharp. 1 = full resolution.
     */
    @JvmField var backdropScale: Float = 1f
) {
    fun copy(): GlassLook = GlassLook(
        blurRadius, bevel, refraction, falloff, dispersion, specular, saturation, tint, dim,
        glassTint, lightX, lightY, backdropScale
    )

    fun set(o: GlassLook) {
        blurRadius = o.blurRadius; bevel = o.bevel; refraction = o.refraction; falloff = o.falloff
        dispersion = o.dispersion; specular = o.specular; saturation = o.saturation; tint = o.tint
        dim = o.dim; glassTint = o.glassTint; lightX = o.lightX; lightY = o.lightY
        backdropScale = o.backdropScale
    }
}
