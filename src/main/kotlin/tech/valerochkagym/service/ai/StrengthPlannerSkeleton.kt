package tech.valerochkagym.service.ai

/** Fixed v2 plan frame. Provider output may fill only these slots. */
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
      focusExerciseId: String,
      candidateIds: List<String>,
      minutes: Int,
    ): StrengthPlannerSkeleton {
      val maximum = minutes * 60
      val minimum = PlannerDuration.minimumSeconds(minutes).toInt()
      val accessories = candidateIds.filterNot { it == focusExerciseId }
      val slots = buildList {
        add(Slot("focus", listOf(focusExerciseId), 180, maximum))
        if (accessories.isNotEmpty()) add(Slot("accessory", accessories, 120, maximum))
      }
      return StrengthPlannerSkeleton(focusExerciseId, slots, minimum, maximum)
    }
  }
}
