package com.example.roboticsdatacollector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CameraX analyzer using MediaPipe Hands. A frame counts as "hands visible" only when
 * landmarks pass confidence **and** geometric checks. GPU is skipped on emulators
 * because the GPU delegate often returns 21 garbage landmarks on every frame.
 */
class DataCollectionGuardian(
    context: Context,
    private val targetAnalysisFps: Int = 4
) : ImageAnalysis.Analyzer {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val lastAnalyzedElapsedNs = AtomicLong(0L)
    private val totalAnalyzedFrames = AtomicInteger(0)
    private val handDetectedFrames = AtomicInteger(0)
    private val blurredFrames = AtomicInteger(0)
    private val underexposedFrames = AtomicInteger(0)
    private val overexposedFrames = AtomicInteger(0)
    private val blurStreak = AtomicInteger(0)
    private val darkStreak = AtomicInteger(0)
    private val brightStreak = AtomicInteger(0)
    private val obstructStreak = AtomicInteger(0)
    private val workspaceBadStreak = AtomicInteger(0)
    private val noneStreak = AtomicInteger(0)
    private val workspaceVisibleFrames = AtomicInteger(0)
    private val obstructedFrames = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)
    private val lastFrameTimestampNs = AtomicLong(0L)
    private val qualityAnalyzer = FrameQualityAnalyzer()
    private val landmarkerRef = AtomicReference<HandLandmarker?>(null)
    private val initAttempted = AtomicBoolean(false)
    private val handsOutHapticFired = AtomicBoolean(false)

    var onQualitySample: ((org.json.JSONObject) -> Unit)? = null
    var onHandsOutEscalation: (() -> Unit)? = null
    var onNoHandsAudioAlert: (() -> Unit)? = null
    var onHandsRecovered: (() -> Unit)? = null

    private val lastNoHandsAudioStreak = AtomicInteger(-1)

    @Volatile
    var detectorBackend: DetectorBackend = DetectorBackend.UNINITIALIZED
        private set

    private val minIntervalNs: Long = 1_000_000_000L / targetAnalysisFps.coerceIn(3, 5)

    private val _isHandVisible = MutableStateFlow(false)
    val isHandVisible: StateFlow<Boolean> = _isHandVisible.asStateFlow()

    private val _handState = MutableStateFlow(HandVisibilityState.NONE)
    val handState: StateFlow<HandVisibilityState> = _handState.asStateFlow()

    private val _workspaceVisible = MutableStateFlow(false)
    val workspaceVisible: StateFlow<Boolean> = _workspaceVisible.asStateFlow()

    private val _visibilityClass = MutableStateFlow(VisibilityClass.POOR)
    val visibilityClass: StateFlow<VisibilityClass> = _visibilityClass.asStateFlow()

    private val _analyzedFrameCount = MutableStateFlow(0)
    val analyzedFrameCount: StateFlow<Int> = _analyzedFrameCount.asStateFlow()

    private val _qualityWarning = MutableStateFlow(QualityWarning.None)
    val qualityWarning: StateFlow<QualityWarning> = _qualityWarning.asStateFlow()

    private val _guardianDegraded = MutableStateFlow(false)
    val guardianDegraded: StateFlow<Boolean> = _guardianDegraded.asStateFlow()

    fun start() {
        ensureLandmarker()
        totalAnalyzedFrames.set(0)
        handDetectedFrames.set(0)
        blurredFrames.set(0)
        underexposedFrames.set(0)
        overexposedFrames.set(0)
        blurStreak.set(0)
        darkStreak.set(0)
        brightStreak.set(0)
        obstructStreak.set(0)
        workspaceBadStreak.set(0)
        noneStreak.set(0)
        workspaceVisibleFrames.set(0)
        obstructedFrames.set(0)
        droppedFrames.set(0)
        lastFrameTimestampNs.set(0L)
        handsOutHapticFired.set(false)
        lastNoHandsAudioStreak.set(-1)
        lastAnalyzedElapsedNs.set(0L)
        _analyzedFrameCount.value = 0
        _isHandVisible.value = false
        _handState.value = HandVisibilityState.NONE
        _workspaceVisible.value = false
        _visibilityClass.value = VisibilityClass.POOR
        _qualityWarning.value = QualityWarning.None
        _guardianDegraded.value = detectorBackend == DetectorBackend.DISABLED
        running.set(true)
        Log.i(TAG, "Guardian started backend=$detectorBackend fps=$targetAnalysisFps")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(
            TAG,
            "Guardian stopped: analyzed=${totalAnalyzedFrames.get()} hands=${handDetectedFrames.get()}"
        )
    }

    fun close() {
        stop()
        landmarkerRef.getAndSet(null)?.closeQuietly()
        detectorBackend = DetectorBackend.UNINITIALIZED
        initAttempted.set(false)
    }

    fun snapshotSummary(): MetadataManager.GuardianSummary {
        val total = totalAnalyzedFrames.get().coerceAtLeast(0)
        val hits = handDetectedFrames.get()
        fun pct(count: Int): Double = if (total == 0) 0.0 else 100.0 * count / total
        return MetadataManager.GuardianSummary(
            handsDetectedPercentage = pct(hits),
            totalAnalyzedFrames = total,
            handDetectedFrames = hits,
            blurredFramesPercentage = pct(blurredFrames.get()),
            underexposedFramesPercentage = pct(underexposedFrames.get()),
            overexposedFramesPercentage = pct(overexposedFrames.get()),
            detector = detectorBackend.metadataName,
            modelAsset = MODEL_ASSET,
            workspaceVisiblePercentage = pct(workspaceVisibleFrames.get()),
            obstructedFramesPercentage = pct(obstructedFrames.get()),
            droppedFrames = droppedFrames.get(),
            guardianDegraded = _guardianDegraded.value
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!running.get()) return

            val nowNs = SystemClock.elapsedRealtimeNanos()
            val lastNs = lastAnalyzedElapsedNs.get()
            if (nowNs - lastNs < minIntervalNs) return
            lastAnalyzedElapsedNs.set(nowNs)

            ensureLandmarker()

            val frameTs = image.imageInfo.timestamp
            val previousTs = lastFrameTimestampNs.getAndSet(frameTs)
            if (previousTs > 0L && frameTs > previousTs) {
                val gapNs = frameTs - previousTs
                if (gapNs > EXPECTED_ANALYSIS_GAP_NS * 2) {
                    droppedFrames.addAndGet(((gapNs / EXPECTED_ANALYSIS_GAP_NS) - 1).toInt().coerceAtLeast(1))
                }
            }

            val quality = qualityAnalyzer.analyze(image)
            val hands = detectHands(image)
            val visible = hands != HandVisibilityState.NONE
            val total = totalAnalyzedFrames.incrementAndGet()
            if (visible) handDetectedFrames.incrementAndGet()
            if (quality.isBlurred) blurredFrames.incrementAndGet()
            if (quality.isUnderexposed) underexposedFrames.incrementAndGet()
            if (quality.isOverexposed) overexposedFrames.incrementAndGet()
            if (quality.workspaceVisible) workspaceVisibleFrames.incrementAndGet()
            if (quality.isObstructed) obstructedFrames.incrementAndGet()

            val ivs = computeVisibilityClass(hands, quality)
            updateQualityWarning(quality, hands)

            _isHandVisible.value = visible
            _handState.value = hands
            _workspaceVisible.value = quality.workspaceVisible
            _visibilityClass.value = ivs
            _analyzedFrameCount.value = total
            _guardianDegraded.value = detectorBackend == DetectorBackend.DISABLED

            onQualitySample?.invoke(
                org.json.JSONObject().apply {
                    put("timestamp_ns", nowNs)
                    put("hands", hands.name)
                    put("workspace_visible", quality.workspaceVisible)
                    put("obstructed", quality.isObstructed)
                    put("laplacian_variance", quality.laplacianVariance)
                    put("mean_luminance", quality.meanLuminance)
                    put("blurred", quality.isBlurred)
                    put("underexposed", quality.isUnderexposed)
                    put("overexposed", quality.isOverexposed)
                    put("ivs", ivs.name)
                    put("dropped_frames_total", droppedFrames.get())
                    put("guardian_degraded", _guardianDegraded.value)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed; capture continues", e)
            _guardianDegraded.value = true
        } finally {
            image.close()
        }
    }

    /**
     * Shows a HUD warning only after the defect is sustained for ~1 second
     * (~[targetAnalysisFps] consecutive analyzed frames).
     */
    private fun computeVisibilityClass(
        hands: HandVisibilityState,
        quality: FrameQuality
    ): VisibilityClass {
        val handsOk = hands == HandVisibilityState.BOTH || hands == HandVisibilityState.LEFT ||
            hands == HandVisibilityState.RIGHT
        return when {
            quality.isObstructed || hands == HandVisibilityState.NONE || !quality.workspaceVisible ->
                VisibilityClass.POOR
            hands == HandVisibilityState.BOTH &&
                quality.workspaceVisible &&
                !quality.isBlurred &&
                !quality.isUnderexposed &&
                !quality.isOverexposed -> VisibilityClass.GOOD
            handsOk && quality.workspaceVisible -> VisibilityClass.DEGRADED
            else -> VisibilityClass.POOR
        }
    }

    private fun updateQualityWarning(quality: FrameQuality, hands: HandVisibilityState) {
        val needed = targetAnalysisFps.coerceIn(3, 5)
        val blur = if (quality.isBlurred) blurStreak.incrementAndGet() else {
            blurStreak.set(0); 0
        }
        val dark = if (quality.isUnderexposed) darkStreak.incrementAndGet() else {
            darkStreak.set(0); 0
        }
        val bright = if (quality.isOverexposed) brightStreak.incrementAndGet() else {
            brightStreak.set(0); 0
        }
        val obstruct = if (quality.isObstructed) obstructStreak.incrementAndGet() else {
            obstructStreak.set(0); 0
        }
        val workspaceBad = if (!quality.workspaceVisible) workspaceBadStreak.incrementAndGet() else {
            workspaceBadStreak.set(0); 0
        }
        val none = if (hands == HandVisibilityState.NONE) noneStreak.incrementAndGet() else {
            val hadMissing = noneStreak.get() > 0
            noneStreak.set(0)
            handsOutHapticFired.set(false)
            lastNoHandsAudioStreak.set(-1)
            if (hadMissing) onHandsRecovered?.invoke()
            0
        }
        val fps = targetAnalysisFps.coerceIn(3, 5).toFloat()
        val noneSeconds = none / fps
        if (noneSeconds >= 8f && handsOutHapticFired.compareAndSet(false, true)) {
            onHandsOutEscalation?.invoke()
        }
        if (noneSeconds >= HandsAlertAudioManager.PERSIST_SECONDS) {
            val last = lastNoHandsAudioStreak.get()
            val sinceLastSec = if (last < 0) Float.POSITIVE_INFINITY else (none - last) / fps
            if (last < 0 || sinceLastSec >= HandsAlertAudioManager.COOLDOWN_SECONDS) {
                lastNoHandsAudioStreak.set(none)
                onNoHandsAudioAlert?.invoke()
            }
        }
        _qualityWarning.value = when {
            obstruct >= needed -> QualityWarning.Obstructed
            dark >= needed -> QualityWarning.TooDark
            bright >= needed -> QualityWarning.TooBright
            blur >= needed -> QualityWarning.TooMuchMotion
            noneSeconds >= 8f -> QualityWarning.HandsOut
            workspaceBad >= needed * 2 -> QualityWarning.Workspace
            else -> QualityWarning.None
        }
    }

    private fun detectHands(image: ImageProxy): HandVisibilityState {
        val landmarker = landmarkerRef.get() ?: return HandVisibilityState.NONE

        var mpImage: MPImage? = null
        var argb: Bitmap? = null
        return try {
            val source = image.toBitmap()
            val rotated = rotateIfNeeded(source, image.imageInfo.rotationDegrees)
            argb = ensureArgb8888(rotated)
            if (argb != rotated && rotated != source) {
                rotated.recycle()
            }
            mpImage = BitmapImageBuilder(argb).build()
            val result = landmarker.detect(mpImage)
            parseHandState(result)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe detect failed; treating frame as no-hands", e)
            _guardianDegraded.value = true
            HandVisibilityState.NONE
        } finally {
            mpImage?.close()
            argb?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /**
     * Rejects low-confidence and geometrically implausible landmark sets.
     * Emulator GPU often emits a full 21-point skeleton with no real hand.
     */
    private fun parseHandState(result: HandLandmarkerResult): HandVisibilityState {
        val hands = result.landmarks()
        if (hands.isEmpty()) return HandVisibilityState.NONE
        val handedness = result.handedness()
        var left = false
        var right = false
        var partial = false

        for (index in hands.indices) {
            val landmarks = hands[index]
            val score = handednessScore(handedness, index)
            if (!isPlausibleHand(landmarks, score)) continue
            if (isPartialHand(landmarks)) partial = true
            val label = handednessLabel(handedness, index)
            when {
                label.contains("left", ignoreCase = true) -> left = true
                label.contains("right", ignoreCase = true) -> right = true
                else -> right = true
            }
        }
        return when {
            left && right -> HandVisibilityState.BOTH
            partial && (left || right) -> HandVisibilityState.PARTIAL
            left -> HandVisibilityState.LEFT
            right -> HandVisibilityState.RIGHT
            else -> HandVisibilityState.NONE
        }
    }

    private fun handednessLabel(
        handedness: List<List<Category>>,
        handIndex: Int
    ): String {
        val categories = handedness.getOrNull(handIndex) ?: return ""
        return categories.maxByOrNull { it.score() }?.categoryName().orEmpty()
    }

    private fun isPartialHand(landmarks: List<NormalizedLandmark>): Boolean {
        var edgeHits = 0
        for (landmark in landmarks) {
            val x = landmark.x()
            val y = landmark.y()
            if (x < 0.03f || x > 0.97f || y < 0.03f || y > 0.97f) edgeHits++
        }
        return edgeHits >= 5
    }

    private fun handednessScore(
        handedness: List<List<Category>>,
        handIndex: Int
    ): Float {
        val categories = handedness.getOrNull(handIndex) ?: return 0f
        return categories.maxOfOrNull { it.score() } ?: 0f
    }

    private fun isPlausibleHand(
        landmarks: List<NormalizedLandmark>,
        handednessScore: Float
    ): Boolean {
        if (landmarks.size < HAND_LANDMARK_COUNT) return false
        if (handednessScore < MIN_HANDEDNESS_SCORE) return false

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var inFrame = 0
        var sumX = 0.0
        var sumY = 0.0

        for (landmark in landmarks) {
            val x = landmark.x()
            val y = landmark.y()
            if (!x.isFinite() || !y.isFinite()) return false
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            if (x in -0.05f..1.05f && y in -0.05f..1.05f) inFrame++
            sumX += x
            sumY += y
        }

        val width = maxX - minX
        val height = maxY - minY
        if (width < MIN_HAND_SPAN || height < MIN_HAND_SPAN) return false
        if (inFrame < MIN_LANDMARKS_IN_FRAME) return false

        val n = landmarks.size.toDouble()
        val meanX = sumX / n
        val meanY = sumY / n
        var varSum = 0.0
        for (landmark in landmarks) {
            val dx = landmark.x() - meanX
            val dy = landmark.y() - meanY
            varSum += dx * dx + dy * dy
        }
        val spread = sqrt(varSum / n)
        if (spread < MIN_LANDMARK_SPREAD) return false

        val wrist = landmarks[WRIST]
        val middleTip = landmarks[MIDDLE_FINGER_TIP]
        val palmLength = hypot(
            (middleTip.x() - wrist.x()).toDouble(),
            (middleTip.y() - wrist.y()).toDouble()
        )
        if (palmLength < MIN_PALM_LENGTH) return false

        return true
    }

    private fun ensureLandmarker() {
        if (initAttempted.get()) return
        synchronized(this) {
            if (!initAttempted.compareAndSet(false, true)) return
            if (!hasModelAsset()) {
                Log.w(TAG, "Missing assets/$MODEL_ASSET — hand detector disabled")
                detectorBackend = DetectorBackend.DISABLED
                return
            }

            val preferCpu = isEmulator()
            val order = if (preferCpu) {
                listOf(Delegate.CPU)
            } else {
                listOf(Delegate.GPU, Delegate.CPU)
            }

            for (delegate in order) {
                val created = createLandmarkerOrNull(delegate) ?: continue
                landmarkerRef.set(created)
                detectorBackend = if (delegate == Delegate.GPU) {
                    DetectorBackend.MEDIAPIPE_GPU
                } else {
                    DetectorBackend.MEDIAPIPE_CPU
                }
                Log.i(TAG, "HandLandmarker ready delegate=$delegate emulator=$preferCpu")
                return
            }
            detectorBackend = DetectorBackend.DISABLED
            Log.w(TAG, "MediaPipe Hands failed to initialize; HUD will stay on No Hands")
        }
    }

    private fun createLandmarkerOrNull(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(MIN_DETECTION)
                .setMinHandPresenceConfidence(MIN_PRESENCE)
                .setMinTrackingConfidence(MIN_TRACKING)
                .build()
            HandLandmarker.createFromOptions(appContext, options)
        } catch (e: Exception) {
            Log.w(TAG, "HandLandmarker init failed for delegate=$delegate", e)
            null
        }
    }

    private fun hasModelAsset(): Boolean {
        return try {
            appContext.assets.open(MODEL_ASSET).use { it.available() >= 0 }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        return converted
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Emulator") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("ranchu") ||
            hardware.contains("goldfish")
    }

    private fun HandLandmarker.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HandLandmarker", e)
        }
    }

    enum class DetectorBackend(val metadataName: String) {
        UNINITIALIZED("uninitialized"),
        MEDIAPIPE_GPU("mediapipe_hands_gpu"),
        MEDIAPIPE_CPU("mediapipe_hands_cpu"),
        DISABLED("disabled")
    }

    companion object {
        private const val TAG = "DataCollectionGuardian"
        const val MODEL_ASSET = "hand_landmarker.task"
        private const val HAND_LANDMARK_COUNT = 21
        private const val WRIST = 0
        private const val MIDDLE_FINGER_TIP = 12
        private const val MIN_DETECTION = 0.7f
        private const val MIN_PRESENCE = 0.7f
        private const val MIN_TRACKING = 0.6f
        private const val MIN_HANDEDNESS_SCORE = 0.65f
        private const val MIN_HAND_SPAN = 0.07f
        private const val MIN_LANDMARK_SPREAD = 0.03
        private const val MIN_PALM_LENGTH = 0.08
        private const val MIN_LANDMARKS_IN_FRAME = 16
        private const val EXPECTED_ANALYSIS_GAP_NS = 250_000_000L
    }
}
