package tech.valerochkagym

import tech.valerochkagym.service.ai.AiProviderInput
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** Upgrade frozen plan fixtures with canned reason codes; this is not an LLM quality eval. */
internal fun plannerFixture(input: AiProviderInput, output: JsonNode): JsonNode {
  if (
    input.schemaName != "calendar_draft" ||
      output["result"] !is ObjectNode ||
      output["result"].has("rationale")
  )
    return output
  val json = JsonMapper.builder().build()
  val context = json.readTree(input.context.substringBefore("\nCORRECTION:"))
  val result = output["result"] as ObjectNode
  val exercises = result["exercises"]?.toList().orEmpty()
  val selected = exercises.map { it["exerciseId"]?.asString() }.toSet()
  val latest = context["history"]["recentWorkouts"].firstOrNull()
  val repeats = latest?.get("observations")?.any { it["exerciseId"].asString() in selected } == true
  val seconds =
    exercises.sumOf { e ->
      val sets = e["plannedSets"]?.toList().orEmpty()
      sets.sumOf { it["durationSec"]?.takeUnless { it.isNull }?.asLong() ?: 45L } +
        (sets.size - 1).coerceAtLeast(0) *
          (e["restSeconds"]?.takeUnless { it.isNull }?.asLong() ?: 90L)
    } + (exercises.size - 1).coerceAtLeast(0) * 90L
  result.set(
    "rationale",
    json.valueToTree(
      mapOf(
        "selection" to "GOAL_BALANCE",
        "repeat" to if (repeats) "CONTINUITY" else "NONE",
        "shortfall" to
          if (seconds < context["intent"]["durationSpec"]["minimumSeconds"].asLong()) "VOLUME_LIMIT"
          else "NONE",
      )
    ),
  )
  return output
}
