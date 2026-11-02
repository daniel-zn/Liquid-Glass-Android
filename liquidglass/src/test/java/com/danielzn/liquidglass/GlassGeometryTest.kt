package com.danielzn.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassGeometryTest {

    private val density = 2f

    @Test
    fun capsuleRadiusIsCappedAtHalfTheShortSide() {
        val p = GlassGeometry.lensParams(GlassLook(), 300f, 80f, 999f, density)
        assertEquals(40f, p.radius1TL, 0f)
        assertEquals(40f, p.radius1BR, 0f)
        assertEquals(150f, p.shape1HW, 0f)
        assertEquals(40f, p.shape1HH, 0f)
    }

    @Test
    fun largePanelKeepsTheRequestedLens() {
        val look = GlassLook(bevel = 30f, refraction = 50f)
        val p = GlassGeometry.lensParams(look, 1200f, 800f, 56f, density)
        assertEquals(30f, p.bevel, 0f)
        assertEquals(50f, p.refract, 0f)
        assertEquals(56f, p.radius1TL, 0f)
    }

    @Test
    fun smallControlGetsAProportionallySmallerBend() {
        val look = GlassLook(bevel = 30f, refraction = 50f)
        // 48 px short side at density 2 (24 dp, under the 110 dp reference)
        val p = GlassGeometry.lensParams(look, 48f, 48f, 24f, density)
        assertEquals(48f * 0.3f, p.bevel, 0.001f)
        val cap = 0.7f * 48f * (48f / (110f * density))
        assertEquals(cap, p.refract, 0.001f)
        assertTrue(p.refract < 10f)
    }

    @Test
    fun valuesAreClampedToTheShaderRanges() {
        val look = GlassLook(dispersion = 3f, dim = 2f, specular = -1f, blurRadius = -5f, falloff = -1f)
        val p = GlassGeometry.lensParams(look, 400f, 400f, 0f, density)
        assertEquals(0.9f, p.dispersion, 0f)
        assertEquals(1f, p.dim, 0f)
        assertEquals(0f, p.spec, 0f)
        assertEquals(0f, p.blurRadius, 0f)
        assertEquals(0f, p.falloff, 0f)
        assertEquals(false, p.outward)
        assertEquals(false, p.adaptiveTint)
    }

    @Test
    fun blurIsQuantisedToHalfPixelsSoTinyChangesDontRebuildTheEffect() {
        val a = GlassGeometry.lensParams(GlassLook(blurRadius = 24.1f), 400f, 400f, 0f, density)
        val b = GlassGeometry.lensParams(GlassLook(blurRadius = 24.3f), 400f, 400f, 0f, density)
        assertEquals(a, b)
    }

    @Test
    fun sameInputsGiveEqualParams() {
        val a = GlassGeometry.lensParams(GlassLook(), 640f, 360f, 32f, density)
        val b = GlassGeometry.lensParams(GlassLook(), 640f, 360f, 32f, density)
        assertEquals(a, b)
    }

    @Test
    fun marginCoversThreeSigmaInSixteenPixelSteps() {
        assertEquals(32, GlassGeometry.lensMargin(0f))
        assertEquals(32, GlassGeometry.lensMargin(10f))
        assertEquals(80, GlassGeometry.lensMargin(25f))
        assertEquals(0, GlassGeometry.lensMargin(25f) % 16)
    }

    @Test
    fun copyDelayFollowsTheFrameRate() {
        assertEquals(100L, GlassGeometry.nextCopyDelay(10f, 1000L, 1000L))
        assertEquals(40L, GlassGeometry.nextCopyDelay(10f, 1000L, 1060L))
        assertEquals(0L, GlassGeometry.nextCopyDelay(10f, 1000L, 1500L))
        assertEquals(0L, GlassGeometry.nextCopyDelay(0f, 1000L, 1000L))
    }

    @Test
    fun backdropScaleIsClampedAndCompared() {
        val full = GlassGeometry.lensParams(GlassLook(), 400f, 400f, 0f, density)
        assertEquals(1f, full.backdropScale, 0f)
        val q = GlassGeometry.lensParams(GlassLook(backdropScale = 0.25f), 400f, 400f, 0f, density)
        assertEquals(0.25f, q.backdropScale, 0f)
        assertTrue(full != q)
        assertEquals(0.1f, GlassGeometry.lensParams(GlassLook(backdropScale = 0f), 400f, 400f, 0f, density).backdropScale, 0f)
        assertEquals(1f, GlassGeometry.lensParams(GlassLook(backdropScale = 3f), 400f, 400f, 0f, density).backdropScale, 0f)
    }

    @Test
    fun clearInteriorReachesTheShader() {
        assertEquals(false, GlassGeometry.lensParams(GlassLook(), 400f, 400f, 0f, density).clearInterior)
        val c = GlassGeometry.lensParams(GlassLook(clearInterior = true), 400f, 400f, 0f, density)
        assertEquals(true, c.clearInterior)
        assertTrue(c != GlassGeometry.lensParams(GlassLook(), 400f, 400f, 0f, density))
    }

    @Test
    fun clearVeilIsPassedAndClamped() {
        assertEquals(0f, GlassGeometry.lensParams(GlassLook(), 400f, 400f, 0f, density).clearVeil, 0f)
        assertEquals(0.07f, GlassGeometry.lensParams(GlassLook(clearInterior = true, clearVeil = 0.07f), 400f, 400f, 0f, density).clearVeil, 0f)
        assertEquals(0.5f, GlassGeometry.lensParams(GlassLook(clearVeil = 3f), 400f, 400f, 0f, density).clearVeil, 0f)
        assertEquals(0f, GlassGeometry.lensParams(GlassLook(clearVeil = -1f), 400f, 400f, 0f, density).clearVeil, 0f)
    }
}
