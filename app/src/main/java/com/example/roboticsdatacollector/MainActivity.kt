package com.example.roboticsdatacollector

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.roboticsdatacollector.ui.theme.RoboticsDataCollectorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var sensorDataManager: SensorDataManager
    private lateinit var guardian: DataCollectionGuardian
    private lateinit var eventLogger: SessionEventLogger
    private lateinit var videoRecorder: VideoRecorder
    private lateinit var sessionSafety: SessionSafetyManager
    private lateinit var handsAlertAudio: HandsAlertAudioManager
    private var analysisExecutor: ExecutorService? = null
    private val emergencyStop = AtomicReference<((String) -> Unit)?>(null)

    private val keyHandler = Handler(Looper.getMainLooper())
    private var volumeDownDownAtMs = 0L
    private var volumeDownLongFired = false
    private var lastVolumeDownUpMs = 0L
    private var pendingSuccess = false

    private val commitVolumeDownSuccess = Runnable {
        if (pendingSuccess) {
            pendingSuccess = false
            eventLogger.record(SessionEvent.SUCCESS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sensorDataManager = SensorDataManager(this)
        guardian = DataCollectionGuardian(this, targetAnalysisFps = 4)
        eventLogger = SessionEventLogger(HapticFeedbackManager(this))
        videoRecorder = VideoRecorder()
        sessionSafety = SessionSafetyManager(this) { eventLogger.isRecording }
        handsAlertAudio = HandsAlertAudioManager(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        setContent {
            RoboticsDataCollectorTheme {
                var destination by remember { mutableStateOf(AppScreen.Capture) }
                when (destination) {
                    AppScreen.Capture -> DataCollectionScreen(
                        sensorDataManager = sensorDataManager,
                        guardian = guardian,
                        eventLogger = eventLogger,
                        videoRecorder = videoRecorder,
                        sessionSafety = sessionSafety,
                        handsAlertAudio = handsAlertAudio,
                        analysisExecutor = analysisExecutor!!,
                        emergencyStop = emergencyStop,
                        onOpenSessions = { destination = AppScreen.Sessions },
                        onKeepScreenOn = { enabled ->
                            if (enabled) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    )
                    AppScreen.Sessions -> SessionManagerScreen(
                        onBack = { destination = AppScreen.Capture }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!eventLogger.isCapturing) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) {
                    eventLogger.record(SessionEvent.MARK)
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeDownDownAtMs = SystemClock.elapsedRealtime()
                    volumeDownLongFired = false
                } else if (
                    !volumeDownLongFired &&
                    event.eventTime - event.downTime >= VOLUME_DOWN_LONG_PRESS_MS
                ) {
                    volumeDownLongFired = true
                    pendingSuccess = false
                    keyHandler.removeCallbacks(commitVolumeDownSuccess)
                    eventLogger.record(SessionEvent.FAILURE)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!eventLogger.isCapturing) return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeDownLongFired) {
                    volumeDownLongFired = false
                } else {
                    val now = SystemClock.elapsedRealtime()
                    val isDoublePress =
                        lastVolumeDownUpMs > 0L && now - lastVolumeDownUpMs <= VOLUME_DOWN_DOUBLE_PRESS_MS
                    if (isDoublePress) {
                        pendingSuccess = false
                        keyHandler.removeCallbacks(commitVolumeDownSuccess)
                        eventLogger.record(SessionEvent.FAILURE)
                        lastVolumeDownUpMs = 0L
                    } else {
                        pendingSuccess = true
                        lastVolumeDownUpMs = now
                        keyHandler.removeCallbacks(commitVolumeDownSuccess)
                        keyHandler.postDelayed(commitVolumeDownSuccess, VOLUME_DOWN_DOUBLE_PRESS_MS)
                    }
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            flushActiveSession(SessionStatus.INTERRUPTED_SYSTEM)
        }
    }

    override fun onDestroy() {
        keyHandler.removeCallbacks(commitVolumeDownSuccess)
        flushActiveSession(SessionStatus.INTERRUPTED_SYSTEM)
        try {
            sessionSafety.stopMonitoring()
            guardian.close()
            handsAlertAudio.release()
            sensorDataManager.release()
            analysisExecutor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy cleanup", e)
        }
        super.onDestroy()
    }

    private fun flushActiveSession(status: String) {
        if (!eventLogger.isRecording) {
            videoRecorder.forceFinalize()
            sensorDataManager.forceFlushAndClose()
            return
        }
        val handler = emergencyStop.get()
        if (handler != null) {
            handler(status)
        } else {
            videoRecorder.forceFinalize()
            sensorDataManager.forceFlushAndClose()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val VOLUME_DOWN_LONG_PRESS_MS = 450L
        private const val VOLUME_DOWN_DOUBLE_PRESS_MS = 320L
    }
}

private const val SEGMENT_DURATION_MS = 10L * 60L * 1000L
private const val LOW_STORAGE_WARN_BYTES = 1024L * 1024L * 1024L
private const val LOW_STORAGE_STOP_BYTES = 500L * 1024L * 1024L

private enum class AppScreen { Capture, Sessions }

private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
fun DataCollectionScreen(
    sensorDataManager: SensorDataManager,
    guardian: DataCollectionGuardian,
    eventLogger: SessionEventLogger,
    videoRecorder: VideoRecorder,
    sessionSafety: SessionSafetyManager,
    handsAlertAudio: HandsAlertAudioManager,
    analysisExecutor: ExecutorService,
    emergencyStop: AtomicReference<((String) -> Unit)?>,
    onOpenSessions: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preFlightChecker = remember { PreFlightChecker(context) }
    var preFlight by remember { mutableStateOf(preFlightChecker.evaluate()) }
    var showPreFlightOverlay by remember { mutableStateOf(true) }
    var preFlightAtSessionStart by remember { mutableStateOf<PreFlightReport?>(null) }

    fun refreshPreFlight() {
        preFlight = preFlightChecker.evaluate()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            REQUIRED_PERMISSIONS.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = REQUIRED_PERMISSIONS.all { result[it] == true }
        refreshPreFlight()
        if (!permissionsGranted) {
            Toast.makeText(
                context,
                "Camera and microphone permissions are required to collect data",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        refreshPreFlight()
        if (!permissionsGranted) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPreFlight()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var recError by remember { mutableStateOf<String?>(null) }
    var safetyBanner by remember { mutableStateOf<String?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var currentSession by remember { mutableStateOf<SessionFiles?>(null) }
    val recordingRef = remember { AtomicReference<Recording?>(null) }
    val sessionRef = remember { AtomicReference<SessionFiles?>(null) }
    val rotateSegmentRef = remember { AtomicReference<(() -> Unit)?>(null) }
    val sessionClosing = remember { AtomicBoolean(false) }
    val stopSessionRef = remember { AtomicReference<((String) -> Unit)?>(null) }

    val isHandVisible by guardian.isHandVisible.collectAsState()
    val handState by guardian.handState.collectAsState()
    val workspaceVisible by guardian.workspaceVisible.collectAsState()
    val visibilityClass by guardian.visibilityClass.collectAsState()
    val analyzedFrameCount by guardian.analyzedFrameCount.collectAsState()
    val qualityWarning by guardian.qualityWarning.collectAsState()
    val eventFlash by eventLogger.flash.collectAsState()

    LaunchedEffect(eventFlash?.timestampNs) {
        if (eventFlash == null) return@LaunchedEffect
        delay(1_400)
        eventLogger.clearFlash()
    }

    LaunchedEffect(safetyBanner, isRecording) {
        if (safetyBanner != null && !isRecording) {
            delay(1_200)
            safetyBanner = null
        }
    }

    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording, isPaused) {
        if (!isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        if (isPaused) return@LaunchedEffect
        val base = elapsedMs
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = base + (SystemClock.elapsedRealtime() - startedAt)
            delay(200)
        }
    }

    var phase by remember { mutableStateOf(CollectionPhase.SETUP) }
    var sessionConfig by remember { mutableStateOf(SessionConfig()) }
    var lastReport by remember { mutableStateOf<SessionReport?>(null) }
    var orphanSession by remember { mutableStateOf<SessionRecord?>(null) }
    var storageHud by remember { mutableStateOf("Storage ✓") }
    var segmentIndex by remember { mutableIntStateOf(0) }
    val videoSegments = remember { mutableStateListOf<VideoSegment>() }
    val pauseIntervals = remember { mutableStateListOf<Pair<Long, Long>>() }
    var pauseStartedNs by remember { mutableLongStateOf(0L) }
    val qualityLogger = remember { QualityLogger() }
    val sessionRepository = remember { SessionRepository(context) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(
                CameraController.VIDEO_CAPTURE or CameraController.IMAGE_ANALYSIS
            )
            videoCaptureQualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
            )
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(analysisExecutor, guardian)
        }
    }

    DisposableEffect(lifecycleOwner, permissionsGranted) {
        if (permissionsGranted) {
            try {
                cameraController.bindToLifecycle(lifecycleOwner)
            } catch (e: Exception) {
                Log.e("DataCollection", "Failed to bind CameraX", e)
            }
        }
        onDispose {
            try {
                cameraController.unbind()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        emergencyStop.set { status ->
            stopSessionRef.get()?.invoke(status)
        }
        onDispose {
            emergencyStop.set(null)
            try {
                videoRecorder.forceFinalize()
            } catch (_: Exception) {
            }
            sessionSafety.stopMonitoring()
            sessionRef.get()?.let { session ->
                writeSessionMetadata(
                    session,
                    guardian,
                    status = SessionStatus.INTERRUPTED_SYSTEM,
                    preFlight = preFlightAtSessionStart,
                    events = eventLogger.endSession(),
                    config = sessionConfig,
                    segments = videoSegments.toList(),
                    pauseIntervals = pauseIntervals.toList()
                )
            }
            qualityLogger.stop()
            CollectionForegroundService.stop(context)
            handsAlertAudio.setCollecting(false)
            guardian.stop()
            sensorDataManager.forceFlushAndClose()
            onKeepScreenOn(false)
        }
    }

    LaunchedEffect(sessionSafety) {
        sessionSafety.protectEvents.collect { reason ->
            val (status, message) = when (reason) {
                SessionProtectReason.LOW_BATTERY ->
                    SessionStatus.INTERRUPTED_LOW_BATTERY to "Critical battery: Saving session..."
                SessionProtectReason.LOW_STORAGE ->
                    SessionStatus.INTERRUPTED_LOW_STORAGE to "Low storage: Saving session..."
                SessionProtectReason.SYSTEM ->
                    SessionStatus.INTERRUPTED_SYSTEM to "System interrupt: Saving session..."
            }
            safetyBanner = message
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            stopSessionRef.get()?.invoke(status)
        }
    }

    LaunchedEffect(Unit) {
        orphanSession = sessionRepository.orphanSessions().firstOrNull()
    }

    DisposableEffect(guardian, context, handsAlertAudio) {
        guardian.onHandsOutEscalation = { eventLogger.notifyHandsOut() }
        guardian.onNoHandsAudioAlert = { handsAlertAudio.speakNoHandsAlert() }
        guardian.onHandsRecovered = { handsAlertAudio.cancel() }
        guardian.onQualitySample = { json ->
            json.put("thermal_status", DeviceHealth.thermalStatus(context))
            val dir = sessionRef.get()?.dir ?: context.getExternalFilesDir(null) ?: context.filesDir
            json.put("free_bytes", DeviceHealth.freeBytes(dir))
            json.put("sensor_gaps", sensorDataManager.sensorGapCount)
            qualityLogger.append(json)
        }
        onDispose {
            guardian.onHandsOutEscalation = null
            guardian.onNoHandsAudioAlert = null
            guardian.onHandsRecovered = null
            guardian.onQualitySample = null
            handsAlertAudio.cancel()
        }
    }

    LaunchedEffect(phase) {
        if (phase == CollectionPhase.WAITING_FOR_WEAR && !isRecording) {
            guardian.start()
        }
    }

    LaunchedEffect(isRecording, isPaused, currentSession) {
        val session = currentSession
        if (!isRecording || session == null) return@LaunchedEffect
        var severeHaptic = false
        while (isRecording) {
            val free = DeviceHealth.freeBytes(session.dir)
            storageHud = when {
                free < LOW_STORAGE_STOP_BYTES -> "Storage 🔴"
                free < LOW_STORAGE_WARN_BYTES -> "Storage 🟡"
                else -> "Storage ✓"
            }
            if (free in 1 until LOW_STORAGE_STOP_BYTES) {
                eventLogger.notifyCritical()
                stopSessionRef.get()?.invoke(SessionStatus.INTERRUPTED_LOW_STORAGE)
                return@LaunchedEffect
            }
            val thermal = DeviceHealth.thermalStatus(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                thermal >= PowerManager.THERMAL_STATUS_SEVERE &&
                !severeHaptic
            ) {
                severeHaptic = true
                eventLogger.notifyCritical()
                safetyBanner = "Device hot — collection continues"
            }
            delay(15_000)
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        if (!isRecording || isPaused) return@LaunchedEffect
        delay(SEGMENT_DURATION_MS)
        while (isRecording && !isPaused) {
            rotateSegmentRef.get()?.invoke()
            delay(SEGMENT_DURATION_MS)
        }
    }

    fun closeCurrentSegment() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (videoSegments.isNotEmpty()) {
            videoSegments[videoSegments.lastIndex] =
                videoSegments.last().copy(endTimestampNs = now)
        }
        videoRecorder.forceFinalize()
        currentSession?.let { SegmentsWriter.write(it.dir, videoSegments.toList()) }
    }

    fun startVideoSegment(session: SessionFiles, index: Int) {
        val file = session.videoFile(index)
        val startNs = SystemClock.elapsedRealtimeNanos()
        videoSegments.add(
            VideoSegment(index = index, fileName = file.name, startTimestampNs = startNs)
        )
        val mainExecutor = ContextCompat.getMainExecutor(context)
        activeRecording = videoRecorder.start(
            controller = cameraController,
            outputFile = file,
            executor = mainExecutor
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    onKeepScreenOn(true)
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError() && isRecording && !sessionClosing.get()) {
                        recError = event.cause?.message ?: "Recording error"
                        Log.e("DataCollection", "Finalize error: ${event.error}", event.cause)
                    }
                }
            }
        }
        recordingRef.set(activeRecording)
        SegmentsWriter.write(session.dir, videoSegments.toList())
    }

    fun rotateVideoSegment() {
        val session = currentSession ?: return
        if (!isRecording || isPaused) return
        closeCurrentSegment()
        segmentIndex += 1
        startVideoSegment(session, segmentIndex)
    }
    rotateSegmentRef.set { rotateVideoSegment() }

    fun pauseCapture() {
        if (!isRecording || isPaused) return
        closeCurrentSegment()
        sensorDataManager.pauseLogging()
        eventLogger.setPaused(true)
        handsAlertAudio.setCollecting(false)
        isPaused = true
        pauseStartedNs = SystemClock.elapsedRealtimeNanos()
        phase = CollectionPhase.PAUSED
        currentSession?.let {
            writeSessionMetadata(
                it,
                guardian,
                status = SessionStatus.PAUSED,
                preFlight = preFlightAtSessionStart,
                events = eventLogger.snapshot(),
                config = sessionConfig,
                segments = videoSegments.toList(),
                pauseIntervals = pauseIntervals.toList()
            )
        }
    }

    fun resumeCapture() {
        val session = currentSession ?: return
        if (!isPaused) return
        if (pauseStartedNs > 0L) {
            pauseIntervals.add(pauseStartedNs to SystemClock.elapsedRealtimeNanos())
            pauseStartedNs = 0L
        }
        sensorDataManager.resumeLogging()
        eventLogger.setPaused(false)
        handsAlertAudio.setCollecting(true)
        isPaused = false
        phase = CollectionPhase.COLLECTING
        segmentIndex += 1
        startVideoSegment(session, segmentIndex)
        writeSessionMetadata(
            session,
            guardian,
            status = SessionStatus.RECORDING,
            preFlight = preFlightAtSessionStart,
            events = eventLogger.snapshot(),
            config = sessionConfig,
            segments = videoSegments.toList(),
            pauseIntervals = pauseIntervals.toList()
        )
    }

    fun stopSession(status: String = SessionStatus.COMPLETED) {
        if (!sessionClosing.compareAndSet(false, true)) return
        phase = CollectionPhase.FINALIZING
        val session = currentSession
        val summary = guardian.snapshotSummary()
        sessionSafety.stopMonitoring()
        CollectionForegroundService.stop(context)
        handsAlertAudio.setCollecting(false)
        if (isPaused && pauseStartedNs > 0L) {
            pauseIntervals.add(pauseStartedNs to SystemClock.elapsedRealtimeNanos())
        }
        try {
            closeCurrentSegment()
        } catch (e: Exception) {
            Log.e("DataCollection", "Failed to stop video", e)
        }
        activeRecording = null
        recordingRef.set(null)
        guardian.stop()
        qualityLogger.stop()
        sensorDataManager.forceFlushAndClose()
        val events = eventLogger.endSession()
        eventLogger.notifyStop()
        session?.let {
            ManifestWriter.writeCalibrationStub(it.dir)
            val allFiles = it.dir.walkTopDown().filter { file -> file.isFile }.toList()
            ManifestWriter.write(it.dir, it.sessionId, allFiles)
            writeSessionMetadata(
                it,
                guardianSummary = summary,
                status = status,
                preFlight = preFlightAtSessionStart,
                events = events,
                config = sessionConfig,
                segments = videoSegments.toList(),
                pauseIntervals = pauseIntervals.toList(),
                sensorGaps = sensorDataManager.sensorGapCount
            )
            val videoOk = it.dir.listFiles { f -> f.name.endsWith(".mp4") }?.any { f -> f.length() > 0 } == true
            val imuOk = it.imuFile.exists() && it.imuFile.length() > 0
            val cameraQuality = (100.0 - summary.blurredFramesPercentage - summary.underexposedFramesPercentage)
                .coerceIn(0.0, 100.0)
            lastReport = SessionReport(
                sessionId = it.sessionId,
                durationSeconds = (SystemClock.elapsedRealtimeNanos() - it.startTimestampNs) / 1_000_000_000.0,
                videoSaved = videoOk,
                imuSaved = imuOk,
                audioSaved = videoOk,
                handVisibilityPercent = summary.handsDetectedPercentage,
                workspaceVisibilityPercent = summary.workspaceVisiblePercentage,
                cameraQualityPercent = cameraQuality,
                droppedFrames = summary.droppedFrames,
                sensorGaps = sensorDataManager.sensorGapCount,
                storageBytes = it.dir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() },
                overall = visibilityClass,
                status = status
            )
        }
        sessionRef.set(null)
        currentSession = null
        isRecording = false
        isPaused = false
        onKeepScreenOn(false)
        phase = CollectionPhase.REPORT
        val savedMessage = when (status) {
            SessionStatus.INTERRUPTED_LOW_BATTERY -> "Critical battery: Session saved"
            SessionStatus.INTERRUPTED_LOW_STORAGE -> "Low storage: Session saved"
            SessionStatus.INTERRUPTED_SYSTEM -> "Interrupted: Session saved"
            SessionStatus.ERROR -> "Session ended with errors"
            else -> "Session saved"
        }
        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
        safetyBanner = null
        sessionClosing.set(false)
    }
    stopSessionRef.set { status -> stopSession(status) }

    fun startSession(overrideMount: Boolean = false) {
        recError = null
        safetyBanner = null
        sessionClosing.set(false)
        refreshPreFlight()
        val snapshot = preFlightChecker.evaluate()
        preFlight = snapshot
        if (!snapshot.canStartSession) {
            phase = CollectionPhase.PRE_FLIGHT
            showPreFlightOverlay = true
            Toast.makeText(
                context,
                "Pre-flight check failed. Fix the items in red and tap Re-check.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val session = SessionFiles.create(context.getExternalFilesDir(null), sessionConfig)
            currentSession = session
            sessionRef.set(session)
            preFlightAtSessionStart = snapshot
            videoSegments.clear()
            pauseIntervals.clear()
            segmentIndex = 0
            isPaused = false
            eventLogger.beginSession()
            eventLogger.notifyStart()
            qualityLogger.start(session.dir)
            CollectionForegroundService.start(context)

            writeSessionMetadata(
                session,
                guardianSummary = MetadataManager.GuardianSummary(
                    handsDetectedPercentage = 0.0,
                    totalAnalyzedFrames = 0
                ),
                status = SessionStatus.RECORDING,
                preFlight = snapshot,
                events = emptyList(),
                config = sessionConfig,
                mountOverride = overrideMount
            )

            sensorDataManager.startLogging(session.imuFile)
            guardian.start()
            handsAlertAudio.setCollecting(true)
            sessionSafety.startMonitoring()
            cameraController.videoCaptureQualitySelector = if (sessionConfig.profile == RecordingProfile.ENDURANCE) {
                QualitySelector.from(Quality.HD)
            } else {
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            }
            startVideoSegment(session, 0)
            isRecording = true
            phase = CollectionPhase.COLLECTING
            onKeepScreenOn(true)
            Toast.makeText(context, "Session started: ${session.sessionId}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("DataCollection", "Failed to start session", e)
            recError = e.message
            sessionSafety.stopMonitoring()
            CollectionForegroundService.stop(context)
            handsAlertAudio.setCollecting(false)
            guardian.stop()
            qualityLogger.stop()
            sensorDataManager.forceFlushAndClose()
            videoRecorder.forceFinalize()
            eventLogger.endSession()
            isRecording = false
            phase = CollectionPhase.WAITING_FOR_WEAR
            onKeepScreenOn(false)
            Toast.makeText(context, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        controller = cameraController
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PermissionDeniedPane(
                onRequest = { permissionLauncher.launch(REQUIRED_PERMISSIONS) }
            )
        }

        CollectionHud(
            isRecording = isRecording,
            isPaused = isPaused,
            elapsedMs = elapsedMs,
            handState = handState,
            workspaceVisible = workspaceVisible,
            visibilityClass = visibilityClass,
            storageHud = storageHud,
            recError = recError,
            eventFlash = eventFlash,
            qualityWarning = qualityWarning,
            safetyBanner = safetyBanner,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        )

        if (isRecording && !isPaused) {
            MarkEventButton(
                onMark = { eventLogger.record(SessionEvent.MARK) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            )
        }

        if (isRecording) {
            CollectingControlPanel(
                isPaused = isPaused,
                onPause = { pauseCapture() },
                onResume = { resumeCapture() },
                onStop = { stopSession() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            )
        }

        if (phase == CollectionPhase.SETUP && !isRecording) {
            SessionSetupOverlay(
                config = sessionConfig,
                onConfigChange = { sessionConfig = it },
                onContinue = {
                    phase = CollectionPhase.PRE_FLIGHT
                    showPreFlightOverlay = true
                    refreshPreFlight()
                }
            )
        }

        if (phase == CollectionPhase.PRE_FLIGHT && showPreFlightOverlay && !isRecording) {
            PreFlightOverlay(
                report = preFlight,
                onRefresh = { refreshPreFlight() },
                onRequestPermissions = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                onContinue = {
                    showPreFlightOverlay = false
                    phase = CollectionPhase.WAITING_FOR_WEAR
                }
            )
        }

        if (phase == CollectionPhase.WAITING_FOR_WEAR && !isRecording) {
            val mountReady = workspaceVisible && handState != HandVisibilityState.NONE
            MountingCheckOverlay(
                handState = handState,
                workspaceVisible = workspaceVisible,
                canStart = preFlight.canStartSession && mountReady,
                onStart = { startSession(overrideMount = false) },
                onOverride = { startSession(overrideMount = true) }
            )
        }

        lastReport?.let { report ->
            if (phase == CollectionPhase.REPORT) {
                SessionReportOverlay(
                    report = report,
                    onDone = {
                        lastReport = null
                        phase = CollectionPhase.SETUP
                    }
                )
            }
        }

        orphanSession?.let { orphan ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Unfinished session") },
                text = {
                    RecoveryDialogContent(
                        sessionId = orphan.sessionId,
                        onRecover = {
                            sessionRepository.recoverSession(orphan)
                            orphanSession = null
                            Toast.makeText(context, "Session recovered", Toast.LENGTH_SHORT).show()
                        },
                        onDiscard = {
                            sessionRepository.deleteSession(orphan)
                            orphanSession = null
                            Toast.makeText(context, "Session discarded", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                confirmButton = {}
            )
        }

        if (!isRecording && phase != CollectionPhase.SETUP && phase != CollectionPhase.REPORT) {
            Surface(
                onClick = onOpenSessions,
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCC111111),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 80.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Sessions",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionHud(
    isRecording: Boolean,
    isPaused: Boolean,
    elapsedMs: Long,
    handState: HandVisibilityState,
    workspaceVisible: Boolean,
    visibilityClass: VisibilityClass,
    storageHud: String,
    recError: String?,
    eventFlash: EventFlash?,
    qualityWarning: QualityWarning,
    safetyBanner: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            RecTimerBadge(
                isRecording = isRecording && !isPaused,
                elapsedMs = elapsedMs,
                paused = isPaused
            )
            QuietStatusStrip(
                isRecording = isRecording,
                handState = handState,
                workspaceVisible = workspaceVisible,
                visibilityClass = visibilityClass,
                storageHud = storageHud
            )
        }
        safetyBanner?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCCB71C1C))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (isRecording && !isPaused && qualityWarning.kind != QualityWarningKind.NONE && qualityWarning.message != null) {
            Text(
                text = qualityWarning.message,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCCF9A825))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        recError?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCCB00020))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        eventFlash?.let { flash ->
            val color = when (flash.eventType) {
                SessionEvent.SUCCESS -> Color(0xFF2E7D32)
                SessionEvent.FAILURE -> Color(0xFFC62828)
                else -> Color(0xFF1565C0)
            }
            Text(
                text = flash.message,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun MarkEventButton(
    onMark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onMark,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC111111),
        tonalElevation = 4.dp
    ) {
        Text(
            text = "[ 📍 MARK ]",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun RecTimerBadge(isRecording: Boolean, elapsedMs: Long, paused: Boolean = false) {
    val alpha by rememberInfiniteTransition(label = "rec-blink").animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 0.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec-alpha"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC111111))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) Color.Red.copy(alpha = alpha) else Color(0xFF9E9E9E)
                )
        )
        Text(
            text = when {
                paused -> "PAUSE  ${formatMmSs(elapsedMs)}"
                isRecording -> "REC  ${formatMmSs(elapsedMs)}"
                else -> "STANDBY"
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun QuietStatusStrip(
    isRecording: Boolean,
    handState: HandVisibilityState,
    workspaceVisible: Boolean,
    visibilityClass: VisibilityClass,
    storageHud: String
) {
    val handsLabel = when (handState) {
        HandVisibilityState.BOTH -> "Hands ✓"
        HandVisibilityState.LEFT -> "Hands L"
        HandVisibilityState.RIGHT -> "Hands R"
        HandVisibilityState.PARTIAL -> "Hands ~"
        HandVisibilityState.NONE -> "Hands ✕"
    }
    val camLabel = when (visibilityClass) {
        VisibilityClass.GOOD -> "Cam ✓"
        VisibilityClass.DEGRADED -> "Cam ~"
        VisibilityClass.POOR -> "Cam ✕"
    }
    val color = when {
        !isRecording -> Color(0xFF9E9E9E)
        visibilityClass == VisibilityClass.GOOD && handState != HandVisibilityState.NONE -> Color(0xFF2E7D32)
        visibilityClass == VisibilityClass.POOR || handState == HandVisibilityState.NONE -> Color(0xFFF9A825)
        else -> Color(0xFFF9A825)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC111111))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = "$handsLabel  $camLabel  $storageHud",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Text(
            text = if (workspaceVisible) "Workspace ✓" else "Workspace ✕",
            color = color,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun CollectingControlPanel(
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xD9111111),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = if (isPaused) onResume else onPause,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isPaused) "RESUME" else "PAUSE")
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Text("STOP")
            }
        }
    }
}

@Composable
private fun SessionControlPanel(
    isRecording: Boolean,
    startEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xD9111111),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ExtendedFloatingActionButton(
                onClick = onToggle,
                icon = {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop session" else "Start session"
                    )
                },
                text = {
                    Text(
                        text = if (isRecording) "STOP SESSION" else "START SESSION",
                        fontWeight = FontWeight.Bold
                    )
                },
                containerColor = when {
                    isRecording -> Color(0xFFC62828)
                    startEnabled -> MaterialTheme.colorScheme.primary
                    else -> Color(0xFF616161)
                },
                contentColor = Color.White
            )
            if (!isRecording && !startEnabled) {
                Text(
                    text = "Blocked until pre-flight checks pass",
                    color = Color(0xFFFFCDD2),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PreFlightOverlay(
    report: PreFlightReport,
    onRefresh: () -> Unit,
    onRequestPermissions: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE6111111)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Pre-Flight Check",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(
                    text = "All items must pass before a session can start.",
                    color = Color(0xFFBDBDBD),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                HorizontalDivider(color = Color(0x33FFFFFF))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PreFlightRow("Storage Space", report.storageDetail, report.storagePassed)
                    PreFlightRow("Battery Status", report.batteryDetail, report.batteryPassed)
                    PreFlightRow("IMU Sensors", report.sensorsDetail, report.sensorsPassed)
                    PreFlightRow("Hardware Permissions", report.permissionsDetail, report.permissionsPassed)
                    PreFlightRow("Thermal", report.thermalDetail, report.thermalPassed)
                    PreFlightRow("Timestamps", report.timestampDetail, report.timestampOk)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp)
                        )
                        Text("Re-check")
                    }
                    if (!report.permissionsPassed) {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Grant access")
                        }
                    } else {
                        Button(
                            onClick = onContinue,
                            enabled = report.canStartSession,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreFlightRow(title: String, detail: String, passed: Boolean) {
    val accent = if (passed) Color(0xFF2E7D32) else Color(0xFFC62828)
    val icon = if (passed) Icons.Filled.CheckCircle else Icons.Filled.Error
    val mark = if (passed) "🟢" else "🔴"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF2A2A2C))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$mark $title",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = detail,
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun PreFlightCompactBar(
    report: PreFlightReport,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (report.allPassed) Color(0xFF2E7D32) else Color(0xFFF9A825)
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xCC111111)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = if (report.allPassed) {
                    "Pre-flight OK · tap to review"
                } else {
                    "Pre-flight failed · tap to fix"
                },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PermissionDeniedPane(onRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 16.dp)
            )
            Text(
                text = "Wear and Work needs camera and microphone access to record egocentric video.",
                color = Color.White,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Grant permissions")
            }
        }
    }
}

/**
 * Session folder:
 * Android/data/com.example.roboticsdatacollector/files/session_<TIMESTAMP>/
 */
private data class SessionFiles(
    val sessionId: String,
    val dir: File,
    val imuFile: File,
    val metadataFile: File,
    val startTimestampNs: Long
) {
    fun videoFile(index: Int): File = File(dir, "video_%03d.mp4".format(index))

    companion object {
        fun create(filesDir: File?, config: SessionConfig): SessionFiles {
            val root = filesDir ?: throw IllegalStateException("App storage is not available")
            val startedAtMs = System.currentTimeMillis()
            val startNs = SystemClock.elapsedRealtimeNanos()
            val sessionId = "session_$startedAtMs"
            val experiment = sanitizePath(config.experiment)
            val participant = sanitizePath(config.participantId)
            val dir = File(root, "$experiment/$participant/$sessionId")
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Could not create $dir")
            }
            return SessionFiles(
                sessionId = sessionId,
                dir = dir,
                imuFile = File(dir, "imu_data.csv"),
                metadataFile = File(dir, "metadata.json"),
                startTimestampNs = startNs
            )
        }

        private fun sanitizePath(value: String): String {
            val cleaned = value.trim().ifBlank { "unknown" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            return cleaned.take(48)
        }
    }
}

private fun writeSessionMetadata(
    session: SessionFiles,
    guardian: DataCollectionGuardian,
    status: String,
    preFlight: PreFlightReport?,
    events: List<SessionEvent>,
    config: SessionConfig? = null,
    segments: List<VideoSegment> = emptyList(),
    pauseIntervals: List<Pair<Long, Long>> = emptyList(),
    mountOverride: Boolean = false
) {
    writeSessionMetadata(
        session,
        guardian.snapshotSummary(),
        status,
        preFlight,
        events,
        config,
        segments,
        pauseIntervals,
        sensorGaps = 0,
        mountOverride = mountOverride
    )
}

private fun writeSessionMetadata(
    session: SessionFiles,
    guardianSummary: MetadataManager.GuardianSummary,
    status: String,
    preFlight: PreFlightReport?,
    events: List<SessionEvent>,
    config: SessionConfig? = null,
    segments: List<VideoSegment> = emptyList(),
    pauseIntervals: List<Pair<Long, Long>> = emptyList(),
    sensorGaps: Int = 0,
    mountOverride: Boolean = false
) {
    val endNs = SystemClock.elapsedRealtimeNanos()
    val videoNames = segments.map { it.fileName }.ifEmpty {
        listOf("video_000.mp4")
    }
    val size = probeVideoSize(File(session.dir, videoNames.first()))
    MetadataManager.write(
        outputFile = session.metadataFile,
        metadata = MetadataManager.SessionMetadata(
            sessionId = session.sessionId,
            startTimestampNs = session.startTimestampNs,
            endTimestampNs = endNs,
            videoFile = videoNames.first(),
            imuFile = session.imuFile.name,
            guardianSummary = guardianSummary,
            preFlightStatus = preFlight,
            events = events,
            status = status,
            config = config,
            bootElapsedNsAtStart = session.startTimestampNs,
            recordingProfile = config?.profile?.id ?: RecordingProfile.QUALITY.id,
            achievedWidth = size.first,
            achievedHeight = size.second,
            videoFileNames = videoNames,
            pauseIntervalsNs = pauseIntervals,
            sensorGaps = sensorGaps,
            thermalAtEnd = 0
        )
    )
    if (mountOverride) {
        try {
            val json = JSONObject(session.metadataFile.readText())
            json.put("mount_check_overridden", true)
            session.metadataFile.writeText(json.toString(2))
        } catch (_: Exception) {
        }
    }
}

private fun probeVideoSize(file: File): Pair<Int, Int> {
    if (!file.exists()) return 0 to 0
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        retriever.release()
        w to h
    } catch (_: Exception) {
        0 to 0
    }
}

private fun formatMmSs(elapsedMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
