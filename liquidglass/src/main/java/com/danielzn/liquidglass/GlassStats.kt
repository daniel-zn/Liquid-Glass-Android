package com.danielzn.liquidglass

/**
 * Counters for measuring (main thread only, plain ints, no cost worth mentioning): how often lens
 * glass re-recorded its backdrop. A static screen should leave [lensRecords] unchanged.
 */
object GlassStats {
    @JvmField var lensRecords = 0
    /** Shared panel backdrops recorded (one per panel and change, for all its controls: [SharedBackdrop]). */
    @JvmField var sharedRecords = 0

    /** Measuring: controls on a glass panel share the panel's backdrop (default) or each record their own. */
    @JvmStatic fun setSharedBackdrops(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33) SharedBackdrop.enabled = on
    }
    /** WindowBackdrop: frames its source window drew while observed, and copies made of it. */
    @JvmField var windowDraws = 0
    @JvmField var windowCopies = 0
    /** GlassRimOverlay: layer frames drawn (GL thread). */
    @JvmField @Volatile var overlayFrames = 0
    /** LiveRim: pictures that redrew glass, any window (main thread). */
    @JvmField var rimFrames = 0
}
