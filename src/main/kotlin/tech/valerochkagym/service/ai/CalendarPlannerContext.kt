package tech.valerochkagym.service.ai

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import tech.valerochkagym.controller.model.CalendarDraftRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Calendar-only context. No Coach conversation, health expansion or provider-side arithmetic. */
internal object CalendarPlannerContext {
  const val MAX_BYTES = 262144
  val instruction: String by lazy {
    CalendarPlannerContext::class.java.getResource("/ai/calendar-planner-v3.txt")!!.readText()
  }

  fun serialize(
    json: ObjectMapper,
    captured: CalendarCapturedContext,
    request: CalendarDraftRequest,
    selected: List<Map<String, Any>>,
    eligibleCount: Int,
    strengthSelection: StrengthPlannerFacts.Selection? = null,
    strengthFacts: StrengthPlannerFacts.CompactFacts? = null,
  ): String {
    val zone = ZoneId.of(request.timeZoneId)
    fun local(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toOffsetDateTime().toString()
    fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val all = captured.facts + captured.olderFacts
    val byWorkout = all.groupBy { it.workoutId }
    val byExercise = all.groupBy { it.exerciseId }
    val recent =
      captured.workouts
        .sortedWith(compareByDescending<CalendarWorkout> { it.finishedAtMillis }.thenBy { it.id })
        .take(3)
    val recentIds = recent.mapTo(mutableSetOf()) { it.id }
    val sources = captured.candidates.associateBy { it.id }
    fun ref(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    // Values describe the saved set, not an inferred historical exercise type or equipment
    // snapshot.
    fun observations(facts: List<CalendarFact>): List<Map<String, Any?>> =
      facts
        .groupBy { it.exerciseId }
        .toSortedMap()
        .map { (id, rows) ->
          mapOf(
            "exerciseId" to id,
            "observationId" to ref("${rows.first().workoutId}:$id"),
            "completedSets" to rows.size,
            "completedSetsInWindow" to
              rows.count { it.factTimeMillis >= captured.windowStartMillis },
            "localDaysSinceLastObservation" to
              ChronoUnit.DAYS.between(
                day(rows.maxOf { it.factTimeMillis }),
                day(captured.capturedAtMillis),
              ),
            "results" to
              rows
                .groupBy { listOf(it.results, it.legacyFields, it.setType, it.timeSource) }
                .map { (_, same) ->
                  val first = same.first()
                  mapOf(
                    "count" to same.size,
                    "values" to first.results,
                    "legacyFields" to first.legacyFields,
                    "setType" to first.setType,
                    "timeSource" to first.timeSource,
                    "firstLocalTime" to local(same.minOf { it.factTimeMillis }),
                    "lastLocalTime" to local(same.maxOf { it.factTimeMillis }),
                  )
                },
          )
        }
    fun totals(facts: List<CalendarFact>): Map<String, Any> {
      val muscles = sortedMapOf<String, Long>()
      var unmapped = 0
      facts.forEach { fact ->
        val contributions = sources[fact.exerciseId]?.payload?.get("muscles")?.toList().orEmpty()
        if (contributions.isEmpty()) unmapped++
        contributions.forEach { muscle ->
          val amount = muscle["contribution"]?.asInt() ?: 0
          if (amount > 0) {
            val name = muscle["muscle"].asString()
            muscles[name] = (muscles[name] ?: 0) + amount
          }
        }
      }
      return mapOf(
        "completedSets" to facts.size,
        "setsByExercise" to facts.groupingBy { it.exerciseId }.eachCount().toSortedMap(),
        "muscleContributionPoints" to muscles,
        "setsWithoutMuscleMapping" to unmapped,
      )
    }
    val remainder = captured.facts.filterNot { it.workoutId in recentIds }
    val weekly =
      (0..3).map { week ->
        val start = day(captured.windowStartMillis).plusDays(week * 7L)
        val end = start.plusDays(7)
        mapOf(
          "startDateInclusive" to start.toString(),
          "endDateExclusive" to end.toString(),
          "totals" to
            totals(
              remainder.filter { day(it.factTimeMillis) >= start && day(it.factTimeMillis) < end }
            ),
        )
      }
    val candidateIds = selected.mapTo(mutableSetOf()) { it["exerciseId"] as String }
    // Candidate pointers reuse recent detail. Older observations not already detailed appear once.
    val extra = linkedMapOf<String, Map<String, Any?>>()
    val candidates =
      selected.map { row ->
        val id = row["exerciseId"] as String
        val history =
          byExercise[id]
            .orEmpty()
            .groupBy { it.workoutId }
            .entries
            .sortedWith(
              compareByDescending<Map.Entry<String, List<CalendarFact>>> {
                  it.value.maxOf { f -> f.factTimeMillis }
                }
                .thenBy { it.key }
            )
            .take(2)
        val refs =
          history.map { (workout, facts) ->
            val observation = observations(facts).single()
            val reference = observation["observationId"] as String
            if (workout !in recentIds)
              extra[reference] =
                observation + mapOf("lastLocalTime" to local(facts.maxOf { it.factTimeMillis }))
            reference
          }
        (row - setOf("available", "coverage")) +
          mapOf(
            "lastObservationIds" to refs,
            "equipmentRequirementState" to
              (sources[id]?.payload?.get("equipmentRequirementState")?.asString() ?: "UNKNOWN"),
            "historyStatus" to
              if (refs.isEmpty()) "NO_OBSERVATION_IN_CAPTURE"
              else "HISTORICAL_TYPE_AND_EQUIPMENT_NOT_SNAPSHOTTED",
          )
      }
    val latest = recent.firstOrNull()
    val payload =
      mapOf(
        "contextVersion" to "calendar-v3",
        "intent" to
          mapOf(
            "timeZoneId" to request.timeZoneId,
            "plannedLocalDateTime" to local(request.startsAtMillis),
            "availableDurationMinutes" to request.availableDurationMinutes,
            "desiredDurationMinutes" to request.availableDurationMinutes,
            "durationSpec" to
              mapOf(
                "version" to "planner-duration-v1",
                "strengthSetSeconds" to 45,
                "defaultRestSeconds" to 90,
                "transitionSeconds" to 90,
                "minimumSeconds" to
                  PlannerDuration.minimumSeconds(request.availableDurationMinutes),
                "maximumSeconds" to request.availableDurationMinutes * 60,
                "warmup" to "ONLY_LISTED_SETS_COUNTED_ONCE_NO_IMPLICIT_ALLOWANCE",
              ),
            "priorityMuscles" to request.priorityMuscles,
            "currentState" to request.currentState,
            "preferences" to request.preferences,
          ),
        "candidates" to candidates,
        "selection" to
          (mapOf("eligibleCount" to eligibleCount, "selectedCount" to selected.size) +
            if (strengthSelection == null) emptyMap()
            else mapOf("focusExerciseId" to strengthSelection.focusExerciseId)),
        "profile" to captured.profile,
        "mass" to captured.mass,
        "notes" to
          captured.notes.filter {
            it["kind"] != "EXERCISE_HINT" || it["canonicalId"] in candidateIds
          },
        "dataQuality" to captured.dataQuality,
        "history" to
          mapOf(
            "capturedLocalTime" to local(captured.capturedAtMillis),
            "windowStartLocalTime" to local(captured.windowStartMillis),
            "lastFinishedLocalTime" to latest?.let { local(it.finishedAtMillis) },
            "secondsSinceLastFinished" to
              latest?.let { (captured.capturedAtMillis - it.finishedAtMillis) / 1000 },
            "localDaysSinceLastFinished" to
              latest?.let {
                ChronoUnit.DAYS.between(day(it.finishedAtMillis), day(captured.capturedAtMillis))
              },
            "lastLoadSummaryNotAdditionalVolume" to
              latest?.let {
                mapOf(
                  "workoutId" to ref(it.id),
                  "finishedLocalTime" to local(it.finishedAtMillis),
                  "totals" to totals(byWorkout[it.id].orEmpty()),
                )
              },
            "olderLookup" to "LATEST_THREE_FINISHED_PARENTS_BEFORE_WINDOW_ONLY",
            "recentWorkouts" to
              recent.map { workout ->
                val facts = byWorkout[workout.id].orEmpty()
                mapOf(
                  "workoutId" to ref(workout.id),
                  "startedLocalTime" to local(workout.startedAtMillis),
                  "finishedLocalTime" to local(workout.finishedAtMillis),
                  "completedSetsInWindow" to
                    facts.count { it.factTimeMillis >= captured.windowStartMillis },
                  "completedSetsOutsideWindow" to
                    facts.count { it.factTimeMillis < captured.windowStartMillis },
                  "windowMuscleContributionPoints" to
                    totals(facts.filter { it.factTimeMillis >= captured.windowStartMillis })[
                      "muscleContributionPoints"],
                  "observations" to observations(facts),
                )
              },
            "remainingWindowWeeks" to weekly,
            "additionalObservationsNotAdditionalVolume" to extra.values,
          ),
      )
    val providerPayload =
      if (strengthFacts == null) payload
      else
        (payload - setOf("history", "mass")) +
          mapOf(
            "contextVersion" to "calendar-strength-v1",
            "notes" to
              captured.notes
                .filter { it["kind"] != "EXERCISE_HINT" || it["canonicalId"] in candidateIds }
                .map { if (it["kind"] == "EXERCISE_HINT") it else it - "canonicalId" },
            "candidates" to candidates.map { it - setOf("lastObservationIds", "historyStatus") },
            "strengthFacts" to
              mapOf(
                "version" to "strength-compact-v1",
                "lastWorkoutExerciseIds" to
                  all
                    .filter { it.workoutId == latest?.id && it.exerciseId in candidateIds }
                    .map { it.exerciseId }
                    .distinct()
                    .sorted(),
                "latest" to strengthFacts.latest,
                "movementUnits" to
                  mapOf(
                    "last7Days" to strengthFacts.last7Days,
                    "last28Days" to strengthFacts.last28Days,
                  ),
                "muscles" to
                  mapOf(
                    "last7Days" to strengthFacts.musclesLast7Days,
                    "last28Days" to strengthFacts.musclesLast28Days,
                  ),
                "efforts" to strengthFacts.efforts.map { mapOf("effort" to it.effort) },
              ),
          )
    return json.writeValueAsString(providerPayload).also {
      if (it.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw aiError("ai_context_too_large")
    }
  }

  /** Agentic tools have a narrower approved egress boundary than legacy planner output. */
  fun serializeAgentic(
    json: ObjectMapper,
    captured: CalendarCapturedContext,
    request: CalendarDraftRequest,
    selected: List<Map<String, Any>>,
    eligibleCount: Int,
    strengthSelection: StrengthPlannerFacts.Selection? = null,
    strengthFacts: StrengthPlannerFacts.CompactFacts? = null,
  ): String {
    val root =
      json.readTree(
        serialize(
          json,
          captured,
          request,
          selected,
          eligibleCount,
          strengthSelection,
          strengthFacts,
        )
      )
    fun redact(node: JsonNode) {
      if (node.isObject) {
        val objectNode = node as tools.jackson.databind.node.ObjectNode
        listOf("mass", "measurement", "measurements", "health", "inBody", "notes")
          .forEach(objectNode::remove)
        objectNode.properties().forEach { redact(it.value) }
      } else if (node.isArray) node.forEach(::redact)
    }
    redact(root)
    return json.writeValueAsString(root)
  }
}
