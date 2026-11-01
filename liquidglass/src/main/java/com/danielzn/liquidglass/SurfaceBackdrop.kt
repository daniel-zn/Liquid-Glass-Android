package com.danielzn.liquidglass

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.RequiresApi

/**
 * A small copy of a [SurfaceView]'s picture (a video), refreshed at [framesPerSecond] while
 * [running] and some attached glass is shown (see [PixelCopyBackdrop]).
 */
@RequiresApi(Build.VERSION_CODES.N)
class SurfaceBackdrop @JvmOverloads constructor(
    private val surfaceView: SurfaceView,
    copyWidth: Int = 480,
    copyHeight: Int = 270
) : PixelCopyBackdrop(copyWidth, copyHeight) {

    private val loc = IntArray(2)

    override fun canCopy(): Boolean {
        val surface = surfaceView.holder.surface
        return surface != null && surface.isValid && surfaceView.width > 0
    }

    override fun request(dest: Bitmap, listener: PixelCopy.OnPixelCopyFinishedListener, handler: Handler) {
        PixelCopy.request(surfaceView, dest, listener, handler)
    }

    override fun sourceRect(out: Rect) {
        surfaceView.getLocationOnScreen(loc)
        out.set(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + surfaceView.height)
    }
}
