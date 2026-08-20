package com.example.roboticsdatacollector

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spoken Guardian prompt on the **notification / system** stream so it is not mixed
 * into CameraX research audio. Capture policy blocks inner-app audio capture (API 29+).
 * Acoustic bleed into the physical mic is still possible if the speaker is loud;
 * volume is kept moderate and playback is cancelled the instant hands return.
 */
class HandsAlertAudioManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(appContext, this)
    private val ready = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    @Volatile
    private var lastSpeakElapsedMs = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed status=$status")
            return
        }
        try {
            tts.setAudioAttributes(notificationSpeechAttributes())
            val lang = tts.setLanguage(Locale.US)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "en-US TTS missing; using default locale")
            }
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.set(true)
                }

                override fun onDone(utteranceId: String?) {
                    speaking.set(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking.set(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    speaking.set(false)
                }
            })
            ready.set(true)
            Log.i(TAG, "Hands-alert TTS ready (USAGE_NOTIFICATION, capture excluded)")
        } catch (e: Exception) {
            Log.e(TAG, "TTS configuration failed", e)
        }
    }

    fun setCollecting(collecting: Boolean) {
        enabled.set(collecting)
        if (!collecting) cancel()
    }

    /**
     * Plays the prompt if cooldown allows. Guardian already applied the 5 s persistence
     * gate; this extra cooldown blocks overlapping / double callbacks.
     */
    fun speakNoHandsAlert() {
        if (!enabled.get() || !ready.get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSpeakElapsedMs < COOLDOWN_MS && lastSpeakElapsedMs > 0L) return
        lastSpeakElapsedMs = now
        mainHandler.post {
            if (!enabled.get() || !ready.get()) return@post
            try {
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.55f)
                }
                tts.speak(PROMPT, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed", e)
            }
        }
    }

    fun cancel() {
        lastSpeakElapsedMs = 0L
        speaking.set(false)
        mainHandler.post {
            try {
                if (tts.isSpeaking) tts.stop()
            } catch (e: Exception) {
                Log.w(TAG, "TTS stop failed", e)
            }
        }
    }

    fun release() {
        enabled.set(false)
        cancel()
        ready.set(false)
        mainHandler.post {
            try {
                tts.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS shutdown failed", e)
            }
        }
    }

    private fun notificationSpeechAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "HandsAlertAudio"
        private const val PROMPT = "Hand not detected!"
        private const val UTTERANCE_ID = "hands_not_detected"
        const val PERSIST_SECONDS = 5f
        const val COOLDOWN_SECONDS = 2f
        private const val COOLDOWN_MS = 2_000L
    }
}
