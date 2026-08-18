package com.example.roboticsdatacollector

import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX recording wrapper that always finalizes the MP4 (moov atom)
 * via [Recording.stop], including emergency shutdown paths.
 */
class VideoRecorder {

    private val finalized = AtomicBoolean(true)
    @Volatile
    private var recording: Recording? = null

    fun start(
        controller: LifecycleCameraController,
        outputFile: File,
        executor: Executor,
        onEvent: (VideoRecordEvent) -> Unit
    ): Recording {
        forceFinalize()
        outputFile.parentFile?.mkdirs()
        val options = FileOutputOptions.Builder(outputFile).build()
        val audio = AudioConfig.create(true)
        finalized.set(false)
        val rec = controller.startRecording(options, audio, executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                finalized.set(true)
                recording = null
            }
            onEvent(event)
        }
        recording = rec
        return rec
    }

    fun stopGracefully() = forceFinalize()

    /**
     * Stops the active recording so CameraX can write container metadata.
     * Safe to call from onStop / onDestroy / low-battery.
     */
    fun forceFinalize() {
        val rec = recording ?: return
        if (!finalized.compareAndSet(false, true)) return
        try {
            rec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize video.mp4", e)
        } finally {
            recording = null
        }
    }

    fun isActive(): Boolean = recording != null && !finalized.get()

    companion object {
        private const val TAG = "VideoRecorder"
    }
}
