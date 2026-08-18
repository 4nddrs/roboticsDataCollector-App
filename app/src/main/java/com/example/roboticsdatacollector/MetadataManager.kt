package com.example.roboticsdatacollector

import android.os.Build
import android.util.Log
import org.json.JSONArray
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
        val blurredFramesPercentage: Double = 0.0,
        val underexposedFramesPercentage: Double = 0.0,
        val overexposedFramesPercentage: Double = 0.0,
        val detector: String = "mediapipe_hands",
        val modelAsset: String = "hand_landmarker.task",
        val workspaceVisiblePercentage: Double = 0.0,
        val obstructedFramesPercentage: Double = 0.0,
        val droppedFrames: Int = 0,
        val guardianDegraded: Boolean = false
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
        val events: List<SessionEvent> = emptyList(),
        val status: String = SessionStatus.COMPLETED,
        val config: SessionConfig? = null,
        val appVersion: String = "1.0",
        val applicationId: String = "com.example.roboticsdatacollector",
        val recordingProfile: String = RecordingProfile.QUALITY.id,
        val timebase: String = "elapsedRealtimeNanos",
        val bootElapsedNsAtStart: Long = 0L,
        val achievedWidth: Int = 0,
        val achievedHeight: Int = 0,
        val requestedFps: Int = TARGET_FPS,
        val videoFileNames: List<String> = emptyList(),
        val pauseIntervalsNs: List<Pair<Long, Long>> = emptyList(),
        val sensorGaps: Int = 0,
        val thermalAtEnd: Int = 0
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
            val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
            val guardian = JSONObject().apply {
                put("hands_detected_percentage", round2(metadata.guardianSummary.handsDetectedPercentage))
                put("total_analyzed_frames", metadata.guardianSummary.totalAnalyzedFrames)
                put("hand_detected_frames", metadata.guardianSummary.handDetectedFrames)
                put("blurred_frames_percentage", round2(metadata.guardianSummary.blurredFramesPercentage))
                put("underexposed_frames_percentage", round2(metadata.guardianSummary.underexposedFramesPercentage))
                put("overexposed_frames_percentage", round2(metadata.guardianSummary.overexposedFramesPercentage))
                put("detector", metadata.guardianSummary.detector)
                put("model_asset", metadata.guardianSummary.modelAsset)
                put("workspace_visible_percentage", round2(metadata.guardianSummary.workspaceVisiblePercentage))
                put("obstructed_frames_percentage", round2(metadata.guardianSummary.obstructedFramesPercentage))
                put("dropped_frames", metadata.guardianSummary.droppedFrames)
                put("guardian_degraded", metadata.guardianSummary.guardianDegraded)
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
                put(
                    "events",
                    JSONArray().apply {
                        metadata.events.forEach { put(it.toJson()) }
                    }
                )
                put("status", metadata.status)
                put("timebase", metadata.timebase)
                put("boot_elapsed_ns_at_start", metadata.bootElapsedNsAtStart)
                put("app_version", metadata.appVersion)
                put("application_id", metadata.applicationId)
                put("recording_profile", metadata.recordingProfile)
                put("requested_fps", metadata.requestedFps)
                put("achieved_width", metadata.achievedWidth)
                put("achieved_height", metadata.achievedHeight)
                put("sensor_gaps", metadata.sensorGaps)
                put("thermal_status_at_end", metadata.thermalAtEnd)
                put(
                    "video_files",
                    JSONArray().apply { metadata.videoFileNames.forEach { put(it) } }
                )
                put(
                    "pause_intervals_ns",
                    JSONArray().apply {
                        metadata.pauseIntervalsNs.forEach { pair ->
                            put(JSONObject().put("start_ns", pair.first).put("end_ns", pair.second))
                        }
                    }
                )
                metadata.config?.let { cfg ->
                    put(
                        "session_config",
                        JSONObject().apply {
                            put("experiment", cfg.experiment)
                            put("participant_id", cfg.participantId)
                            put("environment", cfg.environment)
                            put("task", cfg.task)
                            put("skill", cfg.skill)
                            put("narration", cfg.narration)
                            put("profile", cfg.profile.id)
                        }
                    )
                }
            }
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(outputFile)) {
                tmp.copyTo(outputFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata.json", e)
        }
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private const val TAG = "MetadataManager"
}
