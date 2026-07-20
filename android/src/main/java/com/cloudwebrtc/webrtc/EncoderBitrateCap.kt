package com.cloudwebrtc.webrtc

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton bitrate cap injected from the Dart P2P call layer.
 *
 * Active only while a P2P call is live and the video sender is active.
 * Dart sets this via the "videoEncoderSetBitrateCap" method channel call and
 * clears it (bps = 0) on call teardown. Zero means no clamping is applied.
 *
 * [StreamEncoderWrapper] reads this before forwarding GCC's setRates() /
 * setRateAllocation() target to the hardware encoder. That wrapper sits
 * inside the factory chain used by native [SimulcastVideoEncoder]; an
 * outer factory wrapper around SimulcastVideoEncoder is bypassed because
 * createNative() returns a non-zero C++ pointer.
 */
object EncoderBitrateCap {
    private const val TAG = "EncoderBitrateCap"
    private val capBps = AtomicInteger(0)

    fun set(bps: Int) {
        val sanitized = bps.coerceAtLeast(0)
        val previous = capBps.getAndSet(sanitized)
        if (previous != sanitized) {
            Log.d(TAG, "cap set: ${previous / 1000}kbps → ${sanitized / 1000}kbps")
        }
    }

    fun clear() {
        val previous = capBps.getAndSet(0)
        if (previous != 0) {
            Log.d(TAG, "cap cleared (was ${previous / 1000}kbps)")
        }
    }

    fun get(): Int = capBps.get()
}
