package tech.valerochkagym.service.ai

import java.time.Duration

/** Deterministic strength ranking and compact factual projection. */
internal object StrengthPlannerFacts {
  const val candidateLimit = 24
  private const val overdueDaysCap = 28
  private const val sevenDayCoverageCap = 40

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
  )

  data class MovementUnit(
    val exerciseId: String,
    val workoutFrequency: Int,
    val completedSetCount: Int,
    val actualVolume: Double,
  )

  data class MuscleAggregate(
    val muscle: String,
    val workoutFrequency: Int,
    val completedSetCount: Int,
  )

  data class Effort(val workoutId: String, val effort: String?)

  data class CompactFacts(
    val latest: List<LatestTuple>,
    val last7Days: List<MovementUnit>,
    val last28Days: List<MovementUnit>,
    val musclesLast7Days: List<MuscleAggregate>,
    val musclesLast28Days: List<MuscleAggregate>,
    val efforts: List<Effort>,
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
      val repeat = if (candidate.exerciseId in lastIds) 120 else 0
      return RankResult(
        candidate.exerciseId,
        key + overdue + compound - repeat - coverage(candidate),
        priority,
      )
    }
    val ranked =
      candidates
        .filter { it.type == "STRENGTH" }
        .map(::rank)
        .sortedWith(
          compareByDescending<RankResult> { it.score }
            .thenByDescending {
              if (it.priority == "HIGH") 2 else if (it.priority == "NORMAL") 1 else 0
            }
            .thenBy { it.exerciseId }
        )
    val requiredAlternatives = minOf(6, maxOf(3, durationMinutes / 10))
    val nonRecent = ranked.filter { it.exerciseId !in lastIds }
    val selected =
      if (lastIds.isNotEmpty() && nonRecent.size >= requiredAlternatives) nonRecent else ranked
    require(selected.isNotEmpty()) { "No eligible strength candidates" }
    return Selection(
      selected.take(candidateLimit),
      selected.first().exerciseId,
      if (selected === ranked && lastIds.isNotEmpty()) "STRENGTH_RECENT_FALLBACK" else "NONE",
    )
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
              it.setType in setOf(null, "WORK")
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
      )
    }
    fun movement(days: Long): List<MovementUnit> =
      facts
        .asSequence()
        .filter {
          it.exerciseId in selectedExerciseIds &&
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
          )
        }
    fun muscles(days: Long): List<MuscleAggregate> {
      val rows =
        facts.filter {
          it.exerciseId in selectedExerciseIds &&
            it.factTimeMillis in
              (capturedAtMillis - Duration.ofDays(days).toMillis())..capturedAtMillis
        }
      return rows
        .flatMap { fact ->
          musclesByExercise[fact.exerciseId]
            .orEmpty()
            .filterValues { it > 0 }
            .keys
            .map { it to fact }
        }
        .groupBy { it.first }
        .toSortedMap()
        .map { (muscle, entries) ->
          MuscleAggregate(
            muscle,
            entries.mapTo(mutableSetOf()) { it.second.workoutId }.size,
            entries.size,
          )
        }
    }
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
    )
  }
}
