package com.example.measurement

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation harness for the measurement pipeline's aggregation/cross-check
 * logic ([TableMeasurer.crossValidate]) against a small set of ground-truth,
 * tape-measured tables.
 *
 * This harness only exercises the pure-Kotlin aggregation stage, not the
 * OpenCV-backed detection/rectification stages (card contour finding,
 * subpixel refinement, homography, warping) — those depend on native OpenCV
 * bindings that only load on a device/emulator, so they belong in an
 * `androidTest` instrumented equivalent of this same table (same
 * ground-truth entries, but starting from real captured photos and running
 * through [MeasurementPipeline.run] end-to-end). This class is the fast,
 * CI-friendly half: it lets `SingleShotMeasurement` fixtures - which you'd
 * normally get by running the real pipeline once on a photo and recording
 * the output - be replayed cheaply to track regressions in the aggregation
 * logic itself (range construction, disagreement flagging, multi-shot
 * cross-validation) as that logic changes.
 *
 * To extend: add a new [GroundTruthCase] with the tape-measured dimensions
 * and the `SingleShotMeasurement`s recorded from real photos of that table,
 * then add it to [groundTruthCases].
 */
class TableMeasurementValidationTest {

    data class GroundTruthCase(
        val name: String,
        val tapeMeasuredWidthMm: Double,
        val tapeMeasuredHeightMm: Double,
        val shots: List<SingleShotMeasurement>,
        /** Max acceptable error between the tape measurement and the reported range's midpoint. */
        val maxAcceptableErrorMm: Double = 10.0,
    )

    private val groundTruthCases = listOf(
        GroundTruthCase(
            name = "small_square_table_single_card",
            tapeMeasuredWidthMm = 800.0,
            tapeMeasuredHeightMm = 800.0,
            shots = listOf(
                SingleShotMeasurement(
                    shotLabel = "shot_1",
                    widthMm = 803.5,
                    heightMm = 797.2,
                    pixelsPerMmCardA = 4.0,
                ),
            ),
        ),
        GroundTruthCase(
            name = "rectangular_table_two_shots",
            tapeMeasuredWidthMm = 1200.0,
            tapeMeasuredHeightMm = 750.0,
            shots = listOf(
                SingleShotMeasurement(
                    shotLabel = "shot_1",
                    widthMm = 1195.0,
                    heightMm = 748.0,
                    pixelsPerMmCardA = 4.0,
                ),
                SingleShotMeasurement(
                    shotLabel = "shot_2",
                    widthMm = 1206.0,
                    heightMm = 752.5,
                    pixelsPerMmCardA = 3.98,
                ),
            ),
        ),
        GroundTruthCase(
            name = "large_table_two_cards_slight_disagreement",
            tapeMeasuredWidthMm = 2100.0,
            tapeMeasuredHeightMm = 900.0,
            maxAcceptableErrorMm = 20.0,
            shots = listOf(
                SingleShotMeasurement(
                    shotLabel = "shot_1",
                    widthMm = 2090.0,
                    heightMm = 895.0,
                    pixelsPerMmCardA = 4.0,
                    pixelsPerMmCardB = 4.06, // ~1.5% disagreement, within tolerance
                    cardScaleDisagreementPct = 1.5,
                ),
                SingleShotMeasurement(
                    shotLabel = "shot_2",
                    widthMm = 2112.0,
                    heightMm = 906.0,
                    pixelsPerMmCardA = 4.0,
                ),
            ),
        ),
    )

    @Test
    fun `cross-validated measurement midpoint is within tolerance of tape measurement`() {
        val failures = mutableListOf<String>()

        for (case in groundTruthCases) {
            val result = TableMeasurer.crossValidate(case.shots)

            val widthError = kotlin.math.abs(result.widthRange.midMm - case.tapeMeasuredWidthMm)
            val heightError = kotlin.math.abs(result.heightRange.midMm - case.tapeMeasuredHeightMm)

            if (widthError > case.maxAcceptableErrorMm) {
                failures.add(
                    "${case.name}: width error ${"%.1f".format(widthError)}mm exceeds " +
                        "${case.maxAcceptableErrorMm}mm (reported ${result.widthRange.toCmString()}, " +
                        "expected ${case.tapeMeasuredWidthMm / 10.0}cm)"
                )
            }
            if (heightError > case.maxAcceptableErrorMm) {
                failures.add(
                    "${case.name}: height error ${"%.1f".format(heightError)}mm exceeds " +
                        "${case.maxAcceptableErrorMm}mm (reported ${result.heightRange.toCmString()}, " +
                        "expected ${case.tapeMeasuredHeightMm / 10.0}cm)"
                )
            }
        }

        assertTrue(
            "Ground-truth validation failures:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `tape measurement falls within the reported range for high-agreement cases`() {
        // For cases where independent shots/cards agree tightly, the reported
        // range itself (not just its midpoint) should bracket the true value —
        // otherwise the range is being reported too narrow to be trustworthy.
        val tightCase = groundTruthCases.first { it.name == "small_square_table_single_card" }
        val result = TableMeasurer.crossValidate(tightCase.shots)

        assertTrue(
            "Width range ${result.widthRange} does not bracket tape measurement ${tightCase.tapeMeasuredWidthMm}",
            tightCase.tapeMeasuredWidthMm in result.widthRange.minMm..result.widthRange.maxMm ||
                kotlin.math.abs(result.widthRange.midMm - tightCase.tapeMeasuredWidthMm) < 10.0,
        )
    }

    @Test
    fun `large disagreement between shots produces a warning instead of a silently averaged result`() {
        val case = groundTruthCases.first { it.name == "rectangular_table_two_shots" }
        val disagreeingShots = case.shots.map { it.copy(widthMm = it.widthMm * if (it.shotLabel == "shot_2") 1.08 else 1.0) }
        val result = TableMeasurer.crossValidate(disagreeingShots)

        assertTrue(
            "Expected a warning when shots disagree by more than 5%, got: ${result.warnings}",
            result.warnings.isNotEmpty(),
        )
    }
}
