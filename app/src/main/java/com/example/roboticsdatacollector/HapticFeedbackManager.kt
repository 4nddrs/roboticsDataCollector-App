package com.example.roboticsdatacollector

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Eyes-free confirmation pulses for session markers.
 *
 * - MARK / SUCCESS: single short pulse (~50 ms)
 * - FAILURE: double heavy pulse (~100 ms, 100 ms gap, 100 ms)
 */
class HapticFeedbackManager(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context.applicationContext)

    fun playMark() = playOneShot(durationMs = 50L, amplitude = 180)

    fun playStart() = playOneShot(durationMs = 40L, amplitude = 160)

    fun playStop() {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        try {
            val timings = longArrayOf(0L, 40L, 80L, 40L)
            val amplitudes = intArrayOf(0, 180, 0, 180)
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Stop haptic failed", e)
        }
    }

    fun playCritical() = playOneShot(durationMs = 280L, amplitude = 255)

    fun playHandsAlertOnce() = playOneShot(durationMs = 70L, amplitude = 140)

    fun playSuccess() = playOneShot(durationMs = 50L, amplitude = 180)

    fun playFailure() {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        try {
            val timings = longArrayOf(0L, 100L, 100L, 100L)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Failure haptic failed", e)
        }
    }

    private fun playOneShot(durationMs: Long, amplitude: Int) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        try {
            device.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            )
        } catch (e: Exception) {
            Log.w(TAG, "One-shot haptic failed", e)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator not available", e)
            null
        }
    }

    companion object {
        private const val TAG = "HapticFeedbackManager"
    }
}
