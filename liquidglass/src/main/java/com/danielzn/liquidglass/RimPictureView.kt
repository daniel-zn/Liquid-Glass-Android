package com.danielzn.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi

/**
 * A [GlassRimOverlay]'s pictures shown inside the window instead of on a layer of their own: the overlay's
 * GL renderer draws rims, veils and moved dims (on its own thread, as for a layer) into a GPU buffer, and
 * this view draws the newest buffer as one bitmap. A new picture invalidates only this view; the window
 * stays a single compositor layer. Place it full-size where the overlay's layer would be (over the
 * backdrop, under the content).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RimPictureView(context: Context) : View(context) {

    private var reader: ImageReader? = null
    private var overlay: GlassRimOverlay? = null
    private val main = Handler(Looper.getMainLooper())

    // The shown picture and the one before it stay acquired: the render thread may still be drawing the
    // older one when a new picture arrives, and a closed image goes back to the GL renderer to draw into.
    private var shown: Image? = null
    private var previous: Image? = null
    private var bitmap: Bitmap? = null

    /** Where the picture goes in this view (the overlay's origin). */
    var originX = 0f
    var originY = 0f

    /** Statistics: pictures received, frames drawn. */
    @Volatile var pictures = 0; private set
    @Volatile var draws = 0; private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setWillNotDraw(false)
    }

    /** [o] draws into this view from now on, [w] x [h] pixels. */
    fun attach(o: GlassRimOverlay, w: Int, h: Int) {
        if (overlay === o && reader?.width == w && reader?.height == h) return
        detach()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        r.setOnImageAvailableListener({ onPicture(it) }, main)
        reader = r
        overlay = o
        o.attachToSurface(r.surface, w, h)
    }

    fun detach() {
        overlay?.let { if (it.surface === reader?.surface) it.detachSurface() }
        overlay = null
        bitmap = null
        previous?.close(); previous = null
        shown?.close(); shown = null
        reader?.close()
        reader = null
        invalidate()
    }

    private fun onPicture(r: ImageReader) {
        if (r !== reader) return
        val img = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        val hb = img.hardwareBuffer
        val b = hb?.let { Bitmap.wrapHardwareBuffer(it, null) }
        hb?.close()
        if (b == null) { img.close(); return }
        previous?.close()
        previous = shown
        shown = img
        bitmap = b
        pictures++
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, originX, originY, null)
        draws++
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }
}
