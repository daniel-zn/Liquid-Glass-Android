package com.danielzn.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Another window's last frame as a glass backdrop: for glass in a popup, dialog or second
 * activity over [window] (same process). [under] (e.g. that window's video, a
 * [SurfaceBackdrop]) is drawn first; the window's copy, with its transparent areas, over it.
 *
 * Why a copy and not the window's views: recording another window's views puts its RenderNodes
 * into this window's render tree, and tearing this window down then destroys their GPU resources
 * under the other window (it took down an emulator's GPU every time a popup closed). A copy
 * shares nothing.
 *
 * Copied only after [window] drew a frame (never on a timer alone), at most [framesPerSecond],
 * while [running] and some attached glass is shown: a static window under a dialog costs one copy.
 */
@RequiresApi(Build.VERSION_CODES.O)
class WindowBackdrop @JvmOverloads constructor(
    private val window: Window,
    private val under: BackdropUnderlay? = null,
    copyWidth: Int = 960,
    copyHeight: Int = 540
) : PixelCopyBackdrop(copyWidth, copyHeight) {

    private val loc = IntArray(2)
    /** The window drew since the last copy (only then is there something new to copy). */
    @Volatile private var dirty = true
    private val onDraw = ViewTreeObserver.OnDrawListener {
        dirty = true
        if (running && panes.isNotEmpty()) schedule()
    }

    override fun sourceChanged(): Boolean = dirty

    override fun onCopyStarted() {
        dirty = false
    }
    private var observing: ViewTreeObserver? = null

    init {
        framesPerSecond = 5f
    }

    private val decor: View? get() = window.peekDecorView()

    override fun canCopy(): Boolean {
        val d = decor ?: return false
        return d.isAttachedToWindow && d.width > 0 && d.windowVisibility == View.VISIBLE
    }

    override fun request(dest: Bitmap, listener: PixelCopy.OnPixelCopyFinishedListener, handler: Handler) {
        PixelCopy.request(window, dest, listener, handler)
    }

    override fun sourceRect(out: Rect) {
        val d = decor
        if (d == null) { out.setEmpty(); return }
        d.getLocationOnScreen(loc)
        out.set(loc[0], loc[1], loc[0] + d.width, loc[1] + d.height)
    }

    override fun drawUnderlay(canvas: Canvas) {
        under?.drawUnderlay(canvas)
        super.drawUnderlay(canvas)
    }

    override val underlayVersion: Int get() = super.underlayVersion * 31 + (under?.underlayVersion ?: 0)

    override fun attach(glass: View) {
        (under as? PixelCopyBackdrop)?.attach(glass)
        val d = decor
        if (observing == null && d != null && d.viewTreeObserver.isAlive) {
            observing = d.viewTreeObserver.also { it.addOnDrawListener(onDraw) }
        }
        super.attach(glass)
    }

    override fun detach(glass: View) {
        (under as? PixelCopyBackdrop)?.detach(glass)
        super.detach(glass)
    }

    override fun onGlassDrawn() {
        (under as? PixelCopyBackdrop)?.onGlassDrawn()
        super.onGlassDrawn()
    }

    override fun release() {
        observing?.let { if (it.isAlive) it.removeOnDrawListener(onDraw) }
        observing = null
        super.release()
    }
}
