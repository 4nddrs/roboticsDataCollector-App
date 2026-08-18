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
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura acelerómetro y giroscopio a [SensorManager.SENSOR_DELAY_FASTEST]
 * y escribe `imu_data.csv` sin bloquear el hilo de sensores.
 *
 * Reloj de sincronización: [SystemClock.elapsedRealtimeNanos] (monotónico, ns),
 * el mismo dominio temporal que suele usarse con CameraX.
 */
class SensorLogger(context: Context) : SensorEventListener {

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

    val activeDevices: List<String>
        get() = buildList {
            if (accelerometer != null) add("accelerometer")
            if (gyroscope != null) add("gyroscope")
        }

    /**
     * Inicia el registro. Crea cabecera CSV y encola cada muestra para I/O asíncrono.
     */
    @Synchronized
    fun startLogging(outputFile: File) {
        if (logging.get()) {
            Log.w(TAG, "startLogging ignorado: ya hay una sesión activa")
            return
        }

        try {
            outputFile.parentFile?.mkdirs()
            if (!outputFile.exists()) {
                outputFile.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el archivo IMU", e)
            throw e
        }

        // Buffer amplio: trySend no debe bloquear el hilo de sensores.
        val channel = Channel<String>(
            capacity = 8_192,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        lineChannel = channel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope = scope

        writerJob = scope.launch {
            try {
                BufferedWriter(FileWriter(outputFile, /* append = */ false), BUFFER_SIZE).use { writer ->
                    writer.appendLine(CSV_HEADER)
                    writer.flush()
                    var writesSinceFlush = 0
                    for (line in channel) {
                        if (!isActive) break
                        writer.appendLine(line)
                        writesSinceFlush++
                        // Reduce syscalls: flush por lotes, no en cada muestra FASTEST
                        if (writesSinceFlush >= FLUSH_EVERY_LINES) {
                            writer.flush()
                            writesSinceFlush = 0
                        }
                    }
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo imu_data.csv", e)
            }
        }

        val thread = HandlerThread(SENSOR_THREAD_NAME).apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        logging.set(true)

        // Los callbacks llegan en [sensorThread], no en el hilo principal.
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } ?: Log.w(TAG, "Acelerómetro no disponible en este dispositivo")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } ?: Log.w(TAG, "Giroscopio no disponible en este dispositivo")
    }

    /**
     * Detiene sensores, drena la cola y cierra el archivo de forma segura.
     */
    @Synchronized
    fun stopLogging() {
        if (!logging.getAndSet(false)) return

        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error al desregistrar sensores", e)
        }

        lineChannel?.close()
        lineChannel = null

        try {
            runBlocking {
                withTimeoutOrNull(3_000) { writerJob?.join() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Espera de cierre IMU interrumpida", e)
        } finally {
            writerJob = null
            ioScope?.cancel()
            ioScope = null
        }

        try {
            sensorThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Error al detener hilo de sensores", e)
        } finally {
            sensorThread = null
            sensorHandler = null
        }
    }

    /** Libera recursos si la Activity se destruye con una sesión abierta. */
    fun release() {
        stopLogging()
    }

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
        val sent = lineChannel?.trySend(line)
        if (sent?.isFailure == true) {
            // Si el escritor va lento, no bloqueamos el hilo de sensores.
            Log.w(TAG, "Muestra IMU descartada (cola saturada o canal cerrado)")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Sin acción: la precisión se puede añadir más adelante a metadata.json
    }

    companion object {
        private const val TAG = "SensorLogger"
        private const val SENSOR_THREAD_NAME = "imu-sensor-thread"
        private const val CSV_HEADER = "timestamp_ns,sensor_type,x,y,z"
        private const val BUFFER_SIZE = 64 * 1024
        private const val FLUSH_EVERY_LINES = 200
    }
}
