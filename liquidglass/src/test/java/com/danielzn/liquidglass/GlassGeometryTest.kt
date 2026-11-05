package com.danielzn.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun convexLipIsANarrowBezel() {
        val look = GlassLook(bevel = 64f, refraction = 48f, convex = true)
        val p = GlassGeometry.lensParams(look, 400f, 400f, 40f, density)
        assertEquals(64f, p.bevel, 0.01f)
        assertEquals(48f, p.refract, 0.01f)
        assertTrue(p.convex)
        assertTrue(p.outward)
        val edge = GlassGeometry.snellShift(0f, 48f, 64f)
        val mid = GlassGeometry.snellShift(0.35f, 48f, 64f)
        val inner = GlassGeometry.snellShift(0.55f, 48f, 64f)
        // A visible bend at the outer edge, not a hairline and not a second copy of a glyph.
        assertTrue(edge > 10f)
        assertTrue(edge < 22f)
        assertTrue(mid < edge * 0.25f)
        assertTrue(inner < 1.5f)
        assertEquals(0f, GlassGeometry.snellShift(1f, 48f, 64f), 0.05f)
        assertEquals(0f, GlassGeometry.snellShift(0f, 0f, 64f), 0f)
    }

    @Test
    fun aPanelSizedLipBendsAndThenMeetsTheHole() {
        // The thickness a panel actually uses: an edge you can see, gone before the clear inside,
        // so the lip cannot draw a second copy of a glyph.
        val edge = GlassGeometry.snellShift(0f, 24f, 40f)
        val mid = GlassGeometry.snellShift(0.3f, 24f, 40f)
        val inner = GlassGeometry.snellShift(0.5f, 24f, 40f)
        assertTrue(edge > 6f)
        assertTrue(edge < 11f)
        assertTrue(mid < 2f)
        assertTrue(inner < 0.5f)
    }

    @Test
    fun layerCropHugsThePanes() {
        val full = GlassGeometry.layerCrop(1920, 1080, listOf(floatArrayOf(0f, 0f, 1920f, 1080f)))
        assertEquals(0, full!![0])
        assertEquals(0, full[1])
        assertEquals(1920, full[2])
        assertEquals(1080, full[3])
        val chip = GlassGeometry.layerCrop(1920, 1080, listOf(floatArrayOf(1400f, 120f, 200f, 80f)))
        assertEquals(1344, chip!![0])
        assertEquals(64, chip[1])
        assertEquals(1664, chip[2])
        assertEquals(256, chip[3])
        val dock = GlassGeometry.layerCrop(1920, 1080, listOf(floatArrayOf(80f, 860f, 1760f, 180f)))
        assertTrue(dock!![1] > 600)
        assertEquals(1080, dock[3])
        assertNull(GlassGeometry.layerCrop(1920, 1080, emptyList()))
    }

    @Test
    fun marginCoversAnOutwardLip() {
        assertEquals(32, GlassGeometry.lensMargin(0f, 0f))
        assertEquals(96, GlassGeometry.lensMargin(0f, 80f))
        assertEquals(80, GlassGeometry.lensMargin(25f, 0f))
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
