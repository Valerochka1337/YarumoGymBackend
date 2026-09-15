package tech.valerochkagym.service.ai

import tech.valerochkagym.controller.model.PlannedExercise

/** planner-duration-v1: listed warmup is a normal set; no implicit warmup allowance. */
internal object PlannerDuration {
  fun seconds(exercises: List<PlannedExercise>): Long =
    exercises.sumOf { exercise ->
      exercise.plannedSets.sumOf { (it.durationSec ?: 45).toLong() } +
        (exercise.plannedSets.size - 1).coerceAtLeast(0).toLong() * (exercise.restSeconds ?: 90)
    } + (exercises.size - 1).coerceAtLeast(0) * 90L

  fun minimumSeconds(minutes: Int): Long = minutes * 60L - maxOf(300L, minutes * 12L)
}
