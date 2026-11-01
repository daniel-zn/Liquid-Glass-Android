package com.danielzn.liquidglass

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * A glass container: a FrameLayout whose background is a [LiquidGlassDrawable] (see there for
 * when it draws and what it costs). Children are the content on the glass.
 */
open class GlassPane @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** The glass itself (also this view's background). */
    val glass = LiquidGlassDrawable()

    init {
        background = glass
    }

    var cornerRadius: Float
        get() = glass.cornerRadius
        set(value) { glass.cornerRadius = value }

    var backdropSource: View?
        get() = glass.backdropSource
        set(value) { glass.backdropSource = value }

    var backdropUnderlay: BackdropUnderlay?
        get() = glass.backdropUnderlay
        set(value) { glass.backdropUnderlay = value }

    fun setLook(look: GlassLook) = glass.setLook(look)
    fun getLook(): GlassLook = glass.getLook()
    fun invalidateBackdrop() = glass.invalidateBackdrop()
}
