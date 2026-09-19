package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CoachModelCheckServiceTest {
  private val json = JsonMapper.builder().build()

  private fun service(vararg replies: String): CoachModelCheckService {
    val remaining = replies.toMutableList()
    return CoachModelCheckService(
      object : CoachTurnProvider {
        override fun catalog() = CoachModelCatalog("AVAILABLE", "fixture", listOf("fixture"))

        override fun complete(input: CoachTurnInput): JsonNode {
          assertEquals("fixture", input.model)
          assertTrue(input.tools.isArray)
          return json.readTree(remaining.removeAt(0))
        }
      },
      json,
    )
  }

  private fun call(name: String, args: String) =
    json.writeValueAsString(
      mapOf(
        "choices" to
          listOf(
            mapOf(
              "message" to
                mapOf(
                  "role" to "assistant",
                  "tool_calls" to
                    listOf(
                      mapOf(
                        "id" to "test",
                        "type" to "function",
                        "function" to mapOf("name" to name, "arguments" to args),
                      )
                    ),
                )
            )
          )
      )
    )

  @Test
  fun `read followed by exact synthetic mutation passes`() {
    assertEquals(
      true,
      service(
          call("get_workout_state", "{}"),
          call(
            "submit_workout_changes",
            """{"base_revision":0,"operations":[{"action":"add_set","section_id":"10000000-0000-4000-8000-000000000002"}]}""",
          ),
        )
        .check(null)["success"],
    )
  }

  @Test
  fun `text only or write without reading cannot pass`() {
    assertEquals(
      false,
      service("""{"choices":[{"message":{"content":"Готово"}}]}""").check(null)["success"],
    )
    assertEquals(
      false,
      service(
          call(
            "submit_workout_changes",
            """{"base_revision":0,"operations":[{"action":"add_set","section_id":"10000000-0000-4000-8000-000000000002"}]}""",
          )
        )
        .check(null)["success"],
    )
  }

  @Test
  fun `unsupported model never invokes provider`() {
    assertEquals(false, service().check("other")["success"])
  }
}
