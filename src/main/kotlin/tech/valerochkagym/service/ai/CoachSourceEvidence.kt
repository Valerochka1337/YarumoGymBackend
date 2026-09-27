package tech.valerochkagym.service.ai

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Frozen, app-authored facts that explain a decision even after the set is edited. */
internal object CoachSourceEvidence {
  fun capture(
    json: ObjectMapper,
    snapshot: JsonNode,
    operations: List<JsonNode> = emptyList(),
  ): JsonNode {
    val sections = snapshot["exercises"]?.toList().orEmpty()
    val changedSets = operations.mapNotNull { it["set_id"]?.asString() }.toSet()
    val changedSections =
      operations
        .flatMap { op ->
          listOfNotNull(
            op["section_id"]?.asString(),
            op["first_section_id"]?.asString(),
            op["second_section_id"]?.asString(),
          )
        }
        .toSet()
    val touched =
      sections.filter { section ->
        section["section_id"].asString() in changedSections ||
          section["sets"]?.any { it["set_id"]?.asString() in changedSets } == true
      }
    val candidates = if (touched.isEmpty()) sections else touched
    val sources =
      candidates
        .mapNotNull { section ->
          section["sets"]
            ?.toList()
            .orEmpty()
            .filter { it["completed"]?.asBoolean() == true }
            .maxByOrNull { it["completed_at"]?.asLong() ?: 0L }
            ?.let { section to it }
        }
        .sortedByDescending { it.second["completed_at"]?.asLong() ?: 0L }
        .take(if (touched.isEmpty()) 1 else 4)
    val baseline =
      snapshot["original_plan"]
        ?.get("exercises")
        ?.toList()
        .orEmpty()
        .flatMap { it["sets"]?.toList().orEmpty() }
        .associateBy { it["set_id"]?.asString() }
    return json.valueToTree(
      sources.map { (section, set) ->
        val initial = baseline[set["set_id"].asString()]
        mapOf(
          "workoutId" to snapshot["workout_id"].asString(),
          "sectionId" to section["section_id"].asString(),
          "sourceSetId" to set["set_id"].asString(),
          "exerciseName" to section["name"]?.asString().orEmpty(),
          "setIndex" to (set["index"]?.asInt() ?: 0),
          "setType" to set["set_type"]?.asString(),
          "plannedWeightKg" to
            (set["target_weight_kg"] ?: initial?.get("weight_kg") ?: set["original_weight_kg"]),
          "plannedReps" to (set["target_reps"] ?: initial?.get("reps") ?: set["original_reps"]),
          "actualWeightKg" to (set["actual_weight_kg"] ?: set["weight_kg"]),
          "actualReps" to (set["actual_reps"] ?: set["reps"]),
          "actualRir" to set["actual_rir"],
          "completedAt" to set["completed_at"],
          "revision" to snapshot["revision"],
        )
      }
    )
  }
}
