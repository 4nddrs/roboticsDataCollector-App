package com.example.roboticsdatacollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionProtectReason {
    LOW_BATTERY,
    SYSTEM
}

/**
 * Watches battery and shutdown broadcasts while a session is active and
 * requests an atomic flush/stop before the process is killed.
 */
class SessionSafetyManager(
    private val context: Context,
    private val isSessionActive: () -> Boolean
) {
    private val appContext = context.applicationContext
    private val monitoring = AtomicBoolean(false)
    private val lowBatteryFired = AtomicBoolean(false)

    private val _protectEvents = MutableSharedFlow<SessionProtectReason>(extraBufferCapacity = 1)
    val protectEvents: SharedFlow<SessionProtectReason> = _protectEvents.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isSessionActive() || intent == null) return
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> emitProtect(SessionProtectReason.SYSTEM)
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_CHANGED -> {
                    val percent = batteryPercent(intent)
                    if (percent in 0 until CRITICAL_BATTERY_PERCENT) {
                        if (lowBatteryFired.compareAndSet(false, true)) {
                            emitProtect(SessionProtectReason.LOW_BATTERY)
                        }
                    }
                }
            }
        }
    }

    fun startMonitoring() {
        if (!monitoring.compareAndSet(false, true)) return
        lowBatteryFired.set(false)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
            val percent = batteryPercent(sticky)
            if (percent in 0 until CRITICAL_BATTERY_PERCENT && isSessionActive()) {
                if (lowBatteryFired.compareAndSet(false, true)) {
                    emitProtect(SessionProtectReason.LOW_BATTERY)
                }
            }
            Log.i(TAG, "Session safety monitoring started (battery=$percent%)")
        } catch (e: Exception) {
            monitoring.set(false)
            Log.e(TAG, "Failed to register safety receiver", e)
        }
    }

    fun stopMonitoring() {
        if (!monitoring.compareAndSet(true, false)) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Safety receiver already unregistered", e)
        }
        lowBatteryFired.set(false)
    }

    private fun emitProtect(reason: SessionProtectReason) {
        val emitted = _protectEvents.tryEmit(reason)
        Log.w(TAG, "Protect session reason=$reason emitted=$emitted")
    }

    private fun batteryPercent(intent: Intent?): Int {
        if (intent == null) return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return ((level / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    companion object {
        private const val TAG = "SessionSafetyManager"
        const val CRITICAL_BATTERY_PERCENT = 5
    }
}
