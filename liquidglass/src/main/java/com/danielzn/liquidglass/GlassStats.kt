package com.danielzn.liquidglass

/**
 * Counters for measuring (main thread only, plain ints, no cost worth mentioning): how often lens
 * glass re-recorded its backdrop. A static screen should leave [lensRecords] unchanged.
 */
object GlassStats {
    @JvmField var lensRecords = 0
    /** WindowBackdrop: frames its source window drew while observed, and copies made of it. */
    @JvmField var windowDraws = 0
    @JvmField var windowCopies = 0
    /** GlassRimOverlay: layer frames drawn (GL thread). */
    @JvmField @Volatile var overlayFrames = 0
    /** LiveRim: pictures that redrew glass, any window (main thread). */
    @JvmField var rimFrames = 0
}
