package tech.valerochkagym

import tech.valerochkagym.service.ai.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Local deterministic provider fixture, exercising real server tool validation without AI calls.
 */
internal object TestPlannerTurns {
  private val json = JsonMapper.builder().build()

  fun turn(
    input: AiProviderInput,
    generate: (AiProviderInput) -> JsonNode,
    repairRejected: Boolean = false,
  ): PlannerTurn {
    val transcript = input.plannerTranscript
    val last = transcript.lastOrNull()
    if (last == null) {
      val context = json.readTree(input.context)
      val planning = context["planningContext"] ?: context
      val id =
        planning["plannerPatternCatalog"]["collections"]
          .flatMap { it["patterns"].toList() }
          .map { it["id"].asString() }
          .sorted()
          .first()
      return PlannerTurn(
        calls =
          listOf(
            PlannerToolProtocol.Call(
              "pattern",
              "get_strength_skeleton",
              bytes = json.writeValueAsBytes(mapOf("patternId" to id)),
              patternId = id,
            )
          )
      )
    }
    val validations = transcript.count { it.call.name == "validate_and_finalize_plan" }
    if (
      validations == 0 ||
        (repairRejected && validations == 1 && !json.readTree(last.result)["valid"].asBoolean())
    ) {
      val plan = generate(input)
      return PlannerTurn(
        calls =
          listOf(
            PlannerToolProtocol.Call(
              "validate-$validations",
              "validate_and_finalize_plan",
              bytes = json.writeValueAsBytes(mapOf("plan" to plan)),
              plan = plan,
            )
          )
      )
    }
    return PlannerTurn(final = last.call.plan)
  }
}
