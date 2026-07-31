package com.example.measurement

/**
 * Holds the shots taken during one measurement run. A "run" is 2-3 shots of the
 * same table (see design constraint: guide the user through multiple shots and
 * cross-reference results rather than trusting a single frame). This is a simple
 * in-memory singleton rather than a ViewModel because the existing app has no
 * ViewModel/DI scaffolding yet; if that's introduced later this should move into
 * a scoped ViewModel instead.
 */
object CaptureSession {
    const val DEFAULT_SHOT_COUNT = 2
    const val MAX_SHOT_COUNT = 3

    data class Shot(
        val label: String,
        val photoPath: String,
        val filteredTiltG: Float,
    )

    private val _shots = mutableListOf<Shot>()
    val shots: List<Shot> get() = _shots.toList()

    var targetShotCount: Int = DEFAULT_SHOT_COUNT
        private set

    fun reset(targetShotCount: Int = DEFAULT_SHOT_COUNT) {
        _shots.clear()
        this.targetShotCount = targetShotCount.coerceIn(1, MAX_SHOT_COUNT)
    }

    fun addShot(shot: Shot) {
        _shots.add(shot)
    }

    fun replaceShot(index: Int, shot: Shot) {
        if (index in _shots.indices) _shots[index] = shot else _shots.add(shot)
    }

    fun addExtraShot() {
        targetShotCount = (targetShotCount + 1).coerceAtMost(MAX_SHOT_COUNT)
    }

    fun isComplete(): Boolean = _shots.size >= targetShotCount

    /** 1-based index of the shot the user is about to take. */
    fun nextShotNumber(): Int = (_shots.size + 1).coerceAtMost(targetShotCount)
}
