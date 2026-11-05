package com.danielzn.liquidglass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Every live glass rim of a window in one view: a full-window view under the content and over the backdrop.
 * The glass drawables draw nothing themselves (they report to [panes], a [GlassRimOverlay] used as a list,
 * no surface); this draws each pane's rim and veil with the [LiveRim] shader from the current video picture.
 * A new picture invalidates only this view; with the window's content cached in a hardware layer, a frame
 * is that layer's blit plus the rims, not a re-render of the view tree.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RimView(context: Context, val panes: GlassRimOverlay, val rim: LiveRim) : View(context) {

    private val shader = RuntimeShader(LiveRim.AGSL)
    private val paint = Paint()
    @Volatile var draws = 0; private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        panes.onChanged = Runnable { postInvalidateOnAnimation() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rim.attach(this)   // a new picture invalidates this view (only)
    }

    override fun onDetachedFromWindow() {
        rim.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        for (p in panes.panes) rim.drawAt(canvas, p.x, p.y, p.w, p.h, p.p, p.alpha, shader, paint)
        draws++
    }
}
