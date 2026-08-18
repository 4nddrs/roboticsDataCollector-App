package com.example.roboticsdatacollector

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Low-rate Guardian + device health samples (`quality.jsonl`). */
class QualityLogger {

    private val lock = ReentrantLock()
    private var writer: BufferedWriter? = null

    fun start(sessionDir: File) {
        stop()
        val file = File(sessionDir, "quality.jsonl")
        writer = BufferedWriter(FileWriter(file, false), 16 * 1024)
    }

    fun append(sample: JSONObject) {
        lock.withLock {
            try {
                sample.put("timestamp_ns", sample.optLong("timestamp_ns", SystemClock.elapsedRealtimeNanos()))
                writer?.append(sample.toString())
                writer?.append('\n')
                writer?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "quality.jsonl write failed", e)
            }
        }
    }

    fun stop() {
        lock.withLock {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
    }

    companion object {
        private const val TAG = "QualityLogger"
    }
}
