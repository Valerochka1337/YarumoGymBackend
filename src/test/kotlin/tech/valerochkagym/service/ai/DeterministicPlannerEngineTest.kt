package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeterministicPlannerEngineTest {
  private val engine = DeterministicPlannerEngine { 0L }

  @Test
  fun `strength scheme uses exact rational scoring and descending tuple tie break`() {
    val slot =
      DeterministicSlot(
        slotId = "push",
        selectionId = "push-1",
        movementClass = "HORIZONTAL_PUSH",
        allowedTypes = setOf(PlannerExerciseType.STRENGTH),
        allowedEquipment = emptySet(),
        minSets = 3,
        maxSets = 3,
        minReps = 8,
        maxReps = 12,
        allowedRestSeconds = listOf(120),
        preferredSetCount = 3,
        preferredRestSeconds = 120,
        targetTotalReps = 30,
        targetIntensityBasisPoints = 6949,
      )
    assertEquals(
      listOf(12, 10, 8),
      engine.schemes(slot, PlannerExerciseType.STRENGTH).first().repetitions,
    )
  }

  @Test
  fun `timed and cardio schemes do not use strength intensity`() {
    val timed =
      DeterministicSlot(
        slotId = "row",
        selectionId = "row-1",
        movementClass = "CARDIO",
        allowedTypes = setOf(PlannerExerciseType.TIMED),
        allowedEquipment = emptySet(),
        minSets = 3,
        maxSets = 3,
        allowedActiveSeconds = listOf(30, 45, 60),
        allowedRestSeconds = listOf(30),
        preferredSetCount = 3,
        preferredRestSeconds = 30,
        targetActiveSeconds = 135,
      )
    val cardio =
      timed.copy(
        minSets = 1,
        maxSets = 1,
        allowedActiveSeconds = listOf(300, 600),
        targetActiveSeconds = 600,
      )
    assertEquals(
      listOf(45, 45, 45),
      engine.schemes(timed, PlannerExerciseType.TIMED).first().activeSeconds,
    )
    assertEquals(
      listOf(600),
      engine.schemes(cardio, PlannerExerciseType.CARDIO).first().activeSeconds,
    )
  }

  @Test
  fun `search preserves exact repeatability and filters forbidden exercises`() {
    val slot = baseSlot()
    val forbidden =
      DeterministicCandidate(
        "a",
        PlannerExerciseType.STRENGTH,
        "HORIZONTAL_PUSH",
        emptySet(),
        "NEVER",
      )
    val allowed = forbidden.copy(exerciseId = "b", accent = "MORE")
    val input = listOf(DeterministicStructure("s", listOf(slot)))
    val first = engine.plan(input, listOf(forbidden, allowed), 3600)
    assertEquals(first, engine.plan(input, listOf(allowed, forbidden), 3600))
    assertEquals(
      "b",
      (first as DeterministicPlannerResult.Ready).plan.selections.single().exercise.exerciseId,
    )
  }

  @Test
  fun `node cap includes scheme expansion and never publishes incomplete candidate`() {
    val slot = baseSlot().copy(minSets = 20, maxSets = 20, minReps = 1, maxReps = 30)
    val result =
      engine.plan(
        listOf(DeterministicStructure("s", listOf(slot))),
        listOf(
          DeterministicCandidate("a", PlannerExerciseType.STRENGTH, "HORIZONTAL_PUSH", emptySet())
        ),
        3600,
        DeterministicPlannerLimits(nodes = 64),
      )
    assertEquals(
      DeterministicPlannerResult.Terminal(PlannerTerminalCode.PLANNER_LIMIT_REACHED),
      result,
    )
  }

  @Test
  fun `deadline returns timeout rather than partial ready and hard maximum has no filler`() {
    var tick = 0L
    val timedEngine = DeterministicPlannerEngine { tick.also { tick += 100 } }
    val structures = listOf(DeterministicStructure("s", listOf(baseSlot())))
    val candidates =
      listOf(
        DeterministicCandidate("a", PlannerExerciseType.STRENGTH, "HORIZONTAL_PUSH", emptySet())
      )
    assertEquals(
      DeterministicPlannerResult.Terminal(PlannerTerminalCode.PLANNER_TIMEOUT),
      timedEngine.plan(
        structures,
        candidates,
        3600,
        DeterministicPlannerLimits(deadlineMillis = 100),
      ),
    )
    assertEquals(
      DeterministicPlannerResult.Terminal(PlannerTerminalCode.NO_FEASIBLE_PLAN),
      engine.plan(structures, candidates, 1, DeterministicPlannerLimits(schemesPerSelection = 32)),
    )
  }

  @Test
  fun `nine required unique selections cannot be declared impossible after eight candidate truncation`() {
    val slot = baseSlot().copy(minSets = 1, maxSets = 1, minReps = 8, maxReps = 8)
    val structures =
      listOf(
        DeterministicStructure(
          "nine",
          (1..9).map { slot.copy(slotId = "slot-$it", selectionId = "selection-$it") },
        )
      )
    val candidates =
      (1..9).map {
        DeterministicCandidate(
          "exercise-$it",
          PlannerExerciseType.STRENGTH,
          "HORIZONTAL_PUSH",
          emptySet(),
        )
      }
    val limited =
      engine.plan(structures, candidates, 3600, DeterministicPlannerLimits(candidatesPerSlot = 8))
    assertEquals(
      DeterministicPlannerResult.Terminal(PlannerTerminalCode.PLANNER_LIMIT_REACHED),
      limited,
    )
    val complete =
      engine.plan(structures, candidates, 3600, DeterministicPlannerLimits(candidatesPerSlot = 9))
        as DeterministicPlannerResult.Ready
    assertEquals(9, complete.plan.selections.map { it.exercise.exerciseId }.distinct().size)
  }

  @Test
  fun `discarded feasible set scheme reports limit and retained winner reports nonoptimality`() {
    val slot = baseSlot().copy(allowedRestSeconds = listOf(0, 120), preferredRestSeconds = 120)
    val structures = listOf(DeterministicStructure("s", listOf(slot)))
    val candidates =
      listOf(
        DeterministicCandidate("a", PlannerExerciseType.STRENGTH, "HORIZONTAL_PUSH", emptySet())
      )
    assertEquals(
      DeterministicPlannerResult.Terminal(PlannerTerminalCode.PLANNER_LIMIT_REACHED),
      engine.plan(structures, candidates, 135, DeterministicPlannerLimits(schemesPerSelection = 1)),
    )
    val enough =
      engine.plan(structures, candidates, 135, DeterministicPlannerLimits(schemesPerSelection = 32))
        as DeterministicPlannerResult.Ready
    assertEquals(0, enough.plan.selections.single().scheme.restSeconds)
    val cappedWinner =
      engine.plan(structures, candidates, 1000, DeterministicPlannerLimits(schemesPerSelection = 1))
        as DeterministicPlannerResult.Ready
    assertEquals(true, cappedWinner.plan.optimalityNotGuaranteed)
  }

  private fun baseSlot() =
    DeterministicSlot(
      "push",
      "push-1",
      "HORIZONTAL_PUSH",
      setOf(PlannerExerciseType.STRENGTH),
      emptySet(),
      3,
      3,
      8,
      12,
      allowedRestSeconds = listOf(120),
      preferredSetCount = 3,
      preferredRestSeconds = 120,
      targetTotalReps = 30,
      targetIntensityBasisPoints = 6949,
    )
}
