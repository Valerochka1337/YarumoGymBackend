package tech.valerochkagym.service.ai

/**
 * Frozen, per-attempt planner inputs. Configuration never changes in the middle of an agent run.
 */
internal data class AdaptivePlannerContext(
  val configuration: PlannerConfiguration,
  val collection: PlannerPatternCollection,
  val candidateIds: List<String>,
) {
  val patterns: Map<String, PlannerPattern> = collection.patterns.associateBy { it.id }

  fun pattern(id: String): PlannerPattern = patterns[id] ?: throw aiError("ai_invalid_response")

  fun recommendedPatternId(workouts: List<CalendarWorkout>, facts: List<CalendarFact>): String {
    val completed =
      workouts
        .filter { workout -> facts.any { it.workoutId == workout.id && it.setType == "WORK" } }
        .sortedWith(compareBy<CalendarWorkout> { it.finishedAtMillis }.thenBy { it.id })
    return collection.sequence.getOrNull(completed.size % collection.sequence.size)
      ?: collection.patterns.first().id
  }

  companion object {
    fun create(
      configuration: PlannerConfiguration,
      goal: String?,
      candidateIds: List<String>,
    ): AdaptivePlannerContext {
      val collection =
        configuration.collections.firstOrNull { it.goal == goal }
          ?: configuration.collections.firstOrNull { it.goal == "GENERAL_FITNESS" }
          ?: throw aiError("ai_context_stale")
      return AdaptivePlannerContext(configuration, collection, candidateIds.sorted())
    }
  }
}
