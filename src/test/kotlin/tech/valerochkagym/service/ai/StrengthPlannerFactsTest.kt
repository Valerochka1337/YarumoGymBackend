package tech.valerochkagym.service.ai

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrengthPlannerFactsTest {
  private val now = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()

  private fun fact(
    exerciseId: String,
    workoutId: String,
    daysAgo: Long,
    actual: Boolean = true,
    weight: Double? = 50.0,
    reps: Double? = 8.0,
    setType: String? = "WORK",
  ) =
    CalendarFact(
      exerciseId,
      now - daysAgo * 86_400_000L,
      workoutId,
      "$workoutId-section-$exerciseId",
      0,
      actual,
      if (actual) weight else null,
      if (actual) null else weight,
      mapOf("weightKg" to weight, "reps" to reps),
      if (actual) emptyList() else listOf("weightKg", "reps"),
      setType,
    )

  private fun candidate(id: String, type: String = "STRENGTH", muscle: String = "QUADS") =
    StrengthPlannerFacts.Candidate(id, type, mapOf(muscle to 100))

  @Test
  fun `compound seed IDs are pinned to the canonical built in exercise identities`() {
    assertEquals(
      setOf(
        "99858342-091a-3b09-a60e-2e94877b7c79",
        "02aeafc6-b39e-38f4-ac3f-ea4e7e6b5d5c",
        "e231c9ab-2108-31a7-9f35-92a80eab4add",
        "19a0a043-47d4-333c-99d1-97066ee42456",
        "46381200-6c68-322a-9e52-25b7e547f07e",
        "b9b24431-4e37-3892-94c1-5cb2a6f6fd46",
      ),
      StrengthPlannerFacts.compoundSeedIds,
    )
  }

  @Test
  fun `ranking keeps all hard eligible exercise types in the soft pattern pool`() {
    val compound = "00000000-0000-4000-8000-000000000000"
    val high = "10000000-0000-4000-8000-000000000001"
    val normal = "20000000-0000-4000-8000-000000000002"
    val other = "30000000-0000-4000-8000-000000000003"
    val result =
      StrengthPlannerFacts.select(
        listOf(
          candidate(normal),
          candidate(other),
          candidate(compound),
          candidate(high),
          candidate("40000000-0000-4000-8000-000000000004", "CARDIO"),
        ),
        emptyList(),
        emptyList(),
        mapOf(compound to "HIGH", normal to "NORMAL"),
        45,
        now,
        compoundSeedIds = setOf(compound),
      )
    assertEquals(
      listOf(compound, normal, high, other, "40000000-0000-4000-8000-000000000004"),
      result.ranked.map { it.exerciseId },
    )
    assertEquals(listOf(114, 54, 14, 14, 14), result.ranked.map { it.score })
    assertEquals(compound, result.focusExerciseId)
    assertTrue(result.ranked.any { it.exerciseId == "40000000-0000-4000-8000-000000000004" })
  }

  @Test
  fun `recent high priority focus is retained while non focus exercises remain varied`() {
    val repeated = "10000000-0000-4000-8000-000000000001"
    val alternatives = (2..5).map { "10000000-0000-4000-8000-00000000000$it" }
    val candidates = listOf(candidate(repeated)) + alternatives.map(::candidate)
    val recent =
      StrengthPlannerFacts.select(
        candidates,
        listOf(fact(repeated, "recent", 1)),
        listOf(StrengthPlannerFacts.Workout("recent", now - 1 * 86_400_000L)),
        mapOf(repeated to "HIGH"),
        30,
        now,
      )
    assertTrue(recent.ranked.any { it.exerciseId == repeated })
    assertEquals(repeated, recent.focusExerciseId)
    assertEquals("NONE", recent.repeatReasonCode)
    val old =
      StrengthPlannerFacts.select(
        candidates,
        listOf(fact(repeated, "old", 20)),
        listOf(StrengthPlannerFacts.Workout("old", now - 20 * 86_400_000L)),
        mapOf(repeated to "HIGH"),
        30,
        now,
      )
    assertEquals(repeated, old.focusExerciseId)
  }

  @Test
  fun `compact facts keep one labelled tuple and exact seven and twenty eight day movement units`() {
    val selected = "10000000-0000-4000-8000-000000000001"
    val key = "20000000-0000-4000-8000-000000000002"
    val compact =
      StrengthPlannerFacts.compact(
        listOf(
          fact(selected, "w1", 2, actual = true, weight = 60.0, reps = 5.0),
          fact(selected, "w1", 2, actual = true, weight = 55.0, reps = 5.0),
          fact(selected, "w2", 10, actual = true, weight = 50.0, reps = 8.0),
          fact(key, "w3", 1, actual = false, weight = 40.0, reps = 10.0),
        ),
        setOf(selected),
        setOf(key, "30000000-0000-4000-8000-000000000003"),
        now,
        mapOf(selected to mapOf("QUADS" to 100), key to mapOf("QUADS" to 100)),
        listOf(
          StrengthPlannerFacts.Effort("w1", "HARD"),
          StrengthPlannerFacts.Effort("hidden", "EASY"),
        ),
      )
    assertEquals(listOf("ACTUAL", "LEGACY", "UNKNOWN"), compact.latest.map { it.weightSource })
    assertEquals(listOf("ACTUAL", "LEGACY", "UNKNOWN"), compact.latest.map { it.repsSource })
    assertEquals(listOf(1), compact.last7Days.map { it.workoutFrequency })
    assertEquals(listOf(2), compact.last7Days.map { it.completedSetCount })
    assertEquals(575.0, compact.last7Days.single().actualVolume)
    assertEquals(listOf(2), compact.last28Days.map { it.workoutFrequency })
    assertEquals(listOf(3), compact.last28Days.map { it.completedSetCount })
    assertEquals(25, compact.musclesLast7Days.size)
    val quads = compact.musclesLast7Days.single { it.muscle == "QUADS" }
    assertEquals(2, quads.workoutFrequency)
    assertEquals(3, quads.completedSetCount)
    assertEquals(3, quads.directWorkingSetCount)
    assertEquals(0, quads.indirectWorkingSetCount)
    assertEquals(
      "NO_WORKING_SETS",
      compact.musclesLast7Days.single { it.muscle == "ABS" }.mappingCompleteness,
    )
    assertEquals(listOf("HARD"), compact.efforts.map { it.effort })
    assertTrue(compact.latest.none { it.exerciseId == "w1" })
  }

  @Test
  fun `latest actual null wins while legacy values never masquerade as actual volume`() {
    val id = "10000000-0000-4000-8000-000000000001"
    val actual = fact(id, "actual", 10, weight = null, reps = null)
    val legacy = fact(id, "legacy", 1, actual = false)
    val mixed = fact(id, "mixed", 2).copy(legacyFields = listOf("reps"))
    val result =
      StrengthPlannerFacts.compact(
        listOf(actual, legacy, mixed),
        setOf(id),
        emptySet(),
        now,
        emptyMap(),
        emptyList(),
      )
    assertEquals("ACTUAL", result.latest.single().weightSource)
    assertEquals(null, result.latest.single().weightKg)
    assertEquals(null, result.latest.single().reps)
    assertEquals(0.0, result.last7Days.single().actualVolume)
  }

  @Test
  fun `rolling windows include exact boundary and exclude one millisecond earlier`() {
    val id = "10000000-0000-4000-8000-000000000001"
    val atSeven = fact(id, "seven", 7)
    val beforeSeven =
      atSeven.copy(workoutId = "before-seven", factTimeMillis = atSeven.factTimeMillis - 1)
    val atTwentyEight = fact(id, "twenty-eight", 28)
    val beforeTwentyEight =
      atTwentyEight.copy(
        workoutId = "before-twenty-eight",
        factTimeMillis = atTwentyEight.factTimeMillis - 1,
      )
    val result =
      StrengthPlannerFacts.compact(
        listOf(atSeven, beforeSeven, atTwentyEight, beforeTwentyEight),
        setOf(id),
        emptySet(),
        now,
        emptyMap(),
        emptyList(),
      )
    assertEquals(1, result.last7Days.single().completedSetCount)
    assertEquals(3, result.last28Days.single().completedSetCount)
  }

  @Test
  fun `recent high priority exercise remains the focus without a legacy fallback`() {
    val result =
      StrengthPlannerFacts.select(
        listOf(candidate("recent"), candidate("a"), candidate("b")),
        listOf(fact("recent", "workout", 1)),
        listOf(StrengthPlannerFacts.Workout("workout", now - 86_400_000L)),
        mapOf("recent" to "HIGH"),
        30,
        now,
      )
    assertEquals("NONE", result.repeatReasonCode)
    assertTrue(result.ranked.any { it.exerciseId == "recent" })
    assertEquals("recent", result.focusExerciseId)
    assertEquals(3, result.ranked.size)
  }

  @Test
  fun `coverage separates direct indirect zeros missing mappings unknown kinds and warmups`() {
    val direct = "direct"
    val indirect = "indirect"
    val unmapped = "unmapped"
    val facts =
      listOf(
        fact(direct, "direct-workout", 1),
        fact(indirect, "indirect-workout", 2),
        fact(unmapped, "unmapped-workout", 3),
        fact(direct, "warmup", 1, setType = "WARMUP"),
      )
    val withMissing =
      StrengthPlannerFacts.muscleCoverage(
        facts,
        mapOf(direct to mapOf("QUADS" to 100), indirect to mapOf("QUADS" to 25)),
        now - 7 * 86_400_000L,
        now + 1,
      )
    val quads = withMissing.single { it.muscle == "QUADS" }
    assertEquals(2, quads.completedSetCount)
    assertEquals(1, quads.directWorkingSetCount)
    assertEquals(1, quads.indirectWorkingSetCount)
    assertEquals(listOf(direct, indirect), quads.exerciseIds)
    assertEquals("PARTIAL_MISSING_MAPPINGS", quads.mappingCompleteness)
    val zero = withMissing.single { it.muscle == "ABS" }
    assertEquals(0, zero.directWorkingSetCount)
    assertEquals(0, zero.indirectWorkingSetCount)
    assertEquals("PARTIAL_MISSING_MAPPINGS", zero.mappingCompleteness)

    val unknown =
      StrengthPlannerFacts.muscleCoverage(
        listOf(
          fact(direct, "work", 1),
          fact(direct, "unknown", 1, setType = "UNKNOWN"),
          fact(direct, "null", 1, setType = null),
          fact(direct, "warmup", 1, setType = "WARMUP"),
        ),
        mapOf(direct to mapOf("QUADS" to 100)),
        now - 7 * 86_400_000L,
        now + 1,
      )
    assertEquals(1, unknown.single { it.muscle == "QUADS" }.completedSetCount)
    assertEquals(
      "PARTIAL_UNKNOWN_SET_TYPES",
      unknown.single { it.muscle == "QUADS" }.mappingCompleteness,
    )
  }

  @Test
  fun `weekly muscle windows partition an exact seven day boundary`() {
    val id = "boundary"
    val compact =
      StrengthPlannerFacts.compact(
        listOf(fact(id, "current", 0), fact(id, "previous", 7)),
        setOf(id),
        emptySet(),
        now,
        mapOf(id to mapOf("QUADS" to 100)),
        emptyList(),
      )
    val weeklyCounts =
      compact.weekly.map { week -> week.muscles.single { it.muscle == "QUADS" }.completedSetCount }
    assertEquals(listOf(1, 1, 0, 0), weeklyCounts)
    assertEquals(2, weeklyCounts.sum())
    assertTrue(
      compact.weekly.zipWithNext().all { (newer, older) ->
        older.endAtMillisExclusive == newer.startAtMillis
      }
    )
  }
}
