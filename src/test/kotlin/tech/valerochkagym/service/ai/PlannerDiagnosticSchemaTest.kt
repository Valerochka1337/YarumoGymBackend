package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.json.JsonMapper

class PlannerDiagnosticSchemaTest {
  private val json = JsonMapper.builder().build()

  @Test
  fun `schema diagnostic locates the invalid field without including its value`() {
    val plan =
      json.readTree(
        """{"result":{"name":"secret-name","exercises":[{"exerciseId":"00000000-0000-4000-8000-000000000001","restSeconds":90,"plannedSets":[{"reps":"secret-value","durationSec":null}]}]}}"""
      )
    var field = ""
    val error =
      assertThrows<ApiException> { AiDraftValidator(json).validatePlanner(plan) { field = it } }
    assertEquals("ai_invalid_response", error.code)
    assertEquals("result.exercises[0].plannedSets[0].reps", field)
  }

  @Test
  fun `unknown property names stay out of diagnostic paths`() {
    val plan =
      json.readTree("""{"result":{"name":"name","exercises":[],"secret-key":"secret-value"}}""")
    var field = ""
    assertThrows<ApiException> { AiDraftValidator(json).validatePlanner(plan) { field = it } }
    assertFalse(field.contains("secret"))
  }
}
