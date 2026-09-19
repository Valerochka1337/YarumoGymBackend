package tech.valerochkagym.service.ai

import java.time.Duration

/** Deterministic strength ranking and compact factual projection. */
internal object StrengthPlannerFacts {
  const val candidateLimit = 24
  private const val overdueDaysCap = 28
  private const val sevenDayCoverageCap = 40
  private val allMuscles =
    sortedSetOf(
      "UPPER_CHEST",
      "LOWER_CHEST",
      "FRONT_DELTS",
      "SIDE_DELTS",
      "REAR_DELTS",
      "ROTATOR_CUFF",
      "SERRATUS_ANTERIOR",
      "BICEPS",
      "TRICEPS",
      "FOREARMS",
      "ABS",
      "OBLIQUES",
      "HIP_FLEXORS",
      "ADDUCTORS",
      "QUADS",
      "TIBIALIS_ANTERIOR",
      "CALVES",
      "HAMSTRINGS",
      "GLUTES",
      "HIP_ABDUCTORS",
      "LOWER_BACK",
      "LATS",
      "UPPER_BACK",
      "TRAPS",
      "NECK",
    )

  /**
   * Canonical legacy seed UUIDs, derived from Android's built-in exercise identity contract. This
   * is source data, never a name-matching rule at runtime.
   */
  val compoundSeedIds =
    setOf(
      "99858342-091a-3b09-a60e-2e94877b7c79",
      "02aeafc6-b39e-38f4-ac3f-ea4e7e6b5d5c",
      "e231c9ab-2108-31a7-9f35-92a80eab4add",
      "19a0a043-47d4-333c-99d1-97066ee42456",
      "46381200-6c68-322a-9e52-25b7e547f07e",
      "b9b24431-4e37-3892-94c1-5cb2a6f6fd46",
    )

  data class Candidate(val exerciseId: String, val type: String, val muscles: Map<String, Int>)

  data class Workout(val workoutId: String, val finishedAtMillis: Long)

  data class RankResult(val exerciseId: String, val score: Int, val priority: String?)

  data class Selection(
    val ranked: List<RankResult>,
    val focusExerciseId: String,
    /** A server-local code. It is never provider input or an output-schema field. */
    val repeatReasonCode: String,
  )

  data class LatestTuple(
    val exerciseId: String,
    val weightSource: String,
    val weightKg: Double?,
    val repsSource: String,
    val reps: Double?,
    val lastTrainedAtMillis: Long? = null,
  )

  data class MovementUnit(
    val exerciseId: String,
    val workoutFrequency: Int,
    val completedSetCount: Int,
    val actualVolume: Double,
    val lastTrainedAtMillis: Long? = null,
  )

  data class MuscleAggregate(
    val muscle: String,
    val workoutFrequency: Int,
    val completedSetCount: Int,
    val directWorkingSetCount: Int = 0,
    val indirectWorkingSetCount: Int = 0,
    val lastTrainedAtMillis: Long? = null,
    val exerciseIds: List<String> = emptyList(),
    val mappingCompleteness: String = "COMPLETE",
  )

  data class WeeklyMuscleTrend(
    val startAtMillis: Long,
    val endAtMillisExclusive: Long,
    val muscles: List<MuscleAggregate>,
  )

  data class Effort(val workoutId: String, val effort: String?)

  data class CompactFacts(
    val latest: List<LatestTuple>,
    val last7Days: List<MovementUnit>,
    val last28Days: List<MovementUnit>,
    val musclesLast7Days: List<MuscleAggregate>,
    val musclesLast28Days: List<MuscleAggregate>,
    val efforts: List<Effort>,
    val weekly: List<WeeklyMuscleTrend> = emptyList(),
  )

  fun select(
    candidates: List<Candidate>,
    facts: List<CalendarFact>,
    workouts: List<Workout>,
    priorities: Map<String, String>,
    durationMinutes: Int,
    capturedAtMillis: Long,
    compoundSeedIds: Set<String> = this.compoundSeedIds,
  ): Selection {
    val byId = candidates.associateBy { it.exerciseId }
    val lastWorkout =
      workouts
        .asSequence()
        .filter { it.finishedAtMillis <= capturedAtMillis }
        .sortedWith(compareByDescending<Workout> { it.finishedAtMillis }.thenBy { it.workoutId })
        .firstOrNull()
        ?.takeIf { capturedAtMillis - it.finishedAtMillis <= Duration.ofDays(7).toMillis() }
    val lastIds =
      facts
        .filter { it.workoutId == lastWorkout?.workoutId }
        .mapTo(mutableSetOf()) { it.exerciseId }
    val sevenDaysAgo = capturedAtMillis - Duration.ofDays(7).toMillis()
    fun coverage(candidate: Candidate): Int =
      minOf(
        sevenDayCoverageCap,
        facts
          .asSequence()
          .filter { it.factTimeMillis in sevenDaysAgo..capturedAtMillis }
          .sumOf { fact ->
            byId[fact.exerciseId]?.muscles.orEmpty().entries.sumOf { (muscle, contribution) ->
              candidate.muscles[muscle]?.let { target -> contribution * target / 10_000.0 } ?: 0.0
            }
          }
          .toInt(),
      )
    fun rank(candidate: Candidate): RankResult {
      val history =
        facts.filter {
          it.exerciseId == candidate.exerciseId && it.factTimeMillis <= capturedAtMillis
        }
      val overdue =
        history
          .maxOfOrNull { it.factTimeMillis }
          ?.let { seen ->
            minOf(
              overdueDaysCap,
              ((capturedAtMillis - seen) / Duration.ofDays(1).toMillis()).toInt(),
            ) * 2
          } ?: 14
      val priority = priorities[candidate.exerciseId]
      val key =
        when (priority) {
          "HIGH" -> 80
          "NORMAL" -> 40
          else -> 0
        }
      val compound = if (candidate.exerciseId in compoundSeedIds) 20 else 0
      // A recorded high-priority focus lift is continuity, not a reason to erase it from the
      // next agentic pool. Other recently used exercises remain lower-ranked so accessories can
      // vary when suitable alternatives exist.
      val repeat = if (candidate.exerciseId in lastIds && priority != "HIGH") 20 else 0
      return RankResult(
        candidate.exerciseId,
        key + overdue + compound - repeat - coverage(candidate),
        priority,
      )
    }
    val ranked =
      candidates
        .map(::rank)
        .sortedWith(
          compareByDescending<RankResult> { it.score }
            .thenByDescending {
              if (it.priority == "HIGH") 2 else if (it.priority == "NORMAL") 1 else 0
            }
            .thenBy { it.exerciseId }
        )
    val selected = ranked
    require(selected.isNotEmpty()) { "No eligible strength candidates" }
    return Selection(selected.take(candidateLimit), selected.first().exerciseId, "NONE")
  }

  fun compact(
    facts: List<CalendarFact>,
    selectedExerciseIds: Set<String>,
    keyExerciseIds: Set<String>,
    capturedAtMillis: Long,
    musclesByExercise: Map<String, Map<String, Int>>,
    efforts: List<Effort>,
    latestFacts: List<CalendarFact> = facts,
  ): CompactFacts {
    val ids = (selectedExerciseIds + keyExerciseIds).sorted()
    require(ids.size <= 29) { "Strength compact facts permit at most 29 exercise IDs" }
    fun latest(exerciseId: String): LatestTuple {
      val fact =
        latestFacts
          .asSequence()
          .filter {
            it.exerciseId == exerciseId &&
              it.factTimeMillis <= capturedAtMillis &&
              it.setType == "WORK"
          }
          .sortedWith(
            compareByDescending<CalendarFact> {
                it.actualWeightPresent && "reps" !in it.legacyFields
              }
              .thenByDescending { it.workoutFinishedAtMillis }
              .thenBy { it.workoutId }
              .thenBy { it.sectionId }
              .thenBy { it.setIndex }
          )
          .firstOrNull()
      return LatestTuple(
        exerciseId,
        when {
          fact == null -> "UNKNOWN"
          fact.actualWeightPresent -> "ACTUAL"
          else -> "LEGACY"
        },
        fact?.takeIf { it.actualWeightPresent }?.actualWeightKg,
        when {
          fact == null -> "UNKNOWN"
          "reps" in fact.legacyFields -> "LEGACY"
          fact.results["reps"] == null -> "UNKNOWN"
          else -> "ACTUAL"
        },
        fact?.takeIf { "reps" !in it.legacyFields }?.results?.get("reps"),
        fact?.factTimeMillis,
      )
    }
    fun movement(days: Long): List<MovementUnit> =
      facts
        .asSequence()
        .filter {
          it.exerciseId in selectedExerciseIds &&
            it.setType == "WORK" &&
            it.factTimeMillis in
              (capturedAtMillis - Duration.ofDays(days).toMillis())..capturedAtMillis
        }
        .groupBy { it.exerciseId }
        .toSortedMap()
        .map { (exerciseId, rows) ->
          MovementUnit(
            exerciseId,
            rows.mapTo(mutableSetOf()) { it.workoutId }.size,
            rows.size,
            rows.sumOf { row ->
              if (row.actualWeightPresent && "reps" !in row.legacyFields)
                row.actualWeightKg?.times(row.results["reps"] ?: 0.0) ?: 0.0
              else 0.0
            },
            rows.maxOfOrNull { it.factTimeMillis },
          )
        }
    fun muscles(days: Long) =
      muscleCoverage(
        facts,
        musclesByExercise,
        capturedAtMillis - Duration.ofDays(days).toMillis(),
        capturedAtMillis + 1,
      )
    return CompactFacts(
      ids.map(::latest),
      movement(7),
      movement(28),
      muscles(7),
      muscles(28),
      efforts
        .filter {
          it.workoutId in (facts + latestFacts).mapTo(mutableSetOf()) { fact -> fact.workoutId }
        }
        .sortedWith(
          compareByDescending<Effort> { effort ->
              (facts + latestFacts)
                .filter { it.workoutId == effort.workoutId }
                .maxOfOrNull { it.workoutFinishedAtMillis } ?: 0L
            }
            .thenBy { it.workoutId }
        ),
      (0..3).map { week ->
        val endExclusive = capturedAtMillis - Duration.ofDays(week * 7L).toMillis() + 1
        val start = endExclusive - Duration.ofDays(7).toMillis()
        WeeklyMuscleTrend(
          start,
          endExclusive,
          muscleCoverage(facts, musclesByExercise, start, endExclusive),
        )
      },
    )
  }

  /**
   * Mapping contribution is the only available classification signal: >=50 is direct, 1..49 is
   * indirect. It is a catalog-mapping heuristic, never a physiological dose or recovery claim.
   */
  fun muscleCoverage(
    facts: List<CalendarFact>,
    musclesByExercise: Map<String, Map<String, Int>>,
    startInclusive: Long,
    endExclusive: Long,
  ): List<MuscleAggregate> {
    // Completed legacy rows may omit the kind, and future/invalid kinds must not be silently
    // represented as a complete no-load window. Only WORK contributes to this aggregate and
    // WARMUP is intentionally excluded; every other/null kind makes completeness partial.
    val unknownSetTypes =
      facts.any {
        it.factTimeMillis in startInclusive until endExclusive &&
          it.setType !in setOf("WORK", "WARMUP")
      }
    val work =
      facts.filter {
        it.setType == "WORK" && it.factTimeMillis in startInclusive until endExclusive
      }
    val missingMappings =
      work.any { musclesByExercise[it.exerciseId].orEmpty().none { (_, value) -> value > 0 } }
    return allMuscles.map { muscle ->
      val entries =
        work.filter { fact ->
          musclesByExercise[fact.exerciseId]?.get(muscle)?.let { it > 0 } == true
        }
      MuscleAggregate(
        muscle,
        entries.mapTo(mutableSetOf()) { it.workoutId }.size,
        entries.size,
        entries.count { fact ->
          musclesByExercise[fact.exerciseId]?.get(muscle)?.let { it >= 50 } == true
        },
        entries.count { fact ->
          musclesByExercise[fact.exerciseId]?.get(muscle)?.let { it in 1..49 } == true
        },
        entries.maxOfOrNull { it.factTimeMillis },
        entries.map { it.exerciseId }.distinct().sorted(),
        when {
          entries.isEmpty() && missingMappings -> "PARTIAL_MISSING_MAPPINGS"
          unknownSetTypes -> "PARTIAL_UNKNOWN_SET_TYPES"
          entries.isEmpty() -> "NO_WORKING_SETS"
          missingMappings -> "PARTIAL_MISSING_MAPPINGS"
          else -> "COMPLETE"
        },
      )
    }
  }
}
