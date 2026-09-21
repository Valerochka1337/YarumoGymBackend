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
    diagnostics?.event(
      AiDiagnosticSite.AGENT_LOOP,
      AiDiagnosticReason.ROUND_BUDGET,
      actual = maxRounds.toLong(),
    )
    diagnostics?.event(
      AiDiagnosticSite.AGENT_LOOP,
      AiDiagnosticReason.TOOL_CALL_BUDGET,
      actual = maxCalls.toLong(),
    )
    repeat(maxRounds) {
      if (deadlineMillis() <= 0) throw aiError("ai_timeout")
      diagnostics?.recordRound()
      val next =
        diagnostics?.observe(AiDiagnosticStage.PLANNER_TURN) { turn(transcript.toList()) }
          ?: turn(transcript.toList())
      if (deadlineMillis() <= 0) throw aiError("ai_timeout")
      next.final?.let { final ->
        if (next.calls.isNotEmpty()) reject(AiDiagnosticReason.FINAL_WITH_TOOLS)
        return final
      }
      if (next.calls.isEmpty()) reject(AiDiagnosticReason.EMPTY_TURN)
      if (next.calls.map { it.id }.distinct().size != next.calls.size)
        reject(AiDiagnosticReason.DUPLICATE_TOOL_CALL)
      next.calls.forEach { call ->
        val execute = {
          if (++calls > maxCalls)
            reject(AiDiagnosticReason.TOOL_CALL_LIMIT, calls.toLong(), maxCalls.toLong())
          PlannerToolProtocol.validate(call, candidateIds, patternIds) { reason ->
            diagnostics?.event(AiDiagnosticSite.TOOL_PROTOCOL, reason)
          }
          if (deadlineMillis() <= 0) throw aiError("ai_timeout")
          diagnostics?.recordToolCall()
          diagnostics?.event(AiDiagnosticSite.TOOL_PROTOCOL, AiDiagnosticReason.TOOL_STARTED)
          val result =
            diagnostics?.observe(AiDiagnosticStage.PLANNER_TOOL) { tool(call) } ?: tool(call)
          diagnostics?.event(
            AiDiagnosticSite.TOOL_PROTOCOL,
            AiDiagnosticReason.TOOL_COMPLETED,
            actual = result.size.toLong(),
          )
          if (deadlineMillis() <= 0) throw aiError("ai_timeout")
          if (result.size !in 1..16_384)
            reject(AiDiagnosticReason.TOOL_RESULT_SIZE, result.size.toLong(), 16_384)
          if (bytes.toLong() + call.bytes.size + result.size > maxAttemptBytes)
            reject(
              AiDiagnosticReason.TRANSCRIPT_SIZE,
              bytes.toLong() + call.bytes.size + result.size,
              maxAttemptBytes.toLong(),
            )
          bytes += call.bytes.size + result.size
          transcript += PlannerToolExchange(call, result)
        }
        if (diagnostics != null) diagnostics.withTool(call.name, execute) else execute()
      }
    }
    reject(AiDiagnosticReason.ROUND_LIMIT, maxRounds.toLong(), maxRounds.toLong())
  }

  private fun reject(
    reason: AiDiagnosticReason,
    actual: Long? = null,
    maximum: Long? = null,
  ): Nothing {
    diagnostics?.event(AiDiagnosticSite.AGENT_LOOP, reason, actual = actual, maximum = maximum)
    throw aiError("ai_invalid_response")
  }
}
