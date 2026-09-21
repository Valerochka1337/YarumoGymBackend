package tech.valerochkagym.service.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.json.JsonMapper

class CalendarPlannerAgentTest {
  private val json = JsonMapper.builder().build()
  private val candidate = UUID(0, 1).toString()

  @Test
  fun `oversized tool response records its size and tool before rejecting the attempt`() {
    val diagnostics = AiDiagnostics()
    val agent =
      CalendarPlannerAgent(
        turn = {
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  "details",
                  "get_candidate_details_and_history",
                  listOf(candidate),
                  "{}".encodeToByteArray(),
                )
              )
          )
        },
        tool = { ByteArray(16385) },
        diagnostics = diagnostics,
      )
    assertThrows<ApiException> {
      diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {
        agent.run(setOf(candidate)) { 45000 }
      }
    }
    val run = diagnostics.snapshot().single()
    assertEquals(AiDiagnosticOutcome.FAILURE, run.outcome)
    val failure = run.events.last()
    assertEquals(AiDiagnosticReason.TOOL_RESULT_SIZE, failure.reason)
    assertEquals(16385L, failure.actual)
    assertEquals(16384L, failure.maximum)
    assertEquals(AiDiagnosticTool.GET_CANDIDATE_DETAILS_AND_HISTORY, failure.tool)
    assertEquals(AiDiagnosticOutcome.SUCCESS, run.stages.last().outcome)
  }

  @Test
  fun `invalid tool references record protocol failure before tool execution`() {
    val diagnostics = AiDiagnostics()
    val agent =
      CalendarPlannerAgent(
        turn = {
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  "details",
                  "get_candidate_details_and_history",
                  listOf(UUID(0, 2).toString()),
                  "{}".encodeToByteArray(),
                )
              )
          )
        },
        tool = { error("Must not execute") },
        diagnostics = diagnostics,
      )
    assertThrows<ApiException> {
      diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {
        agent.run(setOf(candidate)) { 45000 }
      }
    }
    val run = diagnostics.snapshot().single()
    assertEquals(0, run.toolCalls)
    assertEquals(AiDiagnosticReason.UNKNOWN_CANDIDATE, run.events.last().reason)
    assertEquals(AiDiagnosticSite.TOOL_PROTOCOL, run.events.last().site)
  }

  @Test
  fun `three tool rounds reject a fourth provider turn`() {
    var calls = 0
    val arguments = "{\"candidateIds\":[\"$candidate\"]}".encodeToByteArray()
    val agent =
      CalendarPlannerAgent(
        turn = {
          val id = "call-${++calls}"
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  id,
                  "get_candidate_details_and_history",
                  listOf(candidate),
                  arguments,
                )
              )
          )
        },
        tool = { ByteArray(16_384) { 1 } },
      )
    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { 45_000 } }
    assertEquals("ai_invalid_response", error.code)
    assertEquals(PlannerToolProtocol.maxRounds, calls)
  }

  @Test
  fun `cumulative exchange budget rejects the next result before it is retained`() {
    var toolCalls = 0
    val arguments = "{\"candidateIds\":[\"$candidate\"]}".encodeToByteArray()
    val agent =
      CalendarPlannerAgent(
        turn = {
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  "call-${toolCalls + 1}",
                  "get_candidate_details_and_history",
                  listOf(candidate),
                  arguments,
                )
              )
          )
        },
        tool = {
          toolCalls++
          ByteArray(40) { 1 }
        },
        maxAttemptBytes = arguments.size + 40 + 1,
      )
    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { 45_000 } }
    assertEquals("ai_invalid_response", error.code)
    assertEquals(2, toolCalls)
  }

  @Test
  fun `unknown and out of pool tools are rejected without calling the tool`() {
    var invoked = false
    val agent =
      CalendarPlannerAgent(
        turn = {
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  "call-1",
                  "get_candidate_details_and_history",
                  listOf(UUID(0, 2).toString()),
                  "{\"candidateIds\":[\"${UUID(0, 2)}\"]}".encodeToByteArray(),
                )
              )
          )
        },
        tool = {
          invoked = true
          "{}".encodeToByteArray()
        },
      )
    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { 45_000 } }
    assertEquals("ai_invalid_response", error.code)
    assertEquals(false, invoked)
  }

  @Test
  fun `expired deadline prevents a provider turn`() {
    var invoked = false
    val agent =
      CalendarPlannerAgent(
        turn = {
          invoked = true
          PlannerTurn(final = json.readTree("{}"))
        },
        tool = { error("unused") },
      )
    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { 0 } }
    assertEquals("ai_timeout", error.code)
    assertEquals(false, invoked)
  }

  @Test
  fun `deadline crossing during a provider turn prevents its final answer`() {
    var remaining = 1L
    val agent =
      CalendarPlannerAgent(
        turn = {
          remaining = 0
          PlannerTurn(final = json.readTree("{}"))
        },
        tool = { error("unused") },
      )

    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { remaining } }
    assertEquals("ai_timeout", error.code)
  }

  @Test
  fun `deadline crossing during a tool prevents retaining its exchange`() {
    var remaining = 1L
    val agent =
      CalendarPlannerAgent(
        turn = {
          PlannerTurn(
            calls =
              listOf(
                PlannerToolProtocol.Call(
                  "call-1",
                  "get_candidate_details_and_history",
                  listOf(candidate),
                  "{\"candidateIds\":[\"$candidate\"]}".encodeToByteArray(),
                )
              )
          )
        },
        tool = {
          remaining = 0
          "{}".encodeToByteArray()
        },
      )

    val error = assertThrows<ApiException> { agent.run(setOf(candidate)) { remaining } }
    assertEquals("ai_timeout", error.code)
  }

  @Test
  fun `planner diagnostics count rounds tools and typed stages`() {
    val diagnostics = AiDiagnostics()
    val arguments = "{\"candidateIds\":[\"$candidate\"]}".encodeToByteArray()
    val agent =
      CalendarPlannerAgent(
        turn = { transcript ->
          if (transcript.isEmpty())
            PlannerTurn(
              calls =
                listOf(
                  PlannerToolProtocol.Call(
                    "details",
                    "get_candidate_details_and_history",
                    listOf(candidate),
                    arguments,
                  )
                )
            )
          else PlannerTurn(final = json.readTree("{}"))
        },
        tool = { "{}".encodeToByteArray() },
        diagnostics = diagnostics,
      )

    diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {
      agent.run(setOf(candidate)) { 45_000 }
    }

    val run = diagnostics.snapshot().single()
    assertEquals(2, run.rounds)
    assertEquals(1, run.toolCalls)
    assertEquals(
      listOf(
        AiDiagnosticStage.CALENDAR_CREATE,
        AiDiagnosticStage.PLANNER_TURN,
        AiDiagnosticStage.PLANNER_TOOL,
        AiDiagnosticStage.PLANNER_TURN,
      ),
      run.stages.map { it.stage },
    )
  }
}
