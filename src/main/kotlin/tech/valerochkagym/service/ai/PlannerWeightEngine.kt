package tech.valerochkagym.service.ai

import kotlin.math.floor

/**
 * Independent arithmetic implementation of the Streprogen repetition/intensity relationship.
 * Reference: https://github.com/tommyod/streprogen/blob/master/streprogen/modeling.py No
 * calendar-based growth assumption: the reference strength comes exclusively from actual sets.
 */
object PlannerWeightEngine {
  fun calculate(
    facts: List<CalendarFact>,
    exerciseId: String,
    reps: Int,
    capturedAtMillis: Long,
    stepKg: Double,
  ): Double? {
    if (reps !in 1..30 || !stepKg.isFinite() || stepKg <= 0) return null
    val usable =
      facts.filter {
        val previousReps = it.results["reps"]
        it.exerciseId == exerciseId &&
          it.factTimeMillis <= capturedAtMillis &&
          it.workoutFinishedAtMillis in 1..capturedAtMillis &&
          it.setType in setOf(null, "WORK") &&
          it.actualWeightPresent &&
          "reps" !in it.legacyFields &&
          it.actualWeightKg?.let { w -> w.isFinite() && w > 0 } == true &&
          previousReps != null &&
          previousReps.isFinite() &&
          previousReps in 1.0..30.0 &&
          previousReps % 1.0 == 0.0
      }
    val latest = usable.maxByOrNull { it.workoutFinishedAtMillis } ?: return null
    val estimates =
      usable
        .filter { it.workoutId == latest.workoutId }
        .map { it.actualWeightKg!! / fraction(it.results.getValue("reps")!!) }
        .sorted()
    val middle = estimates.size / 2
    val reference =
      if (estimates.size % 2 == 0) (estimates[middle - 1] + estimates[middle]) / 2
      else estimates[middle]
    val weight = floor((reference * fraction(reps.toDouble()) + 1e-9) / stepKg) * stepKg
    return weight.takeIf { it.isFinite() && it > 0 }
  }

  private fun fraction(repetitions: Double): Double {
    val offset = repetitions - 1.0
    return (97.5 - 3.5 * offset + 0.05 * offset * offset) / 100.0
  }
}
