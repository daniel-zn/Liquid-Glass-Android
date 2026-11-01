package com.danielzn.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi

/**
 * A small, periodically refreshed copy of a [SurfaceView]'s picture (a video, a camera
 * preview), drawn under the backdrop of the glass views attached to it.
 *
 * A SurfaceView's frames are composited by SurfaceFlinger, outside the view tree, so glass in
 * the window can't record them. This copies the surface with [PixelCopy] into a downscaled
 * bitmap (blurred and refracted by the glass anyway) at [framesPerSecond], and invalidates the
 * attached glass after each copy. Two bitmaps are allocated once and reused (no per-frame
 * allocation); the copy runs on its own thread.
 *
 * It copies only while [running] and at least one attached glass is shown; otherwise it does
 * nothing at all (no timer). [framesPerSecond] = 0 copies once per [refresh] (a still per clip).
 */
@RequiresApi(Build.VERSION_CODES.N)
class SurfaceBackdrop @JvmOverloads constructor(
    private val surfaceView: SurfaceView,
    val copyWidth: Int = 480,
    val copyHeight: Int = 270
) : BackdropUnderlay {

    /** Copies per second while running and shown; 0 = only on [refresh]. */
    var framesPerSecond: Float = 10f
        set(value) {
            field = value.coerceIn(0f, 60f)
            schedule()
        }

    /** Copy only while true (the caller knows when the video plays). */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) refresh() else { main.removeCallbacks(tick); pending = false }
        }

    /** Copy statistics (for measuring): copies done, failures, mean and worst ms per copy. */
    var copies = 0; private set
    var failures = 0; private set
    var lastCopyMs = 0f; private set
    var worstCopyMs = 0f; private set
    private var totalCopyMs = 0.0
    val meanCopyMs: Float get() = if (copies == 0) 0f else (totalCopyMs / copies).toFloat()

    /** Called on the main thread after each new picture (e.g. to measure), may be null. */
    var onFrame: Runnable? = null

    private val panes = ArrayList<View>()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var copyHandler: Handler? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    @Volatile private var front = -1
    private var back = 0
    private var pending = false
    private var requestedAt = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private val loc = IntArray(2)

    private val tick = Runnable { copyNow() }
    private val delivered = Runnable { onCopied() }
    private var lastResult = PixelCopy.SUCCESS

    private val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
        lastResult = result
        main.post(delivered)
    }

    /** Glass that shows this backdrop ([GlassPane] attaches itself when it is the underlay). */
    fun attach(glass: View) {
        if (!panes.contains(glass)) panes.add(glass)
        if (front < 0 || framesPerSecond > 0f) schedule()
    }

    fun detach(glass: View) {
        panes.remove(glass)
    }

    /** An attached glass was shown or hidden. */
    fun onPaneVisibilityChanged() = schedule()

    /** Copy once now (a new clip, a seek), then continue at [framesPerSecond]. */
    fun refresh() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** The picture to draw, or null before the first copy. */
    val frame: Bitmap?
        get() = front.takeIf { it >= 0 }?.let { buffers[it] }

    /** Frees the thread and the bitmaps. */
    fun release() {
        running = false
        main.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        copyHandler = null
        front = -1
        buffers.fill(null)
    }

    override fun drawUnderlay(canvas: Canvas) {
        val bmp = frame ?: return
        surfaceView.getLocationOnScreen(loc)
        dst.set(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + surfaceView.height)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun anyShown(): Boolean {
        for (i in panes.indices) if (panes[i].isShown) return true
        return false
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        if (!running || pending) return
        if (!anyShown()) return
        if (framesPerSecond <= 0f && front >= 0) return
        main.postDelayed(tick, GlassGeometry.nextCopyDelay(framesPerSecond, requestedAt, SystemClock.uptimeMillis()))
    }

    private fun copyNow() {
        if (!running || pending || !anyShown()) return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid || surfaceView.width == 0) return
        val handler = copyHandler ?: HandlerThread("glass-backdrop").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { copyHandler = it }
        }
        back = if (front == 0) 1 else 0
        val bmp = buffers[back] ?: Bitmap.createBitmap(copyWidth, copyHeight, Bitmap.Config.ARGB_8888)
            .also { buffers[back] = it }
        pending = true
        requestedAt = SystemClock.uptimeMillis()
        target = bmp
        // Before API 34 PixelCopy blocks the calling thread until the copy is done: not the UI thread.
        handler.post(request)
    }

    @Volatile private var target: Bitmap? = null
    private val request = Runnable {
        val bmp = target ?: return@Runnable
        try {
            PixelCopy.request(surfaceView, bmp, listener, copyHandler ?: return@Runnable)
        } catch (e: IllegalArgumentException) {
            // The surface went away between the check and the request.
            lastResult = PixelCopy.ERROR_SOURCE_INVALID
            main.post(delivered)
        }
    }

    private fun onCopied() {
        pending = false
        val ms = (SystemClock.uptimeMillis() - requestedAt).toFloat()
        if (lastResult == PixelCopy.SUCCESS) {
            front = back
            copies++
            lastCopyMs = ms
            totalCopyMs += ms
            if (ms > worstCopyMs) worstCopyMs = ms
            for (i in panes.indices) {
                val p = panes[i]
                if (p.isShown) p.invalidate()
            }
            onFrame?.run()
        } else {
            failures++
            if (failures <= 3) Log.w(TAG, "PixelCopy failed: $lastResult")
        }
        schedule()
    }

    private companion object {
        const val TAG = "SurfaceBackdrop"
    }
}
