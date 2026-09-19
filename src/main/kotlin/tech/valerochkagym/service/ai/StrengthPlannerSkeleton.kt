package tech.valerochkagym.service.ai

/**
 * Compatibility projection for v2 clients. It describes the accepted draft after validation; it is
 * not a server-imposed exercise frame.
 */
internal data class StrengthPlannerSkeleton(
  val focusExerciseId: String,
  val slots: List<Slot>,
  val minDurationSec: Int,
  val maxDurationSec: Int,
) {
  data class Slot(
    val slotId: String,
    val allowedExerciseIds: List<String>,
    val minDurationSec: Int,
    val maxDurationSec: Int,
  )

  companion object {
    fun create(
      finalizedExerciseIds: List<String>,
      candidateIds: List<String>,
      minutes: Int,
    ): StrengthPlannerSkeleton {
      require(finalizedExerciseIds.isNotEmpty()) { "A finalized planner draft needs an exercise" }
      val maximum = minutes * 60
      val minimum = PlannerDuration.minimumSeconds(minutes).toInt()
      val focusExerciseId = finalizedExerciseIds.first()
      val allowedCandidates = candidateIds.distinct().sorted()
      require(focusExerciseId in allowedCandidates) {
        "Finalized focus must be an allowed candidate"
      }
      // Android v2 verifies these legacy slot names. They are a post-validation compatibility
      // projection, not a pattern constraint: the finalized first exercise supplies focus and
      // every eligible candidate remains an allowed accessory alternative.
      val slots = buildList {
        add(Slot("focus", listOf(focusExerciseId), 0, maximum))
        if (finalizedExerciseIds.size > 1) add(Slot("accessory", allowedCandidates, 0, maximum))
      }
      return StrengthPlannerSkeleton(focusExerciseId, slots, minimum, maximum)
    }
  }
}
