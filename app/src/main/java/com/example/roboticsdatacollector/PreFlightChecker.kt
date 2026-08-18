package com.example.roboticsdatacollector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/**
 * Real-time device readiness checks that must pass before a collection session starts.
 */
class PreFlightChecker(private val context: Context) {

    fun evaluate(): PreFlightReport {
        val storage = checkStorage()
        val battery = checkBattery()
        val sensors = checkSensors()
        val permissions = checkPermissions()
        val thermal = checkThermal()
        return PreFlightReport(
            storageFreeBytes = storage.freeBytes,
            storagePassed = storage.passed,
            storageDetail = storage.detail,
            batteryPercent = battery.percent,
            batteryCharging = battery.charging,
            batteryPassed = battery.passed,
            batteryDetail = battery.detail,
            accelerometerPresent = sensors.accelerometer,
            gyroscopePresent = sensors.gyroscope,
            sensorsPassed = sensors.passed,
            sensorsDetail = sensors.detail,
            cameraGranted = permissions.camera,
            audioGranted = permissions.audio,
            permissionsPassed = permissions.passed,
            permissionsDetail = permissions.detail,
            thermalStatus = thermal.status,
            thermalPassed = thermal.passed,
            thermalDetail = thermal.detail,
            timestampOk = true,
            timestampDetail = "elapsedRealtimeNanos monotonic clock",
            checkedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun checkStorage(): StorageResult {
        val sessionVolume = context.getExternalFilesDir(null) ?: context.filesDir
        val freeBytes = availableBytes(sessionVolume)
        val passed = freeBytes >= MIN_FREE_BYTES
        val freeLabel = formatGb(freeBytes)
        val requiredLabel = formatGb(MIN_FREE_BYTES)
        val detail = if (passed) {
            "$freeLabel free > $requiredLabel required"
        } else {
            "$freeLabel free < $requiredLabel required"
        }
        return StorageResult(freeBytes, passed, detail)
    }

    private fun checkBattery(): BatteryResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = batteryPercent(intent)
        val charging = isCharging(intent)
        val passed = percent >= MIN_BATTERY_PERCENT || charging
        val detail = buildString {
            if (percent >= 0) append("$percent%") else append("Unknown")
            when {
                charging && percent < MIN_BATTERY_PERCENT -> append(" - Charging - OK")
                passed -> append(" - OK")
                else -> append(" - Plug in charger ($MIN_BATTERY_PERCENT% required)")
            }
        }
        return BatteryResult(percent, charging, passed, detail)
    }

    private fun checkSensors(): SensorResult {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val passed = accelerometer && gyroscope
        val detail = when {
            passed -> "Accelerometer & Gyroscope Detected"
            !accelerometer && !gyroscope -> "Missing: Accelerometer & Gyroscope"
            !accelerometer -> "Missing: Accelerometer"
            else -> "Missing: Gyroscope"
        }
        return SensorResult(accelerometer, gyroscope, passed, detail)
    }

    private fun checkPermissions(): PermissionResult {
        val camera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val passed = camera && audio
        val detail = when {
            passed -> "Camera & Audio Granted"
            !camera && !audio -> "Camera & Audio denied"
            !camera -> "Camera denied"
            else -> "Microphone denied"
        }
        return PermissionResult(camera, audio, passed, detail)
    }

    private fun checkThermal(): ThermalResult {
        val status = DeviceHealth.thermalStatus(context)
        val passed = DeviceHealth.thermalOk(context)
        val label = when {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> "Thermal N/A (API < 29)"
            status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL — wait to cool"
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE — too hot to start"
            status >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Moderate — OK"
            else -> "Nominal"
        }
        return ThermalResult(status, passed, label)
    }

    private fun availableBytes(path: File): Long {
        return try {
            if (!path.exists()) path.mkdirs()
            StatFs(path.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun batteryPercent(intent: Intent?): Int {
        if (intent == null) return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return ((level / scale.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    private fun isCharging(intent: Intent?): Boolean {
        if (intent == null) return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private data class StorageResult(val freeBytes: Long, val passed: Boolean, val detail: String)
    private data class BatteryResult(
        val percent: Int,
        val charging: Boolean,
        val passed: Boolean,
        val detail: String
    )
    private data class SensorResult(
        val accelerometer: Boolean,
        val gyroscope: Boolean,
        val passed: Boolean,
        val detail: String
    )
    private data class PermissionResult(
        val camera: Boolean,
        val audio: Boolean,
        val passed: Boolean,
        val detail: String
    )
    private data class ThermalResult(val status: Int, val passed: Boolean, val detail: String)

    companion object {
        const val MIN_FREE_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val MIN_BATTERY_PERCENT: Int = 20

        fun formatGb(bytes: Long): String {
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            return "%.1f GB".format(gb)
        }
    }
}

data class PreFlightReport(
    val storageFreeBytes: Long,
    val storagePassed: Boolean,
    val storageDetail: String,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val batteryPassed: Boolean,
    val batteryDetail: String,
    val accelerometerPresent: Boolean,
    val gyroscopePresent: Boolean,
    val sensorsPassed: Boolean,
    val sensorsDetail: String,
    val cameraGranted: Boolean,
    val audioGranted: Boolean,
    val permissionsPassed: Boolean,
    val permissionsDetail: String,
    val thermalStatus: Int = 0,
    val thermalPassed: Boolean = true,
    val thermalDetail: String = "Nominal",
    val timestampOk: Boolean = true,
    val timestampDetail: String = "elapsedRealtimeNanos",
    val checkedAtEpochMs: Long
) {
    val allPassed: Boolean
        get() = storagePassed && batteryPassed && sensorsPassed && permissionsPassed && thermalPassed && timestampOk

    val canStartSession: Boolean
        get() = allPassed

    fun toJson(): JSONObject = JSONObject().apply {
        put("passed", allPassed)
        put("free_storage_bytes", storageFreeBytes)
        put("free_storage_gb", kotlin.math.round(storageFreeBytes / (1024.0 * 1024.0 * 1024.0) * 10.0) / 10.0)
        put("storage_ok", storagePassed)
        put("battery_percent", batteryPercent)
        put("battery_charging", batteryCharging)
        put("battery_ok", batteryPassed)
        put("accelerometer_present", accelerometerPresent)
        put("gyroscope_present", gyroscopePresent)
        put("sensors_ok", sensorsPassed)
        put("camera_permission", cameraGranted)
        put("audio_permission", audioGranted)
        put("permissions_ok", permissionsPassed)
        put("thermal_status", thermalStatus)
        put("thermal_ok", thermalPassed)
        put("timestamp_ok", timestampOk)
        put("checked_at_epoch_ms", checkedAtEpochMs)
    }
}
