package tech.valerochkagym.service.ai

/** Fixed server-private tool vocabulary; no tool can mutate routines, proposals or calendars. */
object PlannerToolProtocol {
  const val defaultMaxRounds = 6
  const val defaultMaxCalls = 12
  @Deprecated("Use defaultMaxRounds") const val maxRounds = defaultMaxRounds
  @Deprecated("Use defaultMaxCalls") const val maxCalls = defaultMaxCalls
  const val maxCandidateIds = 24
  const val maxAttemptBytes = 256 * 1024
  val names =
    setOf(
      "get_strength_skeleton",
      "get_candidate_details_and_history",
      "validate_and_finalize_plan",
    )

  data class Call(
    val id: String,
    val name: String,
    val candidateIds: List<String> = emptyList(),
    /** Exact UTF-8 function arguments received from the provider. */
    val bytes: ByteArray,
    val plan: tools.jackson.databind.JsonNode? = null,
    /** The editable collection pattern chosen before a final plan is accepted. */
    val patternId: String? = null,
  )

  fun validate(call: Call, candidates: Set<String>, patternIds: Set<String> = emptySet()) {
    if (
      !call.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
        call.name !in names ||
        call.bytes.size !in 1..16_384 ||
        call.candidateIds.size > maxCandidateIds ||
        call.candidateIds.distinct().size != call.candidateIds.size ||
        call.candidateIds.any { it !in candidates } ||
        (call.name == "get_strength_skeleton" &&
          (call.patternId == null || call.patternId !in patternIds)) ||
        (call.name != "get_strength_skeleton" && call.patternId != null) ||
        (call.name == "validate_and_finalize_plan" && call.plan == null) ||
        (call.name != "validate_and_finalize_plan" && call.plan != null)
    )
      throw aiError("ai_invalid_response")
  }
}
