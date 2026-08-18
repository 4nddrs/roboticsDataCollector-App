package com.example.roboticsdatacollector

import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * Lightweight CameraX Y-plane quality metrics (no OpenCV).
 *
 * Sharpness: variance of a 4-neighbor Laplacian on the luminance plane.
 * Exposure: mean luminance in 0–255.
 */
class FrameQualityAnalyzer(
    private val sampleStep: Int = 4,
    private val blurThreshold: Double = THRESHOLD_BLUR,
    private val underexposedThreshold: Double = THRESHOLD_UNDEREXPOSED,
    private val overexposedThreshold: Double = THRESHOLD_OVEREXPOSED
) {

    fun analyze(image: ImageProxy): FrameQuality {
        val width = image.width
        val height = image.height
        if (width < 3 || height < 3 || image.planes.isEmpty()) {
            return FrameQuality.invalid()
        }

        val yPlane = image.planes[0]
        val buffer = yPlane.buffer.duplicate()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val capacity = buffer.capacity()
        val step = max(1, sampleStep)

        fun luma(x: Int, y: Int): Int {
            val index = y * rowStride + x * pixelStride
            if (index < 0 || index >= capacity) return 0
            return buffer.get(index).toInt() and 0xFF
        }

        var count = 0L
        var sumY = 0L
        var sumLap = 0.0
        var sumLapSq = 0.0

        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val center = luma(x, y)
                sumY += center
                val lap = (4 * center - luma(x - 1, y) - luma(x + 1, y) - luma(x, y - 1) - luma(x, y + 1)).toDouble()
                sumLap += lap
                sumLapSq += lap * lap
                count++
                x += step
            }
            y += step
        }

        if (count == 0L) return FrameQuality.invalid()

        val meanY = sumY.toDouble() / count
        val meanLap = sumLap / count
        val laplacianVariance = (sumLapSq / count) - meanLap * meanLap

        return FrameQuality(
            laplacianVariance = laplacianVariance.coerceAtLeast(0.0),
            meanLuminance = meanY,
            isBlurred = laplacianVariance < blurThreshold,
            isUnderexposed = meanY < underexposedThreshold,
            isOverexposed = meanY > overexposedThreshold
        )
    }

    companion object {
        const val THRESHOLD_BLUR = 80.0
        const val THRESHOLD_UNDEREXPOSED = 40.0
        const val THRESHOLD_OVEREXPOSED = 220.0
    }
}

data class FrameQuality(
    val laplacianVariance: Double,
    val meanLuminance: Double,
    val isBlurred: Boolean,
    val isUnderexposed: Boolean,
    val isOverexposed: Boolean
) {
    companion object {
        fun invalid() = FrameQuality(
            laplacianVariance = 0.0,
            meanLuminance = 0.0,
            isBlurred = false,
            isUnderexposed = false,
            isOverexposed = false
        )
    }
}

enum class QualityWarningKind {
    NONE,
    BLUR,
    UNDEREXPOSED,
    OVEREXPOSED
}

data class QualityWarning(
    val kind: QualityWarningKind,
    val message: String?
) {
    companion object {
        val None = QualityWarning(QualityWarningKind.NONE, null)
        val TooMuchMotion = QualityWarning(QualityWarningKind.BLUR, "⚠️ Too much motion")
        val TooDark = QualityWarning(QualityWarningKind.UNDEREXPOSED, "⚠️ Low light")
        val TooBright = QualityWarning(QualityWarningKind.OVEREXPOSED, "⚠️ Too much light")
    }
}
