package tech.valerochkagym.service.ai

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Completed-session summaries from the same owner-scoped capture as the plan, without raw results.
 */
internal object PlannerWorkoutHistory {
  const val instruction =
    "Read workoutHistory.recentWorkouts in the initial planning context before choosing the next session emphasis. " +
      "They are the latest three captured finished sessions, newest first, for every training goal. " +
      "Use their exercise identities, current muscle mappings and completed set counts to understand actual session order. " +
      "They overlap completedMuscleCoverage and strengthFacts; never add them as extra load. " +
      "Sessions outside the history window are explicitly marked and are not current-window load. " +
      "get_candidate_details_and_history returns candidate-specific summaries from these same sessions; " +
      "an empty result means no completed sets in this recent capture, not never performed. " +
      "Unknown set kinds and missing muscle mappings are unknown, not zero load. Do not infer recovery or readiness."

  fun summarize(captured: CalendarCapturedContext, timeZoneId: String): Map<String, Any> {
    val zone = ZoneId.of(timeZoneId)
    fun local(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toOffsetDateTime().toString()
    val byWorkout = (captured.facts + captured.olderFacts).groupBy { it.workoutId }
    val sources = captured.candidates.associateBy { it.id }
    val detailStart =
      captured.capturedAtMillis - Duration.ofDays(captured.detailDays.toLong()).toMillis()
    val recent =
      captured.workouts
        .sortedWith(compareByDescending<CalendarWorkout> { it.finishedAtMillis }.thenBy { it.id })
        .take(3)
        .map { workout ->
          mapOf(
            "startedLocalTime" to local(workout.startedAtMillis),
            "finishedLocalTime" to local(workout.finishedAtMillis),
            "outsideHistoryWindow" to (workout.finishedAtMillis < captured.windowStartMillis),
            "outsideDetailWindow" to (workout.finishedAtMillis < detailStart),
            "exercises" to
              byWorkout[workout.id]
                .orEmpty()
                .groupBy { it.exerciseId }
                .toSortedMap()
                .map { (id, facts) ->
                  mapOf(
                    "exerciseId" to id,
                    "completedSetCounts" to
                      mapOf(
                        "work" to facts.count { it.setType == "WORK" },
                        "warmup" to facts.count { it.setType == "WARMUP" },
                        "otherOrUnknown" to facts.count { it.setType !in setOf("WORK", "WARMUP") },
                      ),
                    "currentMuscleContributions" to
                      sources[id]
                        ?.payload
                        ?.get("muscles")
                        ?.toList()
                        .orEmpty()
                        .mapNotNull { muscle ->
                          val name = muscle["muscle"]?.asString()
                          val amount = muscle["contribution"]?.asInt()
                          if (name != null && amount != null && amount > 0) name to amount else null
                        }
                        .toMap()
                        .toSortedMap(),
                  )
                },
          )
        }
    return mapOf(
      "version" to "completed-workout-sequence-v1",
      "capturedLocalTime" to local(captured.capturedAtMillis),
      "windowStartLocalTime" to local(captured.windowStartMillis),
      "scope" to "LATEST_THREE_CAPTURED_FINISHED_SESSIONS_NEWEST_FIRST",
      "recentWorkouts" to recent,
    )
  }

  fun candidateDetails(
    json: ObjectMapper,
    history: JsonNode,
    candidates: List<Map<String, Any>>,
    candidateIds: List<String>,
  ): ByteArray {
    val ids = candidateIds.toSet()
    val details =
      candidates.filter { it["exerciseId"] in ids }.sortedBy { it["exerciseId"] as String }
    val matching =
      history["recentWorkouts"].toList().mapNotNull { workout ->
        val exercises =
          workout["exercises"]
            .filter { it["exerciseId"].asString() in ids }
            .map { exercise ->
              mapOf(
                "exerciseId" to exercise["exerciseId"],
                "completedSetCounts" to exercise["completedSetCounts"],
              )
            }
        if (exercises.isEmpty()) null
        else
          mapOf(
            "startedLocalTime" to workout["startedLocalTime"],
            "finishedLocalTime" to workout["finishedLocalTime"],
            "outsideHistoryWindow" to workout["outsideHistoryWindow"],
            "outsideDetailWindow" to workout["outsideDetailWindow"],
            "exercises" to exercises,
          )
      }
    // Keep the existing tool budget. The initial context retains all three summaries if a broad
    // detail request needs to omit older summaries; report the omission instead of claiming no
    // history.
    for (count in matching.size downTo 0) {
      val bytes =
        json.writeValueAsBytes(
          mapOf(
            "candidates" to details,
            "history" to
              mapOf(
                "scope" to history["scope"],
                "status" to
                  when {
                    count < matching.size -> "TRUNCATED_USE_INITIAL_CONTEXT_OR_FEWER_CANDIDATES"
                    matching.isEmpty() -> "NO_COMPLETED_SETS_IN_RECENT_CAPTURE"
                    else -> "AVAILABLE"
                  },
                "omittedWorkoutCount" to matching.size - count,
                "recentWorkouts" to matching.take(count),
              ),
          )
        )
      if (bytes.size <= 16_384) return bytes
    }
    throw aiError("ai_context_too_large")
  }
}
