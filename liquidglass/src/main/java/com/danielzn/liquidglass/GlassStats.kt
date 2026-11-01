package com.danielzn.liquidglass

/**
 * Counters for measuring (main thread only, plain ints, no cost worth mentioning): how often lens
 * glass re-recorded its backdrop. A static screen should leave [lensRecords] unchanged.
 */
object GlassStats {
    @JvmField var lensRecords = 0
}
