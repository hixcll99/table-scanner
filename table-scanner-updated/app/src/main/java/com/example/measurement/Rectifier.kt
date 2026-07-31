package com.example.measurement

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Corrects lens distortion (important on wide/ultra-wide phone lenses, which
 * bend straight lines noticeably near the frame edges) and rectifies the
 * detected card + table region to a true top-down view with a known
 * pixels-per-millimetre scale, via a homography computed from the detected
 * card corners.
 */
object Rectifier {

    data class LensIntrinsics(
        val focalLengthPx: DoubleArray?,
        val principalPointPx: DoubleArray?,
        val distortionCoeffs: DoubleArray?,
    )

    /**
     * Reads lens distortion coefficients and intrinsics for the active back
     * camera from CameraCharacteristics, where the device/HAL exposes them.
     * Many devices do not report LENS_DISTORTION or LENS_INTRINSIC_CALIBRATION;
     * in that case this returns null fields and [undistort] becomes a no-op,
     * which is safe (perspective rectification still runs, just without the
     * lens-curvature correction pre-step).
     */
    fun readLensIntrinsics(context: Context): LensIntrinsics {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return LensIntrinsics(null, null, null)

            val chars = manager.getCameraCharacteristics(backCameraId)
            val intrinsic = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { arr ->
                doubleArrayOf(arr[0].toDouble(), arr[1].toDouble(), arr[2].toDouble(), arr[3].toDouble(), arr[4].toDouble())
            }
            val distortion = chars.get(CameraCharacteristics.LENS_DISTORTION)?.let { arr ->
                DoubleArray(arr.size) { i -> arr[i].toDouble() }
            }
            val focal = intrinsic?.let { doubleArrayOf(it[0], it[1]) }
            val principal = intrinsic?.let { doubleArrayOf(it[2], it[3]) }
            LensIntrinsics(focal, principal, distortion)
        } catch (e: Exception) {
            LensIntrinsics(null, null, null)
        }
    }

    /**
     * Undistorts [bitmap] using the given intrinsics. Returns the input bitmap
     * unchanged if intrinsics/distortion coefficients aren't available, since
     * partial/guessed correction is worse than none.
     */
    fun undistort(bitmap: Bitmap, intrinsics: LensIntrinsics): Bitmap {
        val focal = intrinsics.focalLengthPx
        val principal = intrinsics.principalPointPx
        val dist = intrinsics.distortionCoeffs
        if (focal == null || principal == null || dist == null || dist.size < 4) return bitmap

        val src = Mat()
        val dst = Mat()
        val cameraMatrix = Mat(3, 3, CvType.CV_64F)
        val distCoeffs = MatOfDouble()
        try {
            Utils.bitmapToMat(bitmap, src)
            cameraMatrix.put(0, 0, focal[0], 0.0, principal[0], 0.0, focal[1], principal[1], 0.0, 0.0, 1.0)
            // Camera2's LENS_DISTORTION is [k1, k2, k3, p1, p2] (radial x3, tangential x2);
            // OpenCV's undistort expects [k1, k2, p1, p2, k3] — reorder accordingly.
            distCoeffs.fromArray(dist[0], dist[1], dist[3], dist[4], dist[2])
            Calib3d.undistort(src, dst, cameraMatrix, distCoeffs)
            val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, out)
            return out
        } catch (e: Exception) {
            return bitmap
        } finally {
            src.release(); dst.release(); cameraMatrix.release(); distCoeffs.release()
        }
    }

    /** Origin offset (in output px) at which the anchor card's TL corner lands. Kept fixed so callers can map other points into the same rectified space. */
    private const val CANVAS_MARGIN_MM = 20.0

    /**
     * Computes the homography that maps [cardCorners] (in raw image pixel space)
     * onto the card's known real-world rectangle, expressed in output pixels at
     * [pixelsPerMm]. Because this maps a *known* physical rectangle to a
     * *chosen* pixel rectangle, the anchor card's own size is always exactly
     * self-consistent by construction — it does not, by itself, validate
     * anything. The value of computing it explicitly (rather than only inside
     * [rectify]) is that it can also be applied to *other* points (e.g. a second
     * reference card elsewhere in the frame) via [transformPoints], and how far
     * those land from their own known size is a genuine, independent check of
     * the homography's accuracy across the table.
     */
    fun computeHomography(cardCorners: List<PointF2>, pixelsPerMm: Double): Mat {
        require(cardCorners.size == 4) { "Homography requires exactly 4 card corners" }
        val srcPoints = MatOfPoint2f(
            *cardCorners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
        )
        val cardWidthPx = ReferenceCard.WIDTH_MM * pixelsPerMm
        val cardHeightPx = ReferenceCard.HEIGHT_MM * pixelsPerMm
        val marginPx = CANVAS_MARGIN_MM * pixelsPerMm
        val dstPoints = MatOfPoint2f(
            Point(marginPx, marginPx),
            Point(marginPx + cardWidthPx, marginPx),
            Point(marginPx + cardWidthPx, marginPx + cardHeightPx),
            Point(marginPx, marginPx + cardHeightPx),
        )
        try {
            return Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        } finally {
            srcPoints.release(); dstPoints.release()
        }
    }

    /** Applies [homography] to arbitrary image points (e.g. a second card's corners). */
    fun transformPoints(homography: Mat, points: List<PointF2>): List<PointF2> {
        val src = MatOfPoint2f(*points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f()
        try {
            Core.perspectiveTransform(src, dst, homography)
            return dst.toArray().map { PointF2(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            src.release(); dst.release()
        }
    }

    /**
     * Applies [homography] to the whole (undistorted) frame to produce a
     * top-down rectified image with a uniform, known pixels-per-mm scale
     * everywhere, which is what makes measuring table edges in it meaningful.
     *
     * [pixelsPerMm] must match the scale [homography] was built with.
     */
    fun rectify(
        bitmap: Bitmap,
        homography: Mat,
        outputFile: File,
        pixelsPerMm: Double,
    ): RectificationResult {
        val src = Mat()
        val warped = Mat()
        try {
            Utils.bitmapToMat(bitmap, src)

            // Output canvas: large enough to hold a table well beyond the card on
            // every side. Sized relative to the source image's own extent mapped
            // through the same scale, capped to avoid pathological allocations.
            val outputWidth = (bitmap.width * pixelsPerMm * 1.2).roundToInt().coerceIn(500, 8000)
            val outputHeight = (bitmap.height * pixelsPerMm * 1.2).roundToInt().coerceIn(500, 8000)

            Imgproc.warpPerspective(src, warped, homography, Size(outputWidth.toDouble(), outputHeight.toDouble()))

            val outBitmap = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, outBitmap)

            FileOutputStream(outputFile).use { stream ->
                outBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }

            return RectificationResult(
                rectifiedImagePath = outputFile.absolutePath,
                pixelsPerMm = pixelsPerMm,
            )
        } finally {
            src.release(); warped.release()
        }
    }

    /** Convenience: computes the homography from [cardCorners] and rectifies in one call. */
    fun rectifyFromCard(
        bitmap: Bitmap,
        cardCorners: List<PointF2>,
        outputFile: File,
        pixelsPerMm: Double = 4.0,
    ): Pair<RectificationResult, Mat> {
        val homography = computeHomography(cardCorners, pixelsPerMm)
        val result = rectify(bitmap, homography, outputFile, pixelsPerMm)
        return result to homography
    }
}
