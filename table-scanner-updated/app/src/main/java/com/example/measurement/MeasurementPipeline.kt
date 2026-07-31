package com.example.measurement

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/**
 * Wires the individual pipeline stages (card detection -> lens/perspective
 * rectification -> table measurement -> cross-shot validation) together for
 * a full [CaptureSession]. Each stage's failure is captured as a per-shot
 * warning rather than crashing the whole run, since a session with 2-3 shots
 * should still produce a usable (if wider) range even if one shot's card
 * wasn't detected cleanly.
 */
object MeasurementPipeline {

    data class ShotOutcome(
        val shotLabel: String,
        val rectifiedImagePath: String?,
        val measurement: SingleShotMeasurement?,
        val failureReason: String?,
    )

    data class PipelineResult(
        val shotOutcomes: List<ShotOutcome>,
        val finalResult: TableMeasurementResult?,
        val overallFailureReason: String?,
    )

    /** Runs the full pipeline over every shot currently held in [CaptureSession]. Blocking/CPU-bound. */
    fun run(context: Context, outputDir: File): PipelineResult {
        val intrinsics = Rectifier.readLensIntrinsics(context)
        val shots = CaptureSession.shots
        if (shots.isEmpty()) {
            return PipelineResult(emptyList(), null, "لا توجد صور للقياس.")
        }

        val outcomes = shots.map { shot -> processShot(shot, intrinsics, outputDir) }
        val validMeasurements = outcomes.mapNotNull { it.measurement }

        if (validMeasurements.isEmpty()) {
            return PipelineResult(outcomes, null, "تعذر قياس جميع الصور، يرجى إعادة المحاولة.")
        }

        val finalResult = TableMeasurer.crossValidate(validMeasurements)
        val failedCount = outcomes.size - validMeasurements.size
        val mergedWarnings = if (failedCount > 0) {
            finalResult.warnings + "تعذر استخدام $failedCount من ${outcomes.size} صور في القياس."
        } else {
            finalResult.warnings
        }

        return PipelineResult(
            shotOutcomes = outcomes,
            finalResult = finalResult.copy(warnings = mergedWarnings),
            overallFailureReason = null,
        )
    }

    private fun processShot(
        shot: CaptureSession.Shot,
        intrinsics: Rectifier.LensIntrinsics,
        outputDir: File,
    ): ShotOutcome {
        val original = BitmapFactory.decodeFile(shot.photoPath)
            ?: return ShotOutcome(shot.label, null, null, "تعذر قراءة صورة ${shot.label}.")

        val undistorted = Rectifier.undistort(original, intrinsics)

        val cardResults = CardDetector.detectMultiple(undistorted, maxCards = 2)
        val primaryCard = cardResults.getOrNull(0)
        if (primaryCard == null || !primaryCard.isSuccess) {
            return ShotOutcome(
                shot.label, null, null,
                primaryCard?.failureReason ?: "لم يتم العثور على بطاقة في ${shot.label}.",
            )
        }

        val rectifiedFile = File(outputDir, "${shot.label}_rectified.jpg")
        val (rectA, homography) = Rectifier.rectifyFromCard(undistorted, primaryCard.corners, rectifiedFile)

        // If a second card was also found, project its corners through card A's
        // homography rather than building a second, separately-anchored
        // rectification. A second anchored rectification would trivially match
        // its own card by construction and prove nothing; projecting card B
        // through card A's transform and comparing its *apparent* size to its
        // *known* physical size is what actually checks the homography's
        // accuracy across the span of the table.
        val secondaryCard = cardResults.getOrNull(1)
        val pixelsPerMmB = secondaryCard?.takeIf { it.isSuccess }?.let { card ->
            val projected = Rectifier.transformPoints(homography, card.corners)
            effectiveScaleFromApparentCard(projected)
        }
        homography.release()

        val measurement = TableMeasurer.measureSingleShot(
            shotLabel = shot.label,
            rectifiedBitmap = BitmapFactory.decodeFile(rectA.rectifiedImagePath) ?: undistorted,
            pixelsPerMmCardA = rectA.pixelsPerMm,
            pixelsPerMmCardB = pixelsPerMmB,
        ) ?: return ShotOutcome(shot.label, rectA.rectifiedImagePath, null, "تعذر تحديد حواف الطاولة في ${shot.label}.")

        return ShotOutcome(shot.label, rectA.rectifiedImagePath, measurement, null)
    }

    /**
     * Given a second card's four corners after being projected through the
     * primary card's homography, computes the pixels-per-mm scale implied by
     * that card's *apparent* size vs its *known* real-world size. If the
     * homography (and the flat-table-plane assumption behind it) held
     * perfectly across the whole frame, this equals the primary card's own
     * fixed rectification scale; any difference is the genuine cross-check
     * signal, not a coincidence of construction.
     */
    private fun effectiveScaleFromApparentCard(projectedCorners: List<PointF2>): Double? {
        if (projectedCorners.size != 4) return null
        fun dist(a: PointF2, b: PointF2) = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        val (tl, tr, br, bl) = projectedCorners
        val widthPx = (dist(tl, tr) + dist(bl, br)) / 2.0
        val heightPx = (dist(tl, bl) + dist(tr, br)) / 2.0
        if (widthPx <= 0.0 || heightPx <= 0.0) return null
        val scaleFromWidth = widthPx / ReferenceCard.WIDTH_MM
        val scaleFromHeight = heightPx / ReferenceCard.HEIGHT_MM
        return (scaleFromWidth + scaleFromHeight) / 2.0
    }
}
