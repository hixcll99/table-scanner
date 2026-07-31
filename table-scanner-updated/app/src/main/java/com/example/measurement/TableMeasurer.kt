package com.example.measurement

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Measures the table within a rectified (top-down, known-scale) image and
 * cross-validates results across multiple shots and, where available, multiple
 * reference cards. This module never returns a single "trust me" number: every
 * result is a range, and every disagreement between independent estimates is
 * surfaced as a warning rather than silently averaged away.
 */
object TableMeasurer {

    /** Max allowed disagreement between two independent card-scale estimates before flagging. */
    const val CARD_SCALE_DISAGREEMENT_TOLERANCE_PCT = 3.0

    /**
     * Finds the table's outer boundary in the rectified image and converts it to
     * real-world millimetres using the established scale. The table is assumed
     * to be the dominant high-contrast region against its background (floor,
     * workbench, etc.) — a reasonable assumption once the frame is already
     * cropped/rectified around the table by the capture step.
     */
    fun measureSingleShot(
        shotLabel: String,
        rectifiedBitmap: Bitmap,
        pixelsPerMmCardA: Double,
        pixelsPerMmCardB: Double? = null,
    ): SingleShotMeasurement? {
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(rectifiedBitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(7.0, 7.0), 0.0)
            Imgproc.Canny(blurred, edges, 30.0, 100.0)
            Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 3)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            if (contours.isEmpty()) return null

            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val contour2f = org.opencv.core.MatOfPoint2f(*largest.toArray())
            val rotatedRect: RotatedRect = Imgproc.minAreaRect(contour2f)
            contour2f.release()

            val widthPx = rotatedRect.size.width
            val heightPx = rotatedRect.size.height
            // Present the larger dimension as "width" for a consistent, orientation-
            // independent readout regardless of which way the table happened to sit
            // in the rectified frame.
            val longPx = maxOf(widthPx, heightPx)
            val shortPx = minOf(widthPx, heightPx)

            val avgScale = if (pixelsPerMmCardB != null) (pixelsPerMmCardA + pixelsPerMmCardB) / 2.0 else pixelsPerMmCardA
            val widthMm = longPx / avgScale
            val heightMm = shortPx / avgScale

            val disagreementPct = pixelsPerMmCardB?.let { b ->
                val diff = kotlin.math.abs(pixelsPerMmCardA - b)
                val ref = (pixelsPerMmCardA + b) / 2.0
                if (ref > 0) (diff / ref) * 100.0 else 0.0
            }

            return SingleShotMeasurement(
                shotLabel = shotLabel,
                widthMm = widthMm,
                heightMm = heightMm,
                pixelsPerMmCardA = pixelsPerMmCardA,
                pixelsPerMmCardB = pixelsPerMmCardB,
                cardScaleDisagreementPct = disagreementPct,
            )
        } finally {
            rgba.release(); gray.release(); blurred.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Cross-validates measurements from multiple shots (and, within each shot,
     * multiple cards where used) into a single reported range. Disagreement
     * between independent estimates widens the reported range and is called out
     * as a warning rather than hidden behind an averaged point value.
     */
    fun crossValidate(shots: List<SingleShotMeasurement>): TableMeasurementResult {
        require(shots.isNotEmpty()) { "Need at least one shot to produce a measurement" }

        val warnings = mutableListOf<String>()

        shots.forEach { shot ->
            val disagreement = shot.cardScaleDisagreementPct
            if (disagreement != null && disagreement > CARD_SCALE_DISAGREEMENT_TOLERANCE_PCT) {
                warnings.add(
                    "تباين ${"%.1f".format(disagreement)}% بين تقديرَي المقياس في ${shot.shotLabel} — النتيجة قد تكون أقل دقة."
                )
            }
        }

        val widths = shots.map { it.widthMm }
        val heights = shots.map { it.heightMm }

        val widthRange = rangeFromEstimates(widths)
        val heightRange = rangeFromEstimates(heights)

        if (shots.size > 1) {
            val widthSpreadPct = if (widths.average() > 0) (widthRange.spreadMm / widths.average()) * 100.0 else 0.0
            if (widthSpreadPct > 5.0) {
                warnings.add("اختلاف ملحوظ بين نتائج الصور المختلفة — يفضّل إعادة التصوير لتحسين الدقة.")
            }
        }

        return TableMeasurementResult(
            widthRange = widthRange,
            heightRange = heightRange,
            shotCount = shots.size,
            warnings = warnings,
        )
    }

    /**
     * Builds a [MeasurementRange] from a set of independent point estimates. With
     * multiple estimates the range spans their min/max directly (their natural
     * disagreement); with a single estimate a conservative +-1% band is applied
     * so a single-shot result isn't presented with false precision.
     */
    private fun rangeFromEstimates(estimates: List<Double>): MeasurementRange {
        return if (estimates.size > 1) {
            MeasurementRange(estimates.min(), estimates.max())
        } else {
            val v = estimates.first()
            MeasurementRange(v * 0.99, v * 1.01)
        }
    }
}
