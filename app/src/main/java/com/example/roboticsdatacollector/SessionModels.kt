package com.example.roboticsdatacollector

enum class CollectionPhase {
    SETUP,
    PRE_FLIGHT,
    WAITING_FOR_WEAR,
    COLLECTING,
    PAUSED,
    FINALIZING,
    REPORT
}

enum class RecordingProfile(val id: String, val label: String) {
    QUALITY("fhd30_imu_fastest", "Quality · 1080p30"),
    ENDURANCE("hd24_guardian_1hz", "Endurance · 720p")
}

enum class HandVisibilityState {
    BOTH,
    LEFT,
    RIGHT,
    NONE,
    PARTIAL
}

enum class VisibilityClass {
    GOOD,
    DEGRADED,
    POOR
}

data class SessionConfig(
    val experiment: String = "DefaultExperiment",
    val participantId: String = "P001",
    val environment: String = "Lab",
    val task: String = "Pick and place",
    val skill: String = "Manipulation",
    val narration: String = "",
    val profile: RecordingProfile = RecordingProfile.QUALITY
)

data class VideoSegment(
    val index: Int,
    val fileName: String,
    val startTimestampNs: Long,
    var endTimestampNs: Long = 0L
)

data class SessionReport(
    val sessionId: String,
    val durationSeconds: Double,
    val videoSaved: Boolean,
    val imuSaved: Boolean,
    val audioSaved: Boolean,
    val handVisibilityPercent: Double,
    val workspaceVisibilityPercent: Double,
    val cameraQualityPercent: Double,
    val droppedFrames: Int,
    val sensorGaps: Int,
    val storageBytes: Long,
    val overall: VisibilityClass,
    val status: String
)
