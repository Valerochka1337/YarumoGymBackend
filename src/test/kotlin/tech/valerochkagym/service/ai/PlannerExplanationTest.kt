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

  private val plan =
    json.readTree(
      """{"result":{"name":"Plan","exercises":[{"exerciseId":"$id","restSeconds":90,"plannedSets":[{"reps":10,"durationSec":null}]}]}}"""
    )

  @Test
  fun `empty history produces factual focus duration and no invented intent`() {
    val result = PlannerExplanationFactory.create(draft, request, captured, candidates, 1)
    assertEquals(listOf("QUADS"), result.focusMuscles)
    assertTrue(result.repeatedExerciseIds.isEmpty())
    assertNull(result.lastFinishedAtMillis)
    assertEquals(450L, result.estimatedSeconds)
    assertEquals(2880L, result.minimumSeconds)
    assertEquals("UNSPECIFIED", result.selectionReason)
    assertEquals("NONE", result.repeatReason)
    assertEquals("UNSPECIFIED", result.shortfallReason)
  }

  @Test
  fun `meeting minimum produces no shortfall without a model reason`() {
    val result =
      PlannerExplanationFactory.create(
        draft.copy(exercises = listOf(draft.exercises.single().copy(restSeconds = 40))),
        request.copy(availableDurationMinutes = 10),
        captured,
        candidates,
        1,
      )
    assertEquals(300L, result.estimatedSeconds)
    assertEquals(300L, result.minimumSeconds)
    assertEquals("NONE", result.shortfallReason)
    assertEquals("UNSPECIFIED", result.selectionReason)
  }

  @Test
  fun `provider schema requests only the plan and accepts an answer without rationale`() {
    val schema = json.readTree(javaClass.getResourceAsStream("/ai/calendar-planner-output-v3.json"))
    val result = schema["${'$'}defs"]["ProviderOutput"]["properties"]["result"]
    assertEquals(
      setOf("name", "exercises"),
      result["required"].toList().map { it.asString() }.toSet(),
    )
    assertFalse(result["properties"].has("rationale"))
    assertFalse(CalendarPlannerContext.instruction.contains("rationale", ignoreCase = true))
    assertEquals(plan, AiDraftValidator(json).validatePlanner(plan))
  }

  @Test
  fun `obsolete explanation metadata never rejects or changes a valid plan`() {
    val validator = AiDraftValidator(json)
    listOf(
        "null",
        "42",
        "[]",
        "\"arbitrary prose\"",
        "{}",
        """{"selection":"PRIORITY","repeat":"CONTINUITY","shortfall":"NONE"}""",
        """{"selection":"invented","repeat":"LIMITED_OPTIONS","shortfall":"CONSTRAINTS","references":["invented"]}""",
      )
      .forEach { rationale ->
        val raw = json.readTree(plan.toString())
        (raw["result"] as tools.jackson.databind.node.ObjectNode).set(
          "rationale",
          json.readTree(rationale),
        )
        assertEquals(plan, validator.validatePlanner(raw))
        assertTrue(raw["result"].has("rationale"))
      }
  }

  @Test
  fun `ignoring obsolete rationale preserves strict validation of the plan`() {
    val validator = AiDraftValidator(json)
    listOf(
        plan.toString().replace("\"name\":\"Plan\"", "\"name\":\"Plan\",\"unexpected\":true"),
        plan.toString().replace("\"reps\":10", "\"reps\":10,\"weightKg\":50"),
        plan.toString().replace(id, "not-a-uuid"),
        plan.toString().replace("\"restSeconds\":90", "\"restSeconds\":901"),
        plan.toString().replace("\"reps\":10", "\"reps\":0"),
        plan.toString().replace("\"exercises\":[", "\"missingExercises\":["),
      )
      .forEach { invalid ->
        val raw = json.readTree(invalid)
        (raw["result"] as tools.jackson.databind.node.ObjectNode).put("rationale", "ignored")
        assertThrows<ApiException> { validator.validatePlanner(raw) }
      }
  }
}
