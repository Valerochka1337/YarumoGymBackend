package tech.valerochkagym.service.ai

internal data class PlannerValidationIssue(
  val reason: AiDiagnosticReason,
  val actual: Long? = null,
  val minimum: Long? = null,
  val maximum: Long? = null,
  val field: String? = null,
) {
  fun response(): Map<String, Any?> =
    mapOf(
      "valid" to false,
      "code" to "ai_invalid_response",
      "details" to
        mapOf(
          "accepted" to false,
          "reason" to reason.name,
          "actual" to actual,
          "minimum" to minimum,
          "maximum" to maximum,
          "field" to field,
        ),
      "nextAction" to
        "Correct the indicated issue using the supplied context and call validate_and_finalize_plan again. Select a pattern first; use only IDs in candidates, not IDs found only in workout history. Respect durationSpec bounds without arbitrary padding.",
    )

  companion object {
    const val instruction =
      "AGENT PROTOCOL: Select a pattern with get_strength_skeleton, then submit the plan through validate_and_finalize_plan. A successful validation terminates planning on the server; do not reproduce the plan in a final message. On valid:false, correct details.reason using details.actual/minimum/maximum/field and retry within the budget. For this agent, durationSpec minimumSeconds and maximumSeconds are hard limits; the shorter-plan fallback of the non-agent planner does not apply. Never pad volume or rest arbitrarily. Candidate tools accept only IDs in candidates; workoutHistory can include ineligible historical exercises."
  }
}
