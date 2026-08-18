package com.example.roboticsdatacollector

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporal marker logged during a continuous collection session.
 * [timestampNs] uses the same monotonic clock as IMU samples.
 */
data class SessionEvent(
    val timestampNs: Long,
    val eventType: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp_ns", timestampNs)
        put("event_type", eventType)
    }

    companion object {
        const val MARK = "MARK"
        const val SUCCESS = "SUCCESS"
        const val FAILURE = "FAILURE"

        fun now(type: String): SessionEvent = SessionEvent(
            timestampNs = SystemClock.elapsedRealtimeNanos(),
            eventType = type
        )
    }
}

data class EventFlash(
    val eventType: String,
    val message: String,
    val timestampNs: Long
)

/**
 * Thread-safe event log used by volume keys and the on-screen MARK control.
 */
class SessionEventLogger(
    private val haptics: HapticFeedbackManager
) {
    private val recording = AtomicBoolean(false)
    private val events = CopyOnWriteArrayList<SessionEvent>()
    private val _flash = MutableStateFlow<EventFlash?>(null)
    val flash: StateFlow<EventFlash?> = _flash.asStateFlow()

    val isRecording: Boolean
        get() = recording.get()

    fun beginSession() {
        events.clear()
        _flash.value = null
        recording.set(true)
    }

    fun endSession(): List<SessionEvent> {
        recording.set(false)
        return events.toList()
    }

    fun snapshot(): List<SessionEvent> = events.toList()

    fun record(eventType: String): SessionEvent? {
        if (!recording.get()) return null
        val event = SessionEvent.now(eventType)
        events.add(event)
        when (eventType) {
            SessionEvent.FAILURE -> haptics.playFailure()
            else -> haptics.playMark()
        }
        _flash.value = EventFlash(
            eventType = eventType,
            message = confirmationMessage(eventType),
            timestampNs = event.timestampNs
        )
        return event
    }

    fun clearFlash() {
        _flash.value = null
    }

    private fun confirmationMessage(type: String): String = when (type) {
        SessionEvent.MARK -> "📍 MARK logged"
        SessionEvent.SUCCESS -> "✓ SUCCESS Marked"
        SessionEvent.FAILURE -> "✗ FAILURE Marked"
        else -> type
    }
}
