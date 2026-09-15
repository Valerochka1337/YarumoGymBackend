package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.*
import tools.jackson.databind.json.JsonMapper

class PlannerExplanationTest {
  private val json = JsonMapper.builder().build()
  private val id = "11111111-1111-4111-8111-111111111111"
  private val request =
    CalendarDraftRequest(
      id,
      1,
      1,
      100000,
      "UTC",
      emptyList(),
      emptyList(),
      emptyList(),
      emptyList(),
      false,
      60,
      null,
      null,
    )
  private val draft =
    ApprovalDraft(
      "Plan",
      emptyList(),
      listOf(PlannedExercise(id, 90, List(4) { PlannedSet(null, 10, null, null, null) })),
      100000,
      "UTC",
    )
  private val captured =
    CalendarCapturedContext(
      AiContextRevision(1, 1),
      emptyList(),
      emptyList(),
      null,
      emptyList(),
      null,
      emptyList(),
      emptyList(),
      emptyList(),
    )
  private val candidates =
    listOf(
      mapOf(
        "exerciseId" to id,
        "muscles" to listOf(mapOf("muscle" to "QUADS", "contribution" to 100)),
      )
    )

  private fun output(
    selection: String = "GOAL_BALANCE",
    repeat: String = "NONE",
    shortfall: String = "VOLUME_LIMIT",
  ) =
    json.readTree(
      """{"result":{"rationale":{"selection":"$selection","repeat":"$repeat","shortfall":"$shortfall"}}}"""
    )

  @Test
  fun `empty history and limited options produce computed focus and explicit shortfall`() {
    val result = PlannerExplanationFactory.create(draft, request, captured, candidates, 1, output())
    assertEquals(listOf("QUADS"), result.focusMuscles)
    assertTrue(result.repeatedExerciseIds.isEmpty())
    assertNull(result.lastFinishedAtMillis)
    assertEquals(450, result.estimatedSeconds.toInt())
    assertEquals("VOLUME_LIMIT", result.shortfallReason)
  }

  @Test
  fun `contradictory repeat priority constraint and shortfall claims are rejected`() {
    listOf(
        output(repeat = "CONTINUITY"),
        output(selection = "PRIORITY"),
        output(selection = "CONSTRAINTS"),
        output(shortfall = "CONSTRAINTS"),
        output(shortfall = "NONE"),
        output(selection = "invented"),
      )
      .forEach {
        assertThrows<ApiException> {
          PlannerExplanationFactory.create(draft, request, captured, candidates, 1, it)
        }
      }
  }

  @Test
  fun `provider schema rejects missing rationale arbitrary claims and invented references`() {
    val validator = AiDraftValidator(json)
    val base =
      """{"name":"Plan","exercises":[{"exerciseId":"$id","restSeconds":90,"plannedSets":[{"reps":10,"durationSec":null}]}]"""
    assertThrows<ApiException> { validator.validatePlanner(json.readTree("{\"result\":$base}}")) }
    val valid =
      json.readTree(
        "{\"result\":$base,\"rationale\":{\"selection\":\"GOAL_BALANCE\",\"repeat\":\"NONE\",\"shortfall\":\"VOLUME_LIMIT\"}}}"
      )
    assertEquals(valid, validator.validatePlanner(valid))
    assertThrows<ApiException> {
      validator.validatePlanner(
        json.readTree(valid.toString().replace("GOAL_BALANCE", "Recovered after 48 hours"))
      )
    }
  }
}
