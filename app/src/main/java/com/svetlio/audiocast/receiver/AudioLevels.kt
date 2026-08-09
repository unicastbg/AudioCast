package com.svetlio.audiocast.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A very lightweight audio level meter for the receiver.
 *
 * [PcmPlayer.write] feeds a normalized 0..1 peak for each PCM chunk (only when
 * enabled). We keep a rolling history of ~30 bars/second and publish it as a
 * scrolling waveform the UI can draw. No FFT, no extra permissions — just peak
 * amplitude off the PCM we already have.
 */
object AudioLevels {
    const val BAR_COUNT = 48
    private const val FRAME_MS = 33L // ~30 fps emit rate

    @Volatile private var enabledFast = false
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _levels = MutableStateFlow(FloatArray(BAR_COUNT))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    private val history = FloatArray(BAR_COUNT)
    private var idx = 0
    private var runningPeak = 0f
    private var lastEmit = 0L
    private val lock = Any()

    fun setEnabled(value: Boolean) {
        enabledFast = value
        _enabled.value = value
        if (!value) reset()
    }

    /** Fast flag checked on the audio hot path (avoids StateFlow overhead). */
    fun isEnabled(): Boolean = enabledFast

    /** Feed a normalized 0..1 peak. Emits a new bar at most every FRAME_MS. */
    fun push(peak: Float) {
        if (!enabledFast) return
        synchronized(lock) {
            if (peak > runningPeak) runningPeak = peak
            val now = System.currentTimeMillis()
            if (now - lastEmit < FRAME_MS) return
            lastEmit = now
            history[idx] = runningPeak
            idx = (idx + 1) % BAR_COUNT
            runningPeak = 0f
            val out = FloatArray(BAR_COUNT)
            for (i in 0 until BAR_COUNT) out[i] = history[(idx + i) % BAR_COUNT]
            _levels.value = out
        }
    }

    fun reset() {
        synchronized(lock) {
            history.fill(0f)
            idx = 0
            runningPeak = 0f
            _levels.value = FloatArray(BAR_COUNT)
        }
    }
}
