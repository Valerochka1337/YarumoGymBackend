package tech.valerochkagym.service.ai

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CoachRunExecutorTest {
  private val json = JsonMapper.builder().build()
  private val owner = UUID.randomUUID()
  private val workout = UUID.randomUUID().toString()
  private val section = UUID.randomUUID().toString()
  private val exercise = UUID.randomUUID().toString()
  private val first = UUID.randomUUID().toString()
  private val next = UUID.randomUUID().toString()
  private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
  private val codec = CoachRunTools(json)

  private fun tree(value: Any?): JsonNode = json.valueToTree(value)

  private fun snapshot(feelings: List<String> = emptyList(), rir: Int? = 2) =
    tree(
      mapOf(
        "workout_id" to workout,
        "revision" to 7,
        "observed_at_millis" to 10_000,
        "future_rest_seconds" to 120,
        "profile" to emptyMap<String, String>(),
        "exercises" to
          listOf(
            mapOf(
              "section_id" to section,
              "exercise_id" to exercise,
              "type" to "STRENGTH",
              "name" to "Присед",
              "sets" to
                listOf(
                  mapOf(
                    "set_id" to first,
                    "index" to 0,
                    "completed" to true,
                    "completed_at" to 1000,
                    "weight_kg" to 100,
                    "reps" to 8,
                    "actual_rir" to rir,
                    "set_type" to "WORK",
                    "reported_feelings" to feelings,
                  ),
                  mapOf(
                    "set_id" to next,
                    "index" to 1,
                    "completed" to false,
                    "weight_kg" to 100,
                    "reps" to 8,
                    "set_type" to "WORK",
                  ),
                ),
            )
          ),
      )
    )

  private fun input(snapshot: JsonNode = snapshot()) =
    tree(
      mapOf(
        "requestId" to UUID.randomUUID().toString(),
        "workoutId" to workout,
        "contextVersion" to "ctx-7",
        "snapshot" to snapshot,
        "message" to "Помоги",
        "automatic" to false,
      )
    )

  private fun assistant(content: String) = tree(mapOf("role" to "assistant", "content" to content))

  private fun call(name: String, args: Any, id: String = "call-1") =
    mapOf(
      "id" to id,
      "type" to "function",
      "function" to mapOf("name" to name, "arguments" to json.writeValueAsString(args)),
    )

  private fun checkpoint(messages: List<JsonNode>, requests: Int = 1, calls: Int = 0) =
    tree(
      mapOf(
        "messages" to messages,
        "requests" to requests,
        "calls" to calls,
        "expiresAtMillis" to 600_000,
        "model" to "test",
      )
    )

  private class Hooks : CoachRunHooks {
    var saved: JsonNode? = null
    val texts = mutableListOf<String>()
    var checks = 0
    var reject = false
    var fresh: Pair<JsonNode, String>? = null

    override fun refreshState() = fresh

    override fun checkpoint(value: JsonNode) {
      saved = value
    }

    override fun progress(stage: String) {}

    override fun text(value: String) {
      texts += value
    }

    override fun checkActive() {
      checks++
      check(!reject) { "lease lost" }
    }
  }

  private inner class Provider : CoachTurnProvider {
    var count = 0
    var received: CoachTurnInput? = null
    var deltas: List<String> = emptyList()

    override fun stream(input: CoachTurnInput, delta: (String) -> Unit): JsonNode {
      deltas.forEach(delta)
      return complete(input)
    }

    override fun catalog() = CoachModelCatalog("AVAILABLE", "test", listOf("test"))

    override fun complete(input: CoachTurnInput): JsonNode {
      count++
      received = input
      return tree(
        mapOf(
          "choices" to
            listOf(
              mapOf(
                "finish_reason" to "stop",
                "message" to assistant("{\"text\":\"Продолжим\",\"quick_replies\":[\"Хорошо\"]}"),
              )
            )
        )
      )
    }
  }

  private fun executor(provider: Provider) =
    CoachRunExecutor(
      provider,
      CoachPromptService(JdbcTemplate()),
      CoachRunContext(JdbcTemplate(), json),
      json,
      clock,
    )

  @Test
  fun `state read refreshes the snapshot and checkpoints its context version`() {
    val provider = Provider()
    val hooks = Hooks()
    val fresh = snapshot().deepCopy() as tools.jackson.databind.node.ObjectNode
    fresh.put("revision", 8)
    hooks.fresh = fresh to "ctx-8"
    val messages =
      listOf(
        tree(
          mapOf(
            "role" to "assistant",
            "tool_calls" to listOf(call("get_workout_state", emptyMap<String, String>())),
          )
        )
      )
    executor(provider).execute(owner, input(), checkpoint(messages), hooks)
    val output =
      json.readTree(
        provider.received!!.messages.first { it["role"].asString() == "tool" }["content"].asString()
      )
    assertEquals(8, output["revision"].asInt())
    assertEquals("ctx-8", hooks.saved!!["contextVersion"].asString())
  }

  @Test
  fun `pending proposal blocks a second change but still permits an answer`() {
    val provider = Provider()
    val hooks = Hooks()
    val fresh = snapshot().deepCopy() as tools.jackson.databind.node.ObjectNode
    fresh.set("pending_proposals", tree(listOf(mapOf("proposalId" to "pending"))))
    hooks.fresh = fresh to "ctx-7"
    val args =
      mapOf(
        "base_revision" to 7,
        "operations" to
          listOf(
            mapOf("action" to "edit_set", "set_id" to next, "values" to mapOf("weight_kg" to 95))
          ),
      )
    val messages =
      listOf(
        tree(
          mapOf("role" to "assistant", "tool_calls" to listOf(call("submit_workout_changes", args)))
        )
      )
    val result = executor(provider).execute(owner, input(), checkpoint(messages), hooks)
    assertEquals("answer", result["kind"].asString())
    assertFalse(result.has("proposal"))
    val output =
      json.readTree(
        provider.received!!.messages.first { it["role"].asString() == "tool" }["content"].asString()
      )
    assertEquals("invalid_tool_arguments", output["error"].asString())
  }

  @Test
  fun `resuming a completed read tool never dispatches it twice`() {
    val provider = Provider()
    val hooks = Hooks()
    val messages =
      listOf(
        tree(mapOf("role" to "user", "content" to "Помоги")),
        tree(
          mapOf(
            "role" to "assistant",
            "tool_calls" to listOf(call("get_workout_state", emptyMap<String, String>())),
          )
        ),
        tree(
          mapOf("role" to "tool", "tool_call_id" to "call-1", "content" to snapshot().toString())
        ),
      )
    val result = executor(provider).execute(owner, input(), checkpoint(messages, calls = 1), hooks)
    assertEquals("answer", result["kind"].asString())
    assertEquals(1, provider.count)
    assertEquals(1, hooks.saved!!["calls"].asInt())
    assertEquals(listOf("Продолжим"), hooks.texts)
    assertEquals(1, provider.received!!.messages.toList().count { it["role"].asString() == "tool" })
  }

  @Test
  fun `automatic tool call cannot invent a recorded result`() {
    val request = input() as tools.jackson.databind.node.ObjectNode
    request.put("automatic", true)
    val saved =
      checkpoint(
        listOf(
          tree(
            mapOf(
              "role" to "assistant",
              "tool_calls" to
                listOf(
                  call(
                    "submit_workout_changes",
                    mapOf(
                      "base_revision" to 7,
                      "operations" to
                        listOf(
                          mapOf(
                            "action" to "record_result",
                            "set_id" to first,
                            "values" to mapOf("reps" to 20),
                          )
                        ),
                    ),
                  )
                ),
            )
          )
        )
      )
    val hooks = Hooks()
    val result = executor(Provider()).execute(owner, request, saved, hooks)
    assertNotEquals("proposal", result["kind"].asString())
    assertTrue(hooks.saved!!["messages"].toString().contains("invalid_tool_arguments"))
  }

  @Test
  fun `resuming pending submit publishes immutable concrete proposal without model call`() {
    val provider = Provider()
    val hooks = Hooks()
    val args =
      mapOf(
        "base_revision" to 7,
        "operations" to
          listOf(mapOf("action" to "edit_set", "set_id" to next, "values" to mapOf("reps" to 7))),
        "reason" to "Снизить нагрузку",
      )
    val saved =
      checkpoint(
        listOf(
          tree(
            mapOf(
              "role" to "assistant",
              "tool_calls" to listOf(call("submit_workout_changes", args)),
            )
          )
        )
      )
    val result = executor(provider).execute(owner, input(), saved, hooks)
    assertEquals("proposal", result["kind"].asString())
    assertEquals(0, provider.count)
    assertEquals(7, result["proposal"]["baseRevision"].asInt())
    assertEquals("ctx-7", result["proposal"]["contextVersion"].asString())
    val replay = executor(provider).execute(owner, input(), hooks.saved, Hooks())
    assertEquals(result, replay)
    assertEquals(0, provider.count)
  }

  @Test
  fun `lease loss forbids saved result publication`() {
    val hooks = Hooks().apply { reject = true }
    val provider = Provider()
    assertThrows(IllegalStateException::class.java) {
      executor(provider).execute(owner, input(), checkpoint(listOf(assistant("Ответ"))), hooks)
    }
    assertNull(hooks.saved)
    assertEquals(0, provider.count)
  }

  @Test
  fun `persisted deadline prevents restart from granting new execution budget`() {
    val saved = tree(mapOf("messages" to listOf(assistant("Ответ")), "expiresAtMillis" to 9_999))
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      executor(Provider()).execute(owner, input(), saved, Hooks())
    }
  }

  @Test
  fun `request budget survives worker restart`() {
    val provider = Provider()
    val saved = checkpoint(listOf(tree(mapOf("role" to "user", "content" to "Ещё"))), requests = 8)
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      executor(provider).execute(owner, input(), saved, Hooks())
    }
    assertEquals(0, provider.count)
  }

  @Test
  fun `automatic no change does not stream a visible message`() {
    val hooks = Hooks()
    val request =
      (input().deepCopy() as tools.jackson.databind.node.ObjectNode).put("automatic", true)
    val result =
      executor(Provider())
        .execute(
          owner,
          request,
          checkpoint(
            listOf(
              assistant(
                "{\"decision\":\"no_change\",\"text\":\"План сохранён\",\"quick_replies\":[]}"
              )
            )
          ),
          hooks,
        )
    assertEquals("no_change", result["kind"].asString())
    assertTrue(hooks.texts.isEmpty())
  }

  @Test
  fun `malformed structured answer never leaks raw JSON`() {
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      executor(Provider()).decodeAnswer("{\"text\":", false)
    }
  }

  @Test
  fun `foreign and completed set deletion are rejected`() {
    for (id in listOf(first, UUID.randomUUID().toString())) assertThrows(
      IllegalArgumentException::class.java
    ) {
      codec.operations(
        owner,
        snapshot(),
        tree(
          mapOf(
            "base_revision" to 7,
            "operations" to listOf(mapOf("action" to "delete_set", "set_id" to id)),
          )
        ),
      ) {
        true
      }
    }
  }

  @Test
  fun `stale revision is rejected before proposing any operation`() {
    assertThrows(IllegalArgumentException::class.java) {
      codec.operations(
        owner,
        snapshot(),
        tree(
          mapOf(
            "base_revision" to 6,
            "operations" to listOf(mapOf("action" to "delete_set", "set_id" to next)),
          )
        ),
      ) {
        true
      }
    }
  }

  @Test
  fun `recorded RIR alone never changes the plan`() {
    assertEquals(
      "NO_CHANGE",
      codec
        .assessment(owner, snapshot(rir = 0), tree(emptyMap<String, String>()))["kind"]
        .asString(),
    )
  }

  @Test
  fun `confirmed harder effort expands into one small concrete adjustment`() {
    val operations =
      codec.operations(
        owner,
        snapshot(listOf("HARDER_THAN_EXPECTED")),
        tree(mapOf("base_revision" to 7, "operations" to listOf(mapOf("action" to "autoregulate")))),
      ) {
        true
      }
    assertEquals(
      listOf("edit_set", "future_rest_duration"),
      operations.map { it["action"].asString() },
    )
    assertEquals(next, operations[0]["set_id"].asString())
    assertEquals(7, operations[0]["values"]["reps"].asInt())
    assertEquals(150, operations[1]["seconds"].asInt())
  }

  @Test
  fun `unknown equipment prevents strength load adjustment`() {
    val result =
      codec.assessment(
        owner,
        snapshot(listOf("HARDER_THAN_EXPECTED")),
        tree(mapOf("goal" to "STRENGTH")),
      )
    assertEquals("CLARIFY", result["kind"].asString())
    assertEquals("EQUIPMENT", result["missing_data"][0].asString())
    assertTrue(result["operations"].isEmpty)
  }

  @Test
  fun `pain wins over deadline pending card and final set`() {
    val state = snapshot(listOf("PAIN")) as tools.jackson.databind.node.ObjectNode
    state.put("available_time_minutes", 0)
    state.put("pending_interaction", true)
    state.put("finished", true)
    (state["exercises"][0]["sets"][1] as tools.jackson.databind.node.ObjectNode).put(
      "completed",
      true,
    )
    val decision = codec.assessment(owner, state, tree(emptyMap<String, String>()))
    assertEquals("reported_safety_issue", decision["reason_code"].asString())
    assertTrue(decision["operations"].isEmpty)
    assertNotNull(codec.initiative(state, null, null))
    assertNull(codec.initiative(state, state, null))
  }

  @Test
  fun `automatic response uses contextual language instead of deterministic template`() {
    val provider = Provider()
    val request =
      input(snapshot(listOf("HARDER_THAN_EXPECTED"))) as tools.jackson.databind.node.ObjectNode
    request.put("automatic", true)
    request.put("deterministicPolicy", true)
    val hooks = Hooks()
    val result =
      executor(provider)
        .execute(
          owner,
          request,
          checkpoint(listOf(tree(mapOf("role" to "user", "content" to "Помоги")))),
          hooks,
        )
    assertEquals("answer", result["kind"].asString())
    assertEquals("Продолжим", result["text"].asString())
    assertEquals(1, provider.count)
    assertEquals(result, executor(provider).execute(owner, request, hooks.saved, Hooks()))
    assertEquals(1, provider.count)
  }

  @Test
  fun `automatic observation cannot record an answer or resolve a concern`() {
    val request = input() as tools.jackson.databind.node.ObjectNode
    request.put("automatic", true)
    var observed = false
    val hooks =
      object : CoachRunHooks {
        override fun checkpoint(value: JsonNode) {}

        override fun progress(stage: String) {}

        override fun text(value: String) {}

        override fun checkActive() {}

        override fun observe(callId: String, args: JsonNode): JsonNode {
          observed = true
          return snapshot()
        }
      }
    val args =
      mapOf("kind" to "resolve_concern", "concern_key" to "workout:PAIN", "evidence" to "Помоги")
    val point =
      checkpoint(
        listOf(
          tree(
            mapOf(
              "role" to "assistant",
              "tool_calls" to listOf(call("record_coach_observation", args)),
            )
          )
        )
      )
    executor(Provider()).execute(owner, request, point, hooks)
    assertFalse(observed)
  }

  @Test
  fun `known interruption never asks the same cause again`() {
    val state = snapshot(listOf("INTERRUPTED"))
    assertEquals(
      "NO_CHANGE",
      codec.assessment(owner, state, tree(emptyMap<String, String>()))["kind"].asString(),
    )
    assertNull(codec.initiative(state, null, null))
  }

  @Test
  fun `observation tool records explicit answer and resumes with updated facts`() {
    val request = input()
    val question = UUID.randomUUID().toString()
    val args =
      mapOf(
        "kind" to "answer",
        "question_id" to question,
        "answer" to "INTERRUPTED",
        "evidence" to "Прервали",
      )
    var writes = 0
    var saved: JsonNode? = null
    val hooks =
      object : CoachRunHooks {
        override fun checkActive() {}

        override fun progress(stage: String) {}

        override fun text(value: String) {}

        override fun checkpoint(value: JsonNode) {
          saved = value
        }

        override fun observe(callId: String, args: JsonNode): JsonNode {
          writes++
          return snapshot(listOf("INTERRUPTED"))
        }
      }
    val point =
      checkpoint(
        listOf(
          tree(
            mapOf(
              "role" to "assistant",
              "tool_calls" to listOf(call("record_coach_observation", args)),
            )
          )
        )
      )
    val result = executor(Provider()).execute(owner, request, point, hooks)
    assertEquals(1, writes)
    assertEquals(
      "INTERRUPTED",
      saved!!["snapshot"]["exercises"][0]["sets"][0]["reported_feelings"][0].asString(),
    )
    assertEquals(result, executor(Provider()).execute(owner, request, saved, hooks))
    assertEquals(1, writes)
  }

  @Test
  fun `unchanged snapshot never launches another initiative`() {
    val state = snapshot(listOf("HARDER_THAN_EXPECTED"))
    assertNull(codec.initiative(state, state, null))
  }

  @Test
  fun `keep exercise rejection suppresses another initiative`() {
    val memory =
      tree(
        listOf(
          mapOf(
            "status" to "REJECTED",
            "reason" to "KEEP_EXERCISE",
            "sectionIds" to listOf(section),
          )
        )
      )
    assertNull(codec.initiative(snapshot(listOf("HARDER_THAN_EXPECTED")), null, memory))
  }

  @Test
  fun `streamed draft contains only cumulative visible text`() {
    val provider =
      Provider().apply {
        deltas =
          listOf(
            "{\"quick_replies\":[\"SECRET\"],\"text\":\"" + "А".repeat(70),
            "Б".repeat(70) + "\"}",
          )
      }
    val hooks = Hooks()
    executor(provider)
      .execute(
        owner,
        input(),
        checkpoint(listOf(tree(mapOf("role" to "user", "content" to "Помоги")))),
        hooks,
      )
    assertEquals(listOf("А".repeat(70), "А".repeat(70) + "Б".repeat(70), "Продолжим"), hooks.texts)
    assertTrue(hooks.texts.none { "SECRET" in it || "{" in it })
  }

  @Test
  fun `autoregulation with no operations never publishes a visible draft`() {
    val provider = Provider()
    val hooks = Hooks()
    val args =
      mapOf("base_revision" to 7, "operations" to listOf(mapOf("action" to "autoregulate")))
    val saved =
      checkpoint(
        listOf(
          tree(
            mapOf(
              "role" to "assistant",
              "tool_calls" to listOf(call("submit_workout_changes", args)),
            )
          )
        )
      )
    val result = executor(provider).execute(owner, input(), saved, hooks)
    assertEquals("no_change", result["kind"].asString())
    assertTrue(hooks.texts.isEmpty())
  }
}
