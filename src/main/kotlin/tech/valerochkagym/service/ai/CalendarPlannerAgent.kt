package tech.valerochkagym.service.ai

/** Pure bounded loop used by the provider adapter; results are rejected before exceeding budget. */
internal class CalendarPlannerAgent(
  private val turn: (List<PlannerToolExchange>) -> PlannerTurn,
  private val tool: (PlannerToolProtocol.Call) -> ByteArray,
  private val maxAttemptBytes: Int = PlannerToolProtocol.maxAttemptBytes,
  private val maxRounds: Int = PlannerToolProtocol.defaultMaxRounds,
  private val maxCalls: Int = PlannerToolProtocol.defaultMaxCalls,
  private val diagnostics: AiDiagnostics? = null,
) {
  fun run(
    candidateIds: Set<String>,
    patternIds: Set<String> = emptySet(),
    deadlineMillis: () -> Long,
  ): tools.jackson.databind.JsonNode {
    val transcript = mutableListOf<PlannerToolExchange>()
    var calls = 0
    var bytes = 0
    require(maxRounds in 1..20 && maxCalls in 1..40)
    repeat(maxRounds) {
      if (deadlineMillis() <= 0) throw aiError("ai_timeout")
      diagnostics?.recordRound()
      val next =
        diagnostics?.observe(AiDiagnosticStage.PLANNER_TURN) { turn(transcript.toList()) }
          ?: turn(transcript.toList())
      if (deadlineMillis() <= 0) throw aiError("ai_timeout")
      next.final?.let { final ->
        if (next.calls.isNotEmpty()) throw aiError("ai_invalid_response")
        return final
      }
      if (next.calls.isEmpty()) throw aiError("ai_invalid_response")
      if (next.calls.map { it.id }.distinct().size != next.calls.size)
        throw aiError("ai_invalid_response")
      next.calls.forEach { call ->
        if (++calls > maxCalls) throw aiError("ai_invalid_response")
        PlannerToolProtocol.validate(call, candidateIds, patternIds)
        if (deadlineMillis() <= 0) throw aiError("ai_timeout")
        diagnostics?.recordToolCall()
        val result =
          diagnostics?.observe(AiDiagnosticStage.PLANNER_TOOL) { tool(call) } ?: tool(call)
        if (deadlineMillis() <= 0) throw aiError("ai_timeout")
        // The aggregate is checked before retaining either half of the exchange.
        if (result.size !in 1..16_384 || bytes + call.bytes.size + result.size > maxAttemptBytes)
          throw aiError("ai_invalid_response")
        bytes += call.bytes.size + result.size
        transcript += PlannerToolExchange(call, result)
      }
    }
    throw aiError("ai_invalid_response")
  }
}
