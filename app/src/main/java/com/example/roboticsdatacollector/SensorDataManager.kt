package com.example.roboticsdatacollector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IMU CSV writer with periodic disk flushes and a force-close path for
 * low-battery / process-kill protection.
 */
class SensorDataManager(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val logging = AtomicBoolean(false)
    private var lineChannel: Channel<String>? = null
    private var writerJob: Job? = null
    private var ioScope: CoroutineScope? = null
    @Volatile private var activeWriter: BufferedWriter? = null

    val activeDevices: List<String>
        get() = buildList {
            if (accelerometer != null) add("accelerometer")
            if (gyroscope != null) add("gyroscope")
        }

    @Synchronized
    fun startLogging(outputFile: File) {
        if (logging.get()) {
            Log.w(TAG, "startLogging ignored: session already active")
            return
        }
        try {
            outputFile.parentFile?.mkdirs()
            if (!outputFile.exists()) outputFile.createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "Could not create IMU file", e)
            throw e
        }

        val channel = Channel<String>(
            capacity = 8_192,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        lineChannel = channel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope = scope

        writerJob = scope.launch {
            var writer: BufferedWriter? = null
            try {
                writer = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(outputFile, /* append = */ false), Charsets.UTF_8),
                    BUFFER_SIZE
                )
                activeWriter = writer
                writer.appendLine(CSV_HEADER)
                writer.flush()
                var writesSinceFlush = 0
                for (line in channel) {
                    if (!isActive) break
                    writer.appendLine(line)
                    writesSinceFlush++
                    if (writesSinceFlush >= FLUSH_EVERY_SAMPLES) {
                        writer.flush()
                        writesSinceFlush = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing imu_data.csv", e)
            } finally {
                try {
                    writer?.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "IMU flush on close failed", e)
                }
                try {
                    writer?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "IMU close failed", e)
                }
                activeWriter = null
            }
        }

        val thread = HandlerThread(SENSOR_THREAD_NAME).apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler
        logging.set(true)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } ?: Log.w(TAG, "Accelerometer not available")
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } ?: Log.w(TAG, "Gyroscope not available")
    }

    /** Best-effort flush without stopping the session. */
    fun flush() {
        try {
            activeWriter?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "IMU mid-session flush failed", e)
        }
    }

    /** Unregister sensors, drain the queue, flush, and close the CSV. */
    @Synchronized
    fun stopLogging() {
        if (!logging.getAndSet(false)) {
            flush()
            return
        }
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensors", e)
        }

        lineChannel?.close()
        lineChannel = null

        try {
            runBlocking {
                withTimeoutOrNull(3_000) { writerJob?.join() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMU close wait interrupted", e)
        } finally {
            try {
                activeWriter?.flush()
                activeWriter?.close()
            } catch (_: Exception) {
            }
            activeWriter = null
            writerJob = null
            ioScope?.cancel()
            ioScope = null
        }

        try {
            sensorThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping sensor thread", e)
        } finally {
            sensorThread = null
            sensorHandler = null
        }
    }

    fun forceFlushAndClose() = stopLogging()

    fun release() = stopLogging()

    override fun onSensorChanged(event: SensorEvent?) {
        if (!logging.get() || event == null) return
        val timestampNs = SystemClock.elapsedRealtimeNanos()
        val type = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "ACCEL"
            Sensor.TYPE_GYROSCOPE -> "GYRO"
            else -> return
        }
        val values = event.values
        if (values.size < 3) return
        val line = "$timestampNs,$type,${values[0]},${values[1]},${values[2]}"
        lineChannel?.trySend(line)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "SensorDataManager"
        private const val SENSOR_THREAD_NAME = "imu-sensor-thread"
        private const val CSV_HEADER = "timestamp_ns,sensor_type,x,y,z"
        private const val BUFFER_SIZE = 64 * 1024
        private const val FLUSH_EVERY_SAMPLES = 100
    }
}
