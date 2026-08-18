package com.example.roboticsdatacollector

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object ManifestWriter {

    fun write(
        sessionDir: File,
        sessionId: String,
        files: List<File>
    ) {
        val entries = JSONArray()
        files.filter { it.exists() }.forEach { file ->
            entries.put(
                JSONObject().apply {
                    put("name", file.name)
                    put("relative_path", file.name)
                    put("bytes", file.length())
                    put("sha256", sha256(file))
                }
            )
        }
        val json = JSONObject().apply {
            put("schema_version", 1)
            put("session_id", sessionId)
            put("written_at_ns", SystemClock.elapsedRealtimeNanos())
            put("files", entries)
        }
        val out = File(sessionDir, "manifest.json")
        val tmp = File(sessionDir, "manifest.json.tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    fun writeCalibrationStub(sessionDir: File) {
        val dir = File(sessionDir, "calibration")
        dir.mkdirs()
        File(dir, "camera.json").writeText(
            JSONObject().apply {
                put("status", "uncalibrated")
                put("model", Build.MODEL)
                put("note", "Intrinsics not captured in V1")
            }.toString(2)
        )
    }

    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }
}

object DeviceHealth {
    fun thermalStatus(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(PowerManager::class.java)
                pm.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            }
        } catch (_: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    fun thermalOk(context: Context): Boolean {
        val status = thermalStatus(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status < PowerManager.THERMAL_STATUS_SEVERE
        } else {
            true
        }
    }

    fun freeBytes(path: File): Long {
        return try {
            if (!path.exists()) path.mkdirs()
            StatFs(path.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
    }
}
