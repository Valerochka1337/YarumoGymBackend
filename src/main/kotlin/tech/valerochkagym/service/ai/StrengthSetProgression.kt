package tech.valerochkagym.service.ai

/** Deterministic working-set repetition ladder; weights remain history-derived. */
internal object StrengthSetProgression {
  fun descend(reps: List<Int>, preferredBounds: IntRange? = null): List<Int> {
    if (reps.size < 2) return reps
    val count = reps.size
    val bounds = preferredBounds?.takeIf { it.first in 1..100 && it.last in it.first..100 }
    if (reps.all { it in 1..100 } && reps.zipWithNext().all { (a, b) -> a > b }) return reps
    val span = bounds?.let { it.last - it.first }
    val first = reps.first().coerceIn(1, 100)
    val (start, step) =
      when {
        span != null && span >= 2 * (count - 1) -> bounds.last to 2
        span != null && span >= count - 1 -> bounds.last to 1
        span != null -> return reps
        first >= 1 + 2 * (count - 1) -> first to 2
        else -> maxOf(first, count) to 1
      }
    return List(count) { index -> start - index * step }
  }
}
