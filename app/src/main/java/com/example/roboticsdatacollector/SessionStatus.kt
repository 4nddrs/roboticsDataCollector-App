package com.example.roboticsdatacollector

/**
 * Canonical session status values written to `metadata.json`.
 */
object SessionStatus {
    const val RECORDING = "RECORDING"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED_LOW_BATTERY = "INTERRUPTED_LOW_BATTERY"
    const val INTERRUPTED_SYSTEM = "INTERRUPTED_SYSTEM"
    const val ERROR = "ERROR"
}
