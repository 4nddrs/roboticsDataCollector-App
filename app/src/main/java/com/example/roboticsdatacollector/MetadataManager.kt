package com.example.roboticsdatacollector

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Writes a complete `metadata.json` when a collection session ends.
 * Timestamps use the same monotonic clock as IMU samples ([android.os.SystemClock.elapsedRealtimeNanos]).
 */
object MetadataManager {

    const val TARGET_FPS: Int = 30
    const val RESOLUTION: String = "1080p"

    data class GuardianSummary(
        val handsDetectedPercentage: Double,
        val totalAnalyzedFrames: Int,
        val handDetectedFrames: Int = 0,
        val detector: String = "mediapipe_hands",
        val modelAsset: String = "hand_landmarker.task"
    )

    data class SessionMetadata(
        val sessionId: String,
        val deviceModel: String = Build.MODEL,
        val androidVersion: String = Build.VERSION.RELEASE ?: "unknown",
        val startTimestampNs: Long,
        val endTimestampNs: Long,
        val videoFile: String = "video.mp4",
        val imuFile: String = "imu_data.csv",
        val targetFps: Int = TARGET_FPS,
        val resolution: String = RESOLUTION,
        val guardianSummary: GuardianSummary,
        val preFlightStatus: PreFlightReport? = null,
        val status: String = "completed"
    ) {
        val durationSeconds: Double
            get() = ((endTimestampNs - startTimestampNs).coerceAtLeast(0L)) / 1_000_000_000.0
    }

    /**
     * Serializes [metadata] into [outputFile] (`metadata.json` in the session folder).
     */
    fun write(outputFile: File, metadata: SessionMetadata) {
        try {
            outputFile.parentFile?.mkdirs()
            val guardian = JSONObject().apply {
                put("hands_detected_percentage", round2(metadata.guardianSummary.handsDetectedPercentage))
                put("total_analyzed_frames", metadata.guardianSummary.totalAnalyzedFrames)
                put("hand_detected_frames", metadata.guardianSummary.handDetectedFrames)
                put("detector", metadata.guardianSummary.detector)
                put("model_asset", metadata.guardianSummary.modelAsset)
            }
            val json = JSONObject().apply {
                put("session_id", metadata.sessionId)
                put("device_model", metadata.deviceModel)
                put("android_version", metadata.androidVersion)
                put("start_timestamp_ns", metadata.startTimestampNs)
                put("end_timestamp_ns", metadata.endTimestampNs)
                put("duration_seconds", round2(metadata.durationSeconds))
                put("video_file", metadata.videoFile)
                put("imu_file", metadata.imuFile)
                put("target_fps", metadata.targetFps)
                put("resolution", metadata.resolution)
                put("guardian_summary", guardian)
                put(
                    "pre_flight_status",
                    metadata.preFlightStatus?.toJson() ?: JSONObject().put("passed", false)
                )
                put("status", metadata.status)
            }
            outputFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata.json", e)
        }
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private const val TAG = "MetadataManager"
}
