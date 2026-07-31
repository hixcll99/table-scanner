package com.example.measurement

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Everything needed to decide, *before* we spend any time on precise measurement,
 * whether a captured frame is even worth measuring. This is the first and cheapest
 * line of defense: a blurry, badly tilted, shadowed, or clipped shot will silently
 * corrupt every downstream step (corner refinement, homography, scale), so we
 * reject those shots immediately with a plain-language reason instead of letting
 * them flow into the pipeline.
 */
object CaptureQuality {

    /**
     * Laplacian-variance threshold below which a frame is treated as too blurry.
     * This is a starting point tuned for ~12MP phone photos of a table at arm's
     * length; it's intentionally exposed as a parameter so it can be recalibrated
     * against the ground-truth validation set (see the test harness) instead of
     * being hard-coded once and forgotten.
     */
    const val DEFAULT_BLUR_VARIANCE_THRESHOLD = 60.0

    data class BlurAssessment(val variance: Double, val isSharpEnough: Boolean)

    /**
     * Computes the variance of the Laplacian of the grayscale image: a standard,
     * cheap no-reference blur metric. Sharp images with strong edges (like a table
     * edge or a card boundary) have high variance; blurred images are smoothed out
     * and score low.
     */
    fun assessBlur(
        bitmap: Bitmap,
        threshold: Double = DEFAULT_BLUR_VARIANCE_THRESHOLD,
    ): BlurAssessment {
        val rgba = Mat()
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)
            val sigma = stddev.toArray().getOrElse(0) { 0.0 }
            val variance = sigma * sigma
            return BlurAssessment(variance, variance >= threshold)
        } finally {
            rgba.release(); gray.release(); laplacian.release(); mean.release(); stddev.release()
        }
    }

    /**
     * Checks for a hard shadow crossing the card by sampling contrast along the
     * card's expected border. A hard shadow shows up as a strong, spatially-local
     * drop in local contrast on one side of the card versus the others, which is
     * different from (and not caught by) the global blur check above.
     *
     * [cardRegion] should be the axis-aligned bounding box of the card as located
     * by the live/quick contour scan (see [findCardLikeRegion]). Returns true if a
     * hard shadow is likely present.
     */
    fun hasHardShadowOnCard(bitmap: Bitmap, cardRegion: Rect): Boolean {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val safeRegion = clampRectToMat(cardRegion, gray)
            if (safeRegion.width <= 4 || safeRegion.height <= 4) return false
            val roi = Mat(gray, safeRegion)

            // Split the card ROI into a 2x2 grid and compare mean brightness across
            // quadrants. A strong, one-sided shadow shows up as a large brightness
            // gap between adjacent quadrants that a uniformly-lit (even if dim)
            // card would not have.
            val halfW = safeRegion.width / 2
            val halfH = safeRegion.height / 2
            if (halfW < 2 || halfH < 2) return false
            val quads = listOf(
                Rect(0, 0, halfW, halfH),
                Rect(halfW, 0, safeRegion.width - halfW, halfH),
                Rect(0, halfH, halfW, safeRegion.height - halfH),
                Rect(halfW, halfH, safeRegion.width - halfW, safeRegion.height - halfH),
            )
            val means = quads.map { q -> Core.mean(Mat(roi, q)).`val`[0] }
            val maxGap = (means.max() - means.min())
            // A gap of >60 (on a 0-255 scale) between quadrants of the same small
            // card is a strong signal of a hard shadow rather than natural texture.
            return maxGap > 60.0
        } finally {
            rgba.release(); gray.release()
        }
    }

    private fun clampRectToMat(rect: Rect, mat: Mat): Rect {
        val x = rect.x.coerceIn(0, mat.cols() - 1)
        val y = rect.y.coerceIn(0, mat.rows() - 1)
        val w = rect.width.coerceAtMost(mat.cols() - x)
        val h = rect.height.coerceAtMost(mat.rows() - y)
        return Rect(x, y, w, h)
    }

    /**
     * Fast, low-precision scan for a card-like quadrilateral, used only to answer
     * "is something card-shaped fully inside the frame, not clipped at an edge?"
     * during live preview. This intentionally does *not* do subpixel refinement or
     * strict aspect-ratio matching -- that precision work happens once, after
     * capture, in [CardDetector]. Returns null if nothing card-like is found.
     */
    fun findCardLikeRegion(bitmap: Bitmap): Rect? {
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, Mat())
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            var best: Rect? = null
            var bestScore = 0.0
            val minArea = bitmap.width * bitmap.height * 0.01 // ignore tiny noise contours

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea) continue
                val rect = Imgproc.boundingRect(contour)
                val aspect = rect.width.toDouble() / rect.height.toDouble()
                val aspectMatch =
                    abs(aspect - ReferenceCard.ASPECT_RATIO) < ReferenceCard.DETECTION_ASPECT_TOLERANCE ||
                        abs(1.0 / aspect - ReferenceCard.ASPECT_RATIO) < ReferenceCard.DETECTION_ASPECT_TOLERANCE
                if (!aspectMatch) continue
                if (area > bestScore) {
                    bestScore = area
                    best = rect
                }
            }
            return best
        } finally {
            rgba.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * True if [region] is fully within [frameWidth] x [frameHeight] with a small
     * safety margin, i.e. not clipped at the frame edge.
     */
    fun isRegionFullyInFrame(
        region: Rect,
        frameWidth: Int,
        frameHeight: Int,
        marginPx: Int = 8,
    ): Boolean {
        return region.x >= marginPx &&
            region.y >= marginPx &&
            region.x + region.width <= frameWidth - marginPx &&
            region.y + region.height <= frameHeight - marginPx
    }

    /**
     * Runs the full post-shutter gate on a captured photo (blur + shadow +
     * in-frame checks) and returns every rejection reason that applies, so the UI
     * can show the most relevant plain-language message.
     */
    fun evaluateCapturedPhoto(
        bitmap: Bitmap,
        filteredTiltG: Float,
        tiltThresholdG: Float = TiltFilter.DEFAULT_TILT_THRESHOLD_G,
        blurThreshold: Double = DEFAULT_BLUR_VARIANCE_THRESHOLD,
    ): CaptureQualityResult {
        val rejections = mutableListOf<CaptureRejectionReason>()

        val blur = assessBlur(bitmap, blurThreshold)
        if (!blur.isSharpEnough) rejections.add(CaptureRejectionReason.TOO_BLURRY)

        if (maxOf(abs(filteredTiltG), abs(filteredTiltG)) > tiltThresholdG) {
            rejections.add(CaptureRejectionReason.TILT_TOO_HIGH)
        }

        val cardRegion = findCardLikeRegion(bitmap)
        if (cardRegion == null || !isRegionFullyInFrame(cardRegion, bitmap.width, bitmap.height)) {
            rejections.add(CaptureRejectionReason.CARD_NOT_FULLY_VISIBLE)
        } else if (hasHardShadowOnCard(bitmap, cardRegion)) {
            rejections.add(CaptureRejectionReason.HARD_SHADOW_ON_CARD)
        }

        return CaptureQualityResult(
            passed = rejections.isEmpty(),
            rejections = rejections,
            blurVariance = blur.variance,
            filteredTiltG = filteredTiltG,
        )
    }
}

/**
 * Exponential low-pass filter for raw accelerometer readings. Raw readings are
 * noisy enough that a bare threshold check makes the level indicator visibly
 * jitter even when the phone is genuinely still; smoothing the signal first gives
 * a stable reading without adding perceptible input lag.
 */
class TiltFilter(private val alpha: Float = 0.15f) {
    private var filteredX = 0f
    private var filteredY = 0f
    private var initialized = false

    /** Feed a raw (rawX, rawY) reading in units of g; returns the filtered value. */
    fun update(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!initialized) {
            filteredX = rawX
            filteredY = rawY
            initialized = true
        } else {
            filteredX += alpha * (rawX - filteredX)
            filteredY += alpha * (rawY - filteredY)
        }
        return filteredX to filteredY
    }

    companion object {
        /**
         * Workable tilt threshold for a handheld shot, in g on each axis.
         * 0.03g (~1.7 degrees) is the previous threshold and is unrealistically
         * strict for someone holding a phone freehand above a table. 0.06g
         * (~3.4 degrees) is tight enough that the perspective/lens-distortion
         * correction step can still fully compensate for the residual tilt, while
         * being something a person can actually hit and hold without their arm
         * shaking against the limit.
         */
        const val DEFAULT_TILT_THRESHOLD_G = 0.06f

        /** Extra margin above the threshold shown as "getting close" rather than "too far". */
        const val TILT_WARN_MARGIN_G = 0.04f
    }
}
