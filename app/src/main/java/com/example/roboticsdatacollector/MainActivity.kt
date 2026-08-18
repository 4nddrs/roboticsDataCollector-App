package com.example.roboticsdatacollector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    private var analysisExecutor: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sensorLogger = SensorLogger(this)
        guardian = DataCollectionGuardian(this, targetAnalysisFps = 4)
        analysisExecutor = Executors.newSingleThreadExecutor()

        setContent {
            RoboticsDataCollectorTheme {
                DataCollectionScreen(
                    sensorLogger = sensorLogger,
                    guardian = guardian,
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

    override fun onDestroy() {
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
    analysisExecutor: ExecutorService,
    onKeepScreenOn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
        if (!permissionsGranted) {
            Toast.makeText(
                context,
                "Camera and microphone permissions are required to collect data",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    var isRecording by remember { mutableStateOf(false) }
    var recError by remember { mutableStateOf<String?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var currentSession by remember { mutableStateOf<SessionFiles?>(null) }
    val recordingRef = remember { AtomicReference<Recording?>(null) }
    val sessionRef = remember { AtomicReference<SessionFiles?>(null) }

    val isHandVisible by guardian.isHandVisible.collectAsState()
    val analyzedFrameCount by guardian.analyzedFrameCount.collectAsState()

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
                writeSessionMetadata(session, guardian, status = "aborted")
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
        session?.let {
            writeSessionMetadata(it, guardianSummary = summary, status = "completed")
        }
        sessionRef.set(null)
        currentSession = null
        isRecording = false
        onKeepScreenOn(false)
        Toast.makeText(context, "Session saved", Toast.LENGTH_SHORT).show()
    }

    fun startSession() {
        recError = null
        try {
            val session = SessionFiles.create(context.getExternalFilesDir(null))
            currentSession = session
            sessionRef.set(session)

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
                                    writeSessionMetadata(it, failedSummary, status = "error")
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
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        )

        SessionControlPanel(
            isRecording = isRecording,
            onToggle = {
                if (!permissionsGranted) {
                    permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    return@SessionControlPanel
                }
                if (isRecording) stopSession() else startSession()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        )
    }
}

@Composable
private fun CollectionHud(
    isRecording: Boolean,
    elapsedMs: Long,
    isHandVisible: Boolean,
    analyzedFrameCount: Int,
    recError: String?,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
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
                containerColor = if (isRecording) Color(0xFFC62828) else MaterialTheme.colorScheme.primary,
                contentColor = Color.White
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
    status: String
) {
    writeSessionMetadata(session, guardian.snapshotSummary(), status)
}

private fun writeSessionMetadata(
    session: SessionFiles,
    guardianSummary: MetadataManager.GuardianSummary,
    status: String
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
