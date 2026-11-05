package com.danielzn.liquidglass

import android.graphics.RenderNode
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * One backdrop for all the glass controls on a glass panel (a control center of tiles, chips and
 * buttons): what is behind the panel plus the panel's own material, recorded once per panel and
 * rendered once into a GPU layer, which every control on it then samples. Without it each control
 * records (and the GPU rasterizes) its own copy of the window under it: a dozen of them per change.
 * Controls on the panel don't refract each other (glass never refracts glass), which this keeps.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object SharedBackdrop {

    /** Off: every control records its own backdrop, as before (measuring). */
    @JvmField @Volatile var enabled = true

    class Entry(val node: RenderNode) {
        var sig = 0
        var underlayV = 0
        var forceV = 0
        var anchorX = Float.NaN
        var anchorY = Float.NaN
        var w = -1
        var h = -1
        var scale = 0f
    }

    private val entries = WeakHashMap<View, WeakHashMap<View, Entry>>()
    private val loc = IntArray(2)
    private val srcLoc = IntArray(2)
    private val capture = BackdropCapture()

    /** The glass panel [glass] sits on (its nearest ancestor below [source] with glass as its background). */
    fun anchorFor(glass: View, source: View): View? {
        if (!enabled) return null
        var p = glass.parent
        while (p is View && p !== source) {
            if (p.background is GlassSurface) return p
            p = p.parent
        }
        return null
    }

    /**
     * The panel's shared backdrop at [scale] (node coordinates = source pixels × scale), re-recorded only
     * when what it depends on changed; the GPU layer re-renders on its own when referenced views change.
     */
    fun get(source: View, anchor: View, underlay: BackdropUnderlay?, forceV: Int, sig: Int, scale: Float): RenderNode {
        val byAnchor = entries.getOrPut(source) { WeakHashMap() }
        val e = byAnchor.getOrPut(anchor) {
            Entry(RenderNode("LiquidGlassSharedBackdrop").apply { setUseCompositingLayer(true, null) })
        }
        source.getLocationOnScreen(srcLoc)
        anchor.getLocationOnScreen(loc)
        val ax = (loc[0] - srcLoc[0]).toFloat()
        val ay = (loc[1] - srcLoc[1]).toFloat()
        val uv = underlay?.underlayVersion ?: 0
        if (e.sig == sig && e.underlayV == uv && e.forceV == forceV && e.anchorX == ax && e.anchorY == ay &&
            e.w == source.width && e.h == source.height && e.scale == scale) return e.node
        e.sig = sig; e.underlayV = uv; e.forceV = forceV; e.anchorX = ax; e.anchorY = ay
        e.w = source.width; e.h = source.height; e.scale = scale
        val nw = ceil(source.width * scale).toInt().coerceAtLeast(1)
        val nh = ceil(source.height * scale).toInt().coerceAtLeast(1)
        e.node.setPosition(0, 0, nw, nh)
        GlassStats.sharedRecords++
        val c = e.node.beginRecording(nw, nh)
        try {
            c.scale(scale, scale)
            // Everything behind the panel (the panel's branch left out) ...
            capture.draw(c, source, anchor, underlay)
            // ... and the panel's own material over it, without its controls.
            val bg = anchor.background
            if (bg != null) {
                val s = c.save()
                c.translate(ax, ay)
                bg.draw(c)
                c.restoreToCount(s)
            }
        } finally {
            e.node.endRecording()
        }
        return e.node
    }
}
