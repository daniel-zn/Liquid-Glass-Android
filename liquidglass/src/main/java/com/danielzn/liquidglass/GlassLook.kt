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
    /**
     * How far the rim bends the backdrop, in px. Sampled inward, unless [convex]: then this is the
     * lip's thickness (how far the edge reaches out for the picture).
     */
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
    /**
     * Light direction (unit vector, screen space). Default straight from above: the rim light sits on the
     * top and bottom edges, centred and fading toward the corners (as Apple's current glass has it; it
     * used to be the top-left and bottom-right corners).
     */
    @JvmField var lightX: Float = 0f,
    @JvmField var lightY: Float = -1f,
    /**
     * Records and blurs the backdrop at this scale (0.25 = a sixteenth of the pixels), then
     * upscales it under the full-resolution lens: much cheaper blur on weak GPUs, the rim stays
     * sharp. 1 = full resolution.
     */
    @JvmField var backdropScale: Float = 1f,
    /**
     * Clear interior: only the rim is drawn (refraction, dispersion, rim light, fading out inwards); the
     * inside is transparent, so the real content behind shows at its own brightness and frame rate
     * (e.g. a video on its own display plane). The backdrop then only matters for the rim band.
     */
    @JvmField var clearInterior: Boolean = false,
    /**
     * With [clearInterior]: a faint neutral grey over the transparent inside (its opacity, 0-0.5; e.g.
     * 0.07): what's behind is lifted and compressed a little toward mid-grey, no colour, no blur.
     */
    @JvmField var clearVeil: Float = 0f,
    /**
     * Convex lip. The rim is a squircle of glass: Snell's law at index 1.5, sampling **outward**, so
     * what sits just outside the shape bends in along the edge and the bend dies out before the
     * inside (which can stay clear, [clearInterior]). [refraction] is the lip's thickness and [bevel]
     * is how far inward it extends. Off: the older inverse-power profile, which samples inward and
     * packs the whole bend into a thin ring.
     */
    @JvmField var convex: Boolean = false
) {
    fun copy(): GlassLook = GlassLook(
        blurRadius, bevel, refraction, falloff, dispersion, specular, saturation, tint, dim,
        glassTint, lightX, lightY, backdropScale, clearInterior, clearVeil, convex
    )

    fun set(o: GlassLook) {
        blurRadius = o.blurRadius; bevel = o.bevel; refraction = o.refraction; falloff = o.falloff
        dispersion = o.dispersion; specular = o.specular; saturation = o.saturation; tint = o.tint
        dim = o.dim; glassTint = o.glassTint; lightX = o.lightX; lightY = o.lightY
        backdropScale = o.backdropScale; clearInterior = o.clearInterior; clearVeil = o.clearVeil
        convex = o.convex
    }
}
