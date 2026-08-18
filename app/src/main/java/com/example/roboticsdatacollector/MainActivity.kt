package com.example.roboticsdatacollector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var sensorLogger: SensorLogger
    private lateinit var guardian: DataCollectionGuardian
    private lateinit var eventLogger: SessionEventLogger
    private var analysisExecutor: ExecutorService? = null

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
        sensorLogger = SensorLogger(this)
        guardian = DataCollectionGuardian(this, targetAnalysisFps = 4)
        eventLogger = SessionEventLogger(HapticFeedbackManager(this))
        analysisExecutor = Executors.newSingleThreadExecutor()

        setContent {
            RoboticsDataCollectorTheme {
                DataCollectionScreen(
                    sensorLogger = sensorLogger,
                    guardian = guardian,
                    eventLogger = eventLogger,
                    analysisExecutor = analysisExecutor!!,
                    onKeepScreenOn = { enabled ->
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!eventLogger.isRecording) return super.onKeyDown(keyCode, event)
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
        if (!eventLogger.isRecording) return super.onKeyUp(keyCode, event)
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

    override fun onDestroy() {
        keyHandler.removeCallbacks(commitVolumeDownSuccess)
        try {
            guardian.close()
            sensorLogger.release()
            analysisExecutor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy cleanup", e)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val VOLUME_DOWN_LONG_PRESS_MS = 450L
        private const val VOLUME_DOWN_DOUBLE_PRESS_MS = 320L
    }
}

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

@Composable
fun DataCollectionScreen(
    sensorLogger: SensorLogger,
    guardian: DataCollectionGuardian,
    eventLogger: SessionEventLogger,
    analysisExecutor: ExecutorService,
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
    var recError by remember { mutableStateOf<String?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var currentSession by remember { mutableStateOf<SessionFiles?>(null) }
    val recordingRef = remember { AtomicReference<Recording?>(null) }
    val sessionRef = remember { AtomicReference<SessionFiles?>(null) }

    val isHandVisible by guardian.isHandVisible.collectAsState()
    val analyzedFrameCount by guardian.analyzedFrameCount.collectAsState()
    val qualityWarning by guardian.qualityWarning.collectAsState()
    val eventFlash by eventLogger.flash.collectAsState()

    LaunchedEffect(eventFlash?.timestampNs) {
        if (eventFlash == null) return@LaunchedEffect
        delay(1_400)
        eventLogger.clearFlash()
    }

    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(200)
        }
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(
                CameraController.VIDEO_CAPTURE or CameraController.IMAGE_ANALYSIS
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
        onDispose {
            try {
                recordingRef.get()?.stop()
            } catch (_: Exception) {
            }
            sessionRef.get()?.let { session ->
                writeSessionMetadata(
                    session,
                    guardian,
                    status = "aborted",
                    preFlight = preFlightAtSessionStart,
                    events = eventLogger.endSession()
                )
            }
            guardian.stop()
            sensorLogger.stopLogging()
            onKeepScreenOn(false)
        }
    }

    fun stopSession() {
        val session = currentSession
        val summary = guardian.snapshotSummary()
        try {
            (recordingRef.get() ?: activeRecording)?.stop()
        } catch (e: Exception) {
            Log.e("DataCollection", "Failed to stop video", e)
        }
        activeRecording = null
        recordingRef.set(null)
        guardian.stop()
        sensorLogger.stopLogging()
        val events = eventLogger.endSession()
        session?.let {
            writeSessionMetadata(
                it,
                guardianSummary = summary,
                status = "completed",
                preFlight = preFlightAtSessionStart,
                events = events
            )
        }
        sessionRef.set(null)
        currentSession = null
        isRecording = false
        onKeepScreenOn(false)
        Toast.makeText(context, "Session saved", Toast.LENGTH_SHORT).show()
    }

    fun startSession() {
        recError = null
        refreshPreFlight()
        val snapshot = preFlightChecker.evaluate()
        preFlight = snapshot
        if (!snapshot.canStartSession) {
            showPreFlightOverlay = true
            Toast.makeText(
                context,
                "Pre-flight check failed. Fix the items in red and tap Re-check.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val session = SessionFiles.create(context.getExternalFilesDir(null))
            currentSession = session
            sessionRef.set(session)
            preFlightAtSessionStart = snapshot
            eventLogger.beginSession()

            sensorLogger.startLogging(session.imuFile)
            guardian.start()

            val outputOptions = FileOutputOptions.Builder(session.videoFile).build()
            val audioConfig = AudioConfig.create(true)
            val mainExecutor = ContextCompat.getMainExecutor(context)

            activeRecording = cameraController.startRecording(
                outputOptions,
                audioConfig,
                mainExecutor
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        onKeepScreenOn(true)
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            recError = event.cause?.message ?: "Recording error"
                            Log.e("DataCollection", "Finalize error: ${event.error}", event.cause)
                            try {
                                val failedSummary = guardian.snapshotSummary()
                                guardian.stop()
                                sensorLogger.stopLogging()
                                currentSession?.let {
                                    writeSessionMetadata(
                                        it,
                                        failedSummary,
                                        status = "error",
                                        preFlight = preFlightAtSessionStart,
                                        events = eventLogger.endSession()
                                    )
                                }
                            } catch (_: Exception) {
                            }
                            isRecording = false
                            onKeepScreenOn(false)
                        }
                    }
                }
            }
            recordingRef.set(activeRecording)
            isRecording = true
            onKeepScreenOn(true)
            Toast.makeText(context, "Session started: ${session.sessionId}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("DataCollection", "Failed to start session", e)
            recError = e.message
            guardian.stop()
            sensorLogger.stopLogging()
            eventLogger.endSession()
            isRecording = false
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
            elapsedMs = elapsedMs,
            isHandVisible = isHandVisible,
            analyzedFrameCount = analyzedFrameCount,
            recError = recError,
            eventFlash = eventFlash,
            qualityWarning = qualityWarning,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        )

        if (isRecording) {
            MarkEventButton(
                onMark = { eventLogger.record(SessionEvent.MARK) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            )
        }

        SessionControlPanel(
            isRecording = isRecording,
            startEnabled = preFlight.canStartSession,
            onToggle = {
                if (isRecording) {
                    stopSession()
                    return@SessionControlPanel
                }
                if (!preFlight.canStartSession) {
                    showPreFlightOverlay = true
                    if (!preFlight.permissionsPassed) {
                        permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    } else {
                        refreshPreFlight()
                    }
                    return@SessionControlPanel
                }
                startSession()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        )

        if (showPreFlightOverlay && !isRecording) {
            PreFlightOverlay(
                report = preFlight,
                onRefresh = { refreshPreFlight() },
                onRequestPermissions = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                onContinue = { showPreFlightOverlay = false }
            )
        } else if (!isRecording) {
            PreFlightCompactBar(
                report = preFlight,
                onOpen = { showPreFlightOverlay = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 118.dp)
            )
        }
    }
}

@Composable
private fun CollectionHud(
    isRecording: Boolean,
    elapsedMs: Long,
    isHandVisible: Boolean,
    analyzedFrameCount: Int,
    recError: String?,
    eventFlash: EventFlash?,
    qualityWarning: QualityWarning,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            RecTimerBadge(isRecording = isRecording, elapsedMs = elapsedMs)
            GuardianStatusBadge(
                isHandVisible = isHandVisible,
                analyzedFrameCount = analyzedFrameCount,
                sessionActive = isRecording
            )
        }
        if (isRecording && qualityWarning.kind != QualityWarningKind.NONE && qualityWarning.message != null) {
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
private fun RecTimerBadge(isRecording: Boolean, elapsedMs: Long) {
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
            text = if (isRecording) "REC  ${formatMmSs(elapsedMs)}" else "STANDBY",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun GuardianStatusBadge(
    isHandVisible: Boolean,
    analyzedFrameCount: Int,
    sessionActive: Boolean
) {
    val label = when {
        !sessionActive -> "Guardian idle"
        isHandVisible -> "Hands Detected"
        else -> "No Hands"
    }
    val color = when {
        !sessionActive -> Color(0xFF9E9E9E)
        isHandVisible -> Color(0xFF2E7D32)
        else -> Color(0xFFF9A825)
    }
    val prefix = when {
        !sessionActive -> ""
        isHandVisible -> "🖐️ "
        else -> "⚠️ "
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC111111))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = "$prefix$label",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
        if (sessionActive) {
            Text(
                text = "frames: $analyzedFrameCount",
                color = Color(0xFFBDBDBD),
                fontSize = 10.sp
            )
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
    val videoFile: File,
    val imuFile: File,
    val metadataFile: File,
    val startTimestampNs: Long
) {
    companion object {
        fun create(filesDir: File?): SessionFiles {
            val root = filesDir ?: throw IllegalStateException("App storage is not available")
            val startedAtMs = System.currentTimeMillis()
            val startNs = SystemClock.elapsedRealtimeNanos()
            val sessionId = "session_$startedAtMs"
            val dir = File(root, sessionId)
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Could not create $dir")
            }
            return SessionFiles(
                sessionId = sessionId,
                dir = dir,
                videoFile = File(dir, "video.mp4"),
                imuFile = File(dir, "imu_data.csv"),
                metadataFile = File(dir, "metadata.json"),
                startTimestampNs = startNs
            )
        }
    }
}

private fun writeSessionMetadata(
    session: SessionFiles,
    guardian: DataCollectionGuardian,
    status: String,
    preFlight: PreFlightReport?,
    events: List<SessionEvent>
) {
    writeSessionMetadata(session, guardian.snapshotSummary(), status, preFlight, events)
}

private fun writeSessionMetadata(
    session: SessionFiles,
    guardianSummary: MetadataManager.GuardianSummary,
    status: String,
    preFlight: PreFlightReport?,
    events: List<SessionEvent>
) {
    val endNs = SystemClock.elapsedRealtimeNanos()
    MetadataManager.write(
        outputFile = session.metadataFile,
        metadata = MetadataManager.SessionMetadata(
            sessionId = session.sessionId,
            startTimestampNs = session.startTimestampNs,
            endTimestampNs = endNs,
            videoFile = session.videoFile.name,
            imuFile = session.imuFile.name,
            guardianSummary = guardianSummary,
            preFlightStatus = preFlight,
            events = events,
            status = status
        )
    )
}

private fun formatMmSs(elapsedMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
