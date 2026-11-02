package com.danielzn.liquidglass

import android.graphics.Canvas
import android.view.View

/**
 * A view that draws glass from a recorded backdrop ([LiquidGlassView], [GlassPane]).
 *
 * The renderers record [glassBackdropSource] (minus the glass itself) and, under it,
 * [glassUnderlay]: content that is on screen but not in the view tree, such as a video
 * [android.view.SurfaceView] (see [SurfaceBackdrop]). Glass views found while another
 * glass is sampling are hidden from that sample (glass does not refract glass).
 */
interface GlassSurface {
    /** The view whose drawing is the backdrop (null: nothing to sample). */
    val glassBackdropSource: View?

    /** Drawn under the backdrop source, in screen coordinates; null for none. */
    val glassUnderlay: BackdropUnderlay?

    /** True while this glass's own backdrop is being recorded: its draw() must draw nothing. */
    var glassCapturing: Boolean

    /** Bumped to force a re-record (the backdrop changed in a way the glass can't see). */
    val glassBackdropVersion: Int get() = 0
}

/**
 * Screen-space content drawn under a glass's backdrop: what the window shows behind the
 * view tree (a video surface, a wallpaper). [drawUnderlay] gets a canvas whose origin is the
 * screen's top-left corner; draw only what is cheap (a cached bitmap), it runs on every
 * re-record of every glass that uses it.
 */
interface BackdropUnderlay {
    fun drawUnderlay(canvas: Canvas)

    /** Changes whenever what [drawUnderlay] draws changes (a new copy): glass re-records only then. */
    val underlayVersion: Int get() = 0
}

/** A backdrop source that invalidates the glass showing it (PixelCopy copies, a decoded proxy video). */
interface GlassAttachable {
    fun attach(glass: android.view.View)
    fun detach(glass: android.view.View)
    /** An attached glass drew (it may have just been shown again). */
    fun onGlassDrawn() {}
}
