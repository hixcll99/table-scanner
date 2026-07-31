package com.example.measurement

/**
 * Physical dimensions of the ID-1 reference card (ISO/IEC 7810), e.g. a standard
 * bank/ID card. This is the ground truth used to establish real-world scale.
 */
object ReferenceCard {
    const val WIDTH_MM = 85.60
    const val HEIGHT_MM = 53.98
    const val ASPECT_RATIO = WIDTH_MM / HEIGHT_MM // ~1.586

    /**
     * Tolerance applied when *looking for* card-like contours in a raw (un-rectified,
     * possibly slightly angled) frame. Perspective foreshortening changes the apparent
     * aspect ratio, so this is intentionally loose. It is not the tolerance used to
     * accept a final measurement, only to shortlist candidate contours.
     */
    const val DETECTION_ASPECT_TOLERANCE = 0.35

    /** Table length above which the user should be guided to use two cards. */
    const val TWO_CARD_THRESHOLD_MM = 600.0
}

/** A single 2D point in image pixel space. */
data class PointF2(val x: Float, val y: Float)

/**
 * Result of locating the reference card in a frame and refining its corners to
 * subpixel precision. Ordered corners are TL, TR, BR, BL.
 */
data class CardDetectionResult(
    val corners: List<PointF2>,
    val confidence: Float,
    val failureReason: String? = null,
) {
    val isSuccess: Boolean get() = failureReason == null && corners.size == 4
}

/** Plain-language, user-facing reasons a capture was rejected before it's even processed. */
enum class CaptureRejectionReason(val userMessage: String) {
    TOO_BLURRY("الصورة غير واضحة، ثبّت يدك جيداً وأعد المحاولة."),
    CARD_NOT_FULLY_VISIBLE("تأكد من ظهور البطاقة بالكامل داخل الإطار."),
    TILT_TOO_HIGH("الهاتف مائل، حاول التصوير من الأعلى مباشرة."),
    HARD_SHADOW_ON_CARD("هناك ظل قوي على البطاقة، غيّر زاوية الإضاءة أو موقعك."),
    PHOTO_UNREADABLE("تعذر قراءة الصورة، حاول التقاطها مرة أخرى."),
}

/** Aggregate quality gate outcome for a single frame/photo, before any measurement work. */
data class CaptureQualityResult(
    val passed: Boolean,
    val rejections: List<CaptureRejectionReason> = emptyList(),
    val blurVariance: Double? = null,
    val filteredTiltG: Float? = null,
)

/** Output of undistorting + rectifying a frame to a top-down view with known scale. */
data class RectificationResult(
    val rectifiedImagePath: String,
    val pixelsPerMm: Double,
    val warnings: List<String> = emptyList(),
)

/** A closed numeric range, used instead of a bare point estimate for any reported measurement. */
data class MeasurementRange(val minMm: Double, val maxMm: Double) {
    val midMm: Double get() = (minMm + maxMm) / 2.0
    val spreadMm: Double get() = maxMm - minMm

    fun toCmString(): String {
        val minCm = minMm / 10.0
        val maxCm = maxMm / 10.0
        return "%.1f–%.1f سم".format(minCm, maxCm)
    }
}

/** Per-shot measurement before cross-shot aggregation. */
data class SingleShotMeasurement(
    val shotLabel: String,
    val widthMm: Double,
    val heightMm: Double,
    val pixelsPerMmCardA: Double,
    val pixelsPerMmCardB: Double? = null,
    val cardScaleDisagreementPct: Double? = null,
)

/** Final, cross-validated table measurement shown to the user. */
data class TableMeasurementResult(
    val widthRange: MeasurementRange,
    val heightRange: MeasurementRange,
    val shotCount: Int,
    val warnings: List<String>,
) {
    /** Whether the spread is tight enough that a retake isn't worth prompting for. */
    fun isConfident(maxAcceptableSpreadMm: Double = 15.0): Boolean =
        widthRange.spreadMm <= maxAcceptableSpreadMm && heightRange.spreadMm <= maxAcceptableSpreadMm
}
