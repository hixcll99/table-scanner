package com.example.measurement

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Locates the ID-1 reference card(s) in a captured photo and refines corners to
 * subpixel precision. This is deliberately classical CV (contour detection +
 * cornerSubPix), not a learned/VLM approach: the downstream homography and
 * scale are only as good as these corner coordinates, and a general-purpose
 * vision-language model cannot be trusted for exact pixel geometry the way a
 * targeted, well-understood corner-refinement algorithm can.
 */
object CardDetector {

    /** Minimum confidence to trust a detection at all; below this we fail explicitly. */
    const val MIN_CONFIDENCE = 0.35f

    private data class Candidate(val quad: MatOfPoint2f, val score: Float, val boundingRect: Rect)

    /** Finds the single best card candidate in the frame. */
    fun detect(bitmap: Bitmap): CardDetectionResult {
        val gray = Mat()
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val candidates = findCandidates(gray, bitmap.width, bitmap.height)
            if (candidates.isEmpty()) {
                return CardDetectionResult(emptyList(), 0f, "لم يتم العثور على البطاقة، تأكد من وضوحها في الصورة.")
            }
            val best = candidates.first()
            if (best.score < MIN_CONFIDENCE) {
                candidates.forEach { it.quad.release() }
                return CardDetectionResult(
                    emptyList(),
                    best.score,
                    "لم يتم التعرف على البطاقة بثقة كافية، أعد المحاولة بإضاءة أفضل.",
                )
            }
            val ordered = orderCorners(best.quad.toArray())
            val refined = refineCornersSubpixel(gray, ordered)
            val result = CardDetectionResult(
                corners = refined.map { PointF2(it.x.toFloat(), it.y.toFloat()) },
                confidence = best.score,
            )
            candidates.forEach { it.quad.release() }
            return result
        } finally {
            rgba.release(); gray.release()
        }
    }

    /**
     * Finds up to [maxCards] non-overlapping card candidates, for the large-table
     * flow where two cards are placed at opposite corners/ends of the table so
     * two independent scale estimates can be cross-checked against each other.
     */
    fun detectMultiple(bitmap: Bitmap, maxCards: Int = 2): List<CardDetectionResult> {
        val gray = Mat()
        val rgba = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val candidates = findCandidates(gray, bitmap.width, bitmap.height)
            val chosen = mutableListOf<Candidate>()
            for (c in candidates) {
                if (chosen.size >= maxCards) break
                if (c.score < MIN_CONFIDENCE) break // sorted descending, so nothing further qualifies
                val overlapsExisting = chosen.any { boundingRectOverlapFraction(it.boundingRect, c.boundingRect) > 0.2 }
                if (!overlapsExisting) chosen.add(c)
            }
            val results = chosen.map { candidate ->
                val ordered = orderCorners(candidate.quad.toArray())
                val refined = refineCornersSubpixel(gray, ordered)
                CardDetectionResult(refined.map { PointF2(it.x.toFloat(), it.y.toFloat()) }, candidate.score)
            }
            candidates.forEach { it.quad.release() }
            return results
        } finally {
            rgba.release(); gray.release()
        }
    }

    private fun findCandidates(gray: Mat, imageWidth: Int, imageHeight: Int): List<Candidate> {
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, Mat())
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
            val candidates = mutableListOf<Candidate>()

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < imageArea * 0.002 || area > imageArea * 0.35) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
                contour2f.release()

                if (approx.total() != 4L || !Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                    approx.release()
                    continue
                }

                val pts = approx.toArray()
                val ordered = orderCorners(pts)
                val widthTop = distance(ordered[0], ordered[1])
                val widthBottom = distance(ordered[3], ordered[2])
                val heightLeft = distance(ordered[0], ordered[3])
                val heightRight = distance(ordered[1], ordered[2])
                val avgWidth = (widthTop + widthBottom) / 2.0
                val avgHeight = (heightLeft + heightRight) / 2.0
                if (avgHeight < 1e-3) { approx.release(); continue }

                val aspect = avgWidth / avgHeight
                val aspectDiff = minOf(
                    abs(aspect - ReferenceCard.ASPECT_RATIO),
                    abs(1.0 / aspect - ReferenceCard.ASPECT_RATIO),
                )
                if (aspectDiff > ReferenceCard.DETECTION_ASPECT_TOLERANCE) {
                    approx.release()
                    continue
                }

                val symmetry = 1.0 - (abs(widthTop - widthBottom) / avgWidth + abs(heightLeft - heightRight) / avgHeight) / 2.0
                val aspectScore = (1.0 - aspectDiff / ReferenceCard.DETECTION_ASPECT_TOLERANCE).coerceIn(0.0, 1.0)
                val sizeScore = (area / imageArea).coerceIn(0.0, 1.0)
                val score = (0.5 * aspectScore + 0.3 * symmetry + 0.2 * sizeScore).toFloat()

                candidates.add(Candidate(approx, score, Imgproc.boundingRect(MatOfPoint(*pts))))
            }
            return candidates.sortedByDescending { it.score }
        } finally {
            blurred.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun boundingRectOverlapFraction(a: Rect, b: Rect): Double {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width, b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)
        if (x2 <= x1 || y2 <= y1) return 0.0
        val interArea = (x2 - x1).toDouble() * (y2 - y1).toDouble()
        val smaller = minOf(a.width * a.height, b.width * b.height).toDouble()
        return if (smaller <= 0.0) 0.0 else interArea / smaller
    }

    /**
     * Refines approximate corner locations to subpixel precision using OpenCV's
     * iterative corner refinement (cornerSubPix), which fits the true corner
     * position within the local gradient neighbourhood rather than accepting the
     * nearest integer pixel from contour approximation.
     */
    private fun refineCornersSubpixel(gray: Mat, corners: List<Point>): List<Point> {
        val cornersMat = MatOfPoint2f(*corners.toTypedArray())
        val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.001)
        return try {
            Imgproc.cornerSubPix(gray, cornersMat, Size(5.0, 5.0), Size(-1.0, -1.0), criteria)
            cornersMat.toArray().toList()
        } catch (e: Exception) {
            // If refinement fails (e.g. too close to the image border), fall back
            // to the un-refined corners rather than crashing the pipeline.
            corners
        } finally {
            cornersMat.release()
        }
    }

    /** Orders 4 arbitrary points as TL, TR, BR, BL using sum/difference of coordinates. */
    private fun orderCorners(points: Array<Point>): List<Point> {
        val sorted = points.sortedBy { it.x + it.y }
        val tl = sorted.first()
        val br = sorted.last()
        val remaining = points.toList() - tl - br
        val tr = remaining.maxByOrNull { it.x - it.y } ?: remaining[0]
        val bl = remaining.minByOrNull { it.x - it.y } ?: remaining[1]
        return listOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
