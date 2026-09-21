package tech.valerochkagym

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  classes = [Application::class, CoachRunIntegrationTest.Fakes::class],
  properties =
    [
      "gym.coach-runs.enabled=false",
      "gym.calendar-jobs.enabled=false",
      "gym.coach-behavior.mode=SHADOW",
    ],
)
class CoachRunIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
        "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
      )

    @DynamicPropertySource
    @JvmStatic
    fun properties(r: DynamicPropertyRegistry) {
      r.add("spring.datasource.url", postgres::getJdbcUrl)
      r.add("spring.datasource.username", postgres::getUsername)
      r.add("spring.datasource.password", postgres::getPassword)
      r.add("gym.token-pepper") { "test-pepper-with-at-least-thirty-two-bytes" }
    }
  }

  class Provider : CoachTurnProvider {
    var calls = 0
    var onCall: () -> Unit = {}
    var completion: ((CoachTurnInput) -> JsonNode)? = null
    val inputs = mutableListOf<CoachTurnInput>()

    override fun catalog() = CoachModelCatalog("AVAILABLE", "fixture", listOf("fixture"))

    override fun complete(input: CoachTurnInput): JsonNode {
      calls++
      onCall()
      inputs += input
      completion?.let {
        return it(input)
      }
      return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readTree(
          """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Продолжим"}}]}"""
        )
    }
  }

  @TestConfiguration
  class Fakes {
    @Bean @Primary fun coachProvider() = Provider()
  }

  @Autowired lateinit var dialogue: CoachDialogueService
  @Autowired lateinit var interventions: CoachInterventionService
  @Autowired lateinit var runs: CoachRunService
  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var provider: Provider
  @Autowired lateinit var crypto: tech.valerochkagym.utils.Crypto
  @org.springframework.boot.test.web.server.LocalServerPort var port: Int = 0

  @Test
  fun `playground bootstrap is unavailable without its profile`() {
    val response =
      java.net.http.HttpClient.newHttpClient()
        .send(
          java.net.http.HttpRequest.newBuilder(
              java.net.URI("http://localhost:$port/dev/coach/api/bootstrap")
            )
            .header("X-Coach-Playground", "1")
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
    assertEquals(401, response.statusCode())
    assertFalse(response.body().contains("accessToken"))
  }

  @BeforeEach
  fun reset() {
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users CASCADE"
    )
    provider.calls = 0
    provider.onCall = {}
    provider.completion = null
    provider.inputs.clear()
  }

  private fun owner(): Identity {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, "$id@example.com")
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      UUID.randomUUID().toString().replace("-", "").repeat(2),
    )
    return Identity(id, session, "")
  }

  private fun request(
    id: UUID = UUID.randomUUID(),
    workout: UUID = UUID.randomUUID(),
    message: String = "Привет",
  ) =
    json.writeValueAsBytes(
      mapOf(
        "requestId" to id.toString(),
        "workoutId" to workout.toString(),
        "contextVersion" to "a".repeat(64),
        "snapshot" to
          mapOf(
            "revision" to 1,
            "workout_id" to workout.toString(),
            "exercises" to emptyList<Any>(),
          ),
        "message" to message,
        "history" to emptyList<Any>(),
      )
    )

  @Test
  fun `concern is durable without a run despite disabled initiative and pending interaction`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot =
      json.readTree(request(workout = workout))["snapshot"]
        as tools.jackson.databind.node.ObjectNode
    snapshot.put("finished", true)
    snapshot.put("pending_interaction", true)
    snapshot.set("reported_feelings", json.valueToTree(listOf("PAIN")))
    val body = sessionBody(workout, 1, snapshot = snapshot)
    runs.session(user, workout, body)
    dialogue.runNext()
    runs.session(user, workout, body)
    dialogue.runNext()
    runs.session(user, workout, sessionBody(workout, 2, snapshot = snapshot))
    dialogue.runNext()
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    val events = runs.workoutEvents(user, workout, 0)
    assertEquals(1, events.size)
    assertEquals("concern", events.single()["type"].asString())
    assertFalse(events.single().has("runId"))
    assertEquals(
      2,
      db.queryForObject("SELECT count(*) FROM coach_behavior_events", Int::class.java),
    )
    assertEquals(2L, runs.behaviorState(user, workout)["behavior"]["stateVersion"].asLong())
    assertTrue(runs.workoutEvents(user, workout, 1).isEmpty())
  }

  @Test
  fun `deleting an account removes behavior events without run references`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot =
      json.readTree(request(workout = workout))["snapshot"]
        as tools.jackson.databind.node.ObjectNode
    snapshot.set("reported_feelings", json.valueToTree<JsonNode>(listOf("PAIN")))
    runs.session(user, workout, sessionBody(workout, 1, snapshot = snapshot))
    dialogue.runNext()
    db.update("DELETE FROM users WHERE id=?", user.userId)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM coach_workout_events", Int::class.java))
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_behavior_events", Int::class.java),
    )
  }

  @Test
  fun `explicit concern resolution survives snapshot refresh and pins session mode`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot =
      json.readTree(request(workout = workout))["snapshot"]
        as tools.jackson.databind.node.ObjectNode
    snapshot.set("reported_feelings", json.valueToTree<JsonNode>(listOf("PAIN")))
    runs.session(user, workout, sessionBody(workout, 1, snapshot = snapshot))
    dialogue.runNext()
    val resolution =
      json.readTree(sessionBody(workout, 2, snapshot = snapshot))
        as tools.jackson.databind.node.ObjectNode
    resolution.set("resolvedConcernKeys", json.valueToTree<JsonNode>(listOf("workout:PAIN")))
    val raw = json.writeValueAsBytes(resolution)
    runs.session(user, workout, raw)
    dialogue.runNext()
    runs.session(user, workout, raw)
    dialogue.runNext()
    runs.session(user, workout, sessionBody(workout, 3, snapshot = snapshot))
    dialogue.runNext()
    val view = runs.behaviorState(user, workout)["behavior"]
    assertTrue(view["state"]["openConcerns"].isEmpty)
    assertEquals("SHADOW", view["mode"].asString())
    assertEquals(1, runs.workoutEvents(user, workout, 0).size)
    assertEquals(3L, view["stateVersion"].asLong())
  }

  @Test
  fun `enforced policy supersedes automatic work during a set but keeps manual questions`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot =
      json.readTree(request(workout = workout))["snapshot"]
        as tools.jackson.databind.node.ObjectNode
    snapshot.put("phase", "READY")
    runs.session(user, workout, sessionBody(workout, 1, true, snapshot))
    dialogue.runNext()
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    val id = UUID.randomUUID()
    val input = json.readTree(request(id, workout)) as tools.jackson.databind.node.ObjectNode
    input.put("automatic", true)
    runs.submit(user, json.writeValueAsBytes(input))
    snapshot.put("phase", "IN_SET")
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    assertEquals("SUPERSEDED", runs.status(user, id)["state"].asString())
    val manual = UUID.randomUUID()
    runs.submit(user, request(manual, workout))
    runs.runNext()
    assertEquals("SUCCEEDED", runs.status(user, manual)["state"].asString())
    assertEquals(1, provider.calls)
  }

  @Test
  fun `pain is delivered immediately even when narration is leased and provider is unavailable`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    db.update(
      "INSERT INTO coach_presentations(id,owner_id,workout_id,event_type,payload,context_version,lease_until) VALUES (?,?,?,'intervention','{}','context',now()+interval '10 minutes')",
      UUID.randomUUID(),
      user.userId,
      workout,
    )
    provider.onCall = { error("provider unavailable") }
    snapshot.set("reported_feelings", json.valueToTree<JsonNode>(listOf("PAIN")))
    runs.session(user, workout, sessionBody(workout, 2, false, snapshot))
    val concern = runs.workoutEvents(user, workout, 0).single { it["type"].asString() == "concern" }
    assertTrue(concern["text"].asString().startsWith("Останови движение"))
    assertEquals(0, provider.calls)
    runs.session(user, workout, sessionBody(workout, 3, false, snapshot))
    assertEquals(
      1,
      runs.workoutEvents(user, workout, 0).count { it["type"].asString() == "concern" },
    )
  }

  @Test
  fun `early priorities remain visible after more than twenty dialogue turns`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val priority = "Последнее упражнение обязательно оставь"
    repeat(25) { index ->
      runs.submit(
        user,
        request(workout = workout, message = if (index == 0) priority else "Уточнение $index"),
      )
      runs.runNext()
    }
    assertTrue(
      provider.inputs.last().messages.any {
        it["role"].asString() == "user" && it["content"].asString() == priority
      }
    )
  }

  private fun interventionSnapshot(workout: UUID): JsonNode =
    json.valueToTree(
      mapOf(
        "workout_id" to workout.toString(),
        "revision" to 1,
        "phase" to "RESTING",
        "future_rest_seconds" to 120,
        "exercises" to
          listOf(
            mapOf(
              "section_id" to UUID.randomUUID().toString(),
              "exercise_id" to UUID.randomUUID().toString(),
              "name" to "Присед",
              "type" to "STRENGTH",
              "sets" to
                (0..2).map { index ->
                  mapOf(
                    "set_id" to UUID.randomUUID().toString(),
                    "index" to index,
                    "completed" to (index < 2),
                    "completed_at" to if (index < 2) index + 1 else null,
                    "weight_kg" to 60,
                    "reps" to if (index == 1) 6 else 10,
                    "set_type" to "WORK",
                  )
                },
            )
          ),
      )
    )

  @Test
  fun `question and proposal are narrated without changing validated operations`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout)
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    dialogue.runNext()
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    val question = runs.workoutEvents(user, workout, 0).single()["result"]["question"]
    val questionId = UUID.fromString(question["questionId"].asString())
    val answer =
      json.valueToTree<JsonNode>(
        mapOf(
          "answerId" to UUID.randomUUID(),
          "expectedVersion" to 1,
          "answer" to "HARDER_THAN_EXPECTED",
        )
      )
    val result = interventions.answer(user, workout, questionId, answer)
    assertEquals("ANSWERED", result["status"].asString())
    assertTrue(result["proposalPending"].asBoolean())
    dialogue.runNext()
    assertEquals(result, interventions.answer(user, workout, questionId, answer))
    assertTrue(provider.calls > 0)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
    val presented =
      runs.workoutEvents(user, workout, 0).last { it["result"]?.get("proposal") != null }["result"]
    val proposalId = UUID.fromString(presented["proposal"]["proposalId"].asString())
    val receipt =
      json.valueToTree<JsonNode>(
        mapOf(
          "receiptId" to UUID.randomUUID(),
          "version" to 1,
          "status" to "APPLIED",
          "resultRevision" to 2,
        )
      )
    db.update("UPDATE coach_intervention_proposals SET status='EXPIRED',expires_at=0")
    val ack = interventions.receipt(user, workout, proposalId, receipt)
    assertEquals(ack, interventions.receipt(user, workout, proposalId, receipt))
    assertEquals(
      "APPLIED",
      db.queryForObject("SELECT status FROM coach_intervention_proposals", String::class.java),
    )
    assertEquals(
      2,
      db.queryForObject("SELECT count(*) FROM coach_intervention_receipts", Int::class.java),
    )
    val conflictingReceipt =
      (receipt.deepCopy() as tools.jackson.databind.node.ObjectNode)
        .put("receiptId", UUID.randomUUID().toString())
        .put("status", "REJECTED")
    assertEquals(
      409,
      assertThrows(ApiException::class.java) {
          interventions.receipt(user, workout, proposalId, conflictingReceipt)
        }
        .status,
    )
    val changed =
      (answer.deepCopy() as tools.jackson.databind.node.ObjectNode).put("answer", "PLANNED_EFFORT")
    assertEquals(
      409,
      assertThrows(ApiException::class.java) {
          interventions.answer(user, workout, questionId, changed)
        }
        .status,
    )
  }

  @Test
  fun `time conflict goes through conversational judgement before any proposal`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    snapshot.put("available_time_minutes", 0)
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    dialogue.runNext()
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    runs.session(user, workout, sessionBody(workout, 3, true, snapshot))
    dialogue.runNext()
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM coach_questions", Int::class.java))
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    assertEquals(0, provider.calls)
  }

  @Test
  fun `planned effort answer keeps the plan and never implies consent`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout)
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    dialogue.runNext()
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    val questionId =
      UUID.fromString(
        runs.workoutEvents(user, workout, 0).single()["result"]["question"]["questionId"].asString()
      )
    val answer =
      json.valueToTree<JsonNode>(
        mapOf("answerId" to UUID.randomUUID(), "expectedVersion" to 1, "answer" to "PLANNED_EFFORT")
      )
    assertEquals(
      "no_change",
      interventions.answer(user, workout, questionId, answer)["kind"].asString(),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
    runs.session(user, workout, sessionBody(workout, 3, true, snapshot))
    dialogue.runNext()
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_questions", Int::class.java))
  }

  @Test
  fun `old question cannot modify a later workout revision`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    dialogue.runNext()
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    val question = runs.workoutEvents(user, workout, 0).single()["result"]["question"]
    snapshot.put("revision", 2)
    runs.session(user, workout, sessionBody(workout, 3, true, snapshot))
    dialogue.runNext()
    val answer =
      json.valueToTree<JsonNode>(
        mapOf(
          "answerId" to UUID.randomUUID(),
          "expectedVersion" to 1,
          "answer" to "HARDER_THAN_EXPECTED",
        )
      )
    assertEquals(
      "ANSWERED",
      interventions
        .answer(user, workout, UUID.fromString(question["questionId"].asString()), answer)["status"]
        .asString(),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
  }

  @Test
  fun `late answer remains a fact in subsequent calculation and conversation`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    val question = runs.workoutEvents(user, workout, 0).single()["result"]["question"]
    snapshot.put("revision", 2)
    snapshot.put("phase", "IN_SET")
    runs.session(user, workout, sessionBody(workout, 3, true, snapshot))
    db.update("UPDATE coach_questions SET expires_at=0")
    val answer =
      json.valueToTree<JsonNode>(
        mapOf("answerId" to UUID.randomUUID(), "expectedVersion" to 1, "answer" to "PLANNED_EFFORT")
      )
    val result =
      interventions.answer(
        user,
        workout,
        UUID.fromString(question["questionId"].asString()),
        answer,
      )
    assertEquals("ANSWERED", result["status"].asString())
    assertEquals(
      result,
      interventions.answer(
        user,
        workout,
        UUID.fromString(question["questionId"].asString()),
        answer,
      ),
    )
    val projected = interventions.planningSnapshot(user.userId, workout, snapshot)
    assertEquals(
      "PLANNED_EFFORT",
      projected["exercises"][0]["sets"][1]["reported_feelings"][0].asString(),
    )
    assertEquals(
      "planned_effort",
      CoachRunTools(json)
        .assessment(user.userId, projected, json.createObjectNode())["reason_code"]
        .asString(),
    )
    assertTrue(
      dialogue.history(user.userId, workout).any {
        it["role"] == "user" && it["text"] == "Остановился специально"
      }
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
  }

  @Test
  fun `text reply resolves only its named concern and is idempotent`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    snapshot.set(
      "reported_feelings",
      json.valueToTree<JsonNode>(listOf("PAIN", "TECHNIQUE_BREAKDOWN")),
    )
    runs.session(user, workout, sessionBody(workout, 1, true, snapshot))
    dialogue.runNext()
    val id = UUID.randomUUID()
    val args =
      json.valueToTree<JsonNode>(
        mapOf(
          "kind" to "resolve_concern",
          "concern_key" to "workout:PAIN",
          "evidence" to "Боль прошла",
        )
      )
    val result =
      interventions.observe(
        user.userId,
        workout,
        id,
        args,
        "Боль прошла, но техника всё ещё страдает",
      )
    assertEquals(
      result,
      interventions.observe(
        user.userId,
        workout,
        id,
        args,
        "Боль прошла, но техника всё ещё страдает",
      ),
    )
    assertEquals(
      listOf("workout:TECHNIQUE_BREAKDOWN"),
      result["open_concerns"].toList().map { it.asString() },
    )
    assertThrows(IllegalArgumentException::class.java) {
      interventions.observe(user.userId, workout, UUID.randomUUID(), args, "Продолжаем")
    }
  }

  @Test
  fun `narration never publishes a proposal after the athlete starts a set`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    snapshot.put("available_time_minutes", 0)
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    provider.onCall = {
      snapshot.put("phase", "IN_SET")
      runs.session(user, workout, sessionBody(workout, 3, true, snapshot))
    }
    dialogue.runNext()
    assertTrue(
      runs.workoutEvents(user, workout, 0).none { it["type"]?.asString() == "intervention" }
    )
  }

  @Test
  fun `ordinary chat answers the pending question and model sees updated calculation`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout)
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    dialogue.runNext()
    val questionId =
      runs.workoutEvents(user, workout, 0).single()["result"]["question"]["questionId"].asString()
    provider.completion = { input ->
      val message =
        if (input.messages.any { it["role"]?.asString() == "tool" }) {
          val toolState = json.readTree(input.messages.last()["content"].asString())
          assertEquals("INTERRUPTED", toolState["behavior_facts"][0]["reportedFeeling"].asString())
          mapOf(
            "role" to "assistant",
            "content" to "Понял, прерванный подход не считаю признаком слишком большого веса.",
          )
        } else {
          assertTrue(
            input.messages.any {
              it["role"]?.asString() == "assistant" && it["content"]?.asString() == "Продолжим"
            }
          )
          mapOf(
            "role" to "assistant",
            "tool_calls" to
              listOf(
                mapOf(
                  "id" to "answer-cause",
                  "type" to "function",
                  "function" to
                    mapOf(
                      "name" to "record_coach_observation",
                      "arguments" to
                        json.writeValueAsString(
                          mapOf(
                            "kind" to "answer",
                            "question_id" to questionId,
                            "answer" to "INTERRUPTED",
                            "evidence" to "меня отвлекли",
                          )
                        ),
                    ),
                )
              ),
          )
        }
      json.valueToTree(
        mapOf(
          "choices" to
            listOf(
              mapOf(
                "finish_reason" to if (message.containsKey("tool_calls")) "tool_calls" else "stop",
                "message" to message,
              )
            )
        )
      )
    }
    val requestId = UUID.randomUUID()
    val body =
      json.readTree(request(id = requestId, workout = workout, message = "Да, меня отвлекли"))
        as tools.jackson.databind.node.ObjectNode
    body.set("snapshot", snapshot)
    runs.submit(user, json.writeValueAsBytes(body))
    runs.runNext()
    assertEquals(
      "ANSWERED",
      db.queryForObject("SELECT status FROM coach_questions", String::class.java),
    )
    assertEquals("SUCCEEDED", runs.status(user, requestId)["state"].asString())
    assertEquals(
      "interrupted_set",
      CoachRunTools(json)
        .assessment(
          user.userId,
          interventions.planningSnapshot(user.userId, workout, snapshot),
          json.createObjectNode(),
        )["reason_code"]
        .asString(),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM coach_intervention_proposals", Int::class.java),
    )
  }

  @Test
  fun `wording changes no operations and provider failure preserves the decision`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val snapshot = interventionSnapshot(workout) as tools.jackson.databind.node.ObjectNode
    (snapshot["exercises"][0]["sets"][1] as tools.jackson.databind.node.ObjectNode).set(
      "reported_feelings",
      json.valueToTree<JsonNode>(listOf("HARDER_THAN_EXPECTED")),
    )
    runs.session(user, workout, sessionBody(workout, 1, false, snapshot))
    db.update("UPDATE coach_sessions SET behavior_mode='ENFORCE' WHERE owner_id=?", user.userId)
    runs.session(user, workout, sessionBody(workout, 2, true, snapshot))
    val prepared =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM coach_intervention_proposals",
          String::class.java,
        )!!
      )
    provider.completion = { input ->
      assertTrue(input.tools.isEmpty)
      throw IllegalStateException("fixture provider unavailable")
    }
    dialogue.runNext()
    val presented = runs.workoutEvents(user, workout, 0).single()["result"]
    assertEquals(prepared["proposal"]["operations"], presented["proposal"]["operations"])
    assertEquals(prepared["proposal"]["proposalId"], presented["proposal"]["proposalId"])
    assertEquals(prepared["text"], presented["text"])
    dialogue.runNext()
    assertEquals(1, runs.workoutEvents(user, workout, 0).size)
  }

  @Test
  fun `admission bound preserves idempotent retries`() {
    val user = owner()
    val workout = UUID.randomUUID()
    val first = request(workout = workout)
    runs.submit(user, first)
    repeat(7) { runs.submit(user, request(workout = workout)) }
    assertEquals("QUEUED", runs.submit(user, first)["state"].asString())
    assertEquals(
      429,
      assertThrows(ApiException::class.java) { runs.submit(user, request(workout = workout)) }
        .status,
    )
    assertEquals(8, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
  }

  @Test
  fun `submission is idempotent and conflicting payload is rejected`() {
    val owner = owner()
    val id = UUID.randomUUID()
    val workout = UUID.randomUUID()
    val body = request(id, workout)
    assertEquals("QUEUED", runs.submit(owner, body)["state"].asString())
    assertEquals(id.toString(), runs.submit(owner, body)["runId"].asString())
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    assertEquals(
      409,
      assertThrows(ApiException::class.java) {
          runs.submit(owner, request(id, workout, "Другой текст"))
        }
        .status,
    )
  }

  @Test
  fun `status and replay never disclose another owners run`() {
    val a = owner()
    val b = owner()
    val id = UUID.randomUUID()
    runs.submit(a, request(id))
    assertEquals(404, assertThrows(ApiException::class.java) { runs.status(b, id) }.status)
    assertEquals(404, assertThrows(ApiException::class.java) { runs.events(b, id, 0) }.status)
    assertEquals(1, runs.events(a, id, 0).size)
    assertTrue(runs.events(a, id, 1).isEmpty())
  }

  @Test
  fun `cancellation is durable and manual messages are not superseded`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    runs.submit(a, request(first, workout))
    runs.submit(a, request(second, workout))
    assertEquals("QUEUED", runs.status(a, first)["state"].asString())
    runs.cancel(a, first)
    runs.cancel(a, first)
    assertEquals("CANCELLED", runs.status(a, first)["state"].asString())
    assertEquals("QUEUED", runs.status(a, second)["state"].asString())
    val events = runs.events(a, first, 1)
    assertEquals(1, events.size)
    assertEquals("completed", events.single()["type"].asString())
  }

  @Test
  fun `expired lease is reclaimed and result survives without subscribers`() {
    val a = owner()
    val id = UUID.randomUUID()
    runs.submit(a, request(id))
    db.update(
      "UPDATE coach_runs SET state='RUNNING',executions=1,lease_token=?,lease_until=? WHERE request_id=?",
      UUID.randomUUID(),
      Timestamp.from(Instant.EPOCH),
      id,
    )
    runs.runNext()
    val status = runs.status(a, id)
    assertEquals("SUCCEEDED", status["state"].asString())
    assertEquals(
      2,
      db.queryForObject("SELECT executions FROM coach_runs WHERE request_id=?", Int::class.java, id),
    )
    assertEquals("completed", runs.events(a, id, 0).last()["type"].asString())
    runs.runNext()
    assertEquals(1, provider.calls)
  }

  @Test
  fun `full snapshots tolerate sequence gaps and reject duplicate mutation`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val event = UUID.randomUUID()
    fun body(seq: Long) =
      json.writeValueAsBytes(
        mapOf(
          "eventId" to event.toString(),
          "sequence" to seq,
          "contextVersion" to "b".repeat(64),
          "snapshot" to
            mapOf(
              "workout_id" to workout.toString(),
              "revision" to 1,
              "exercises" to emptyList<Any>(),
            ),
          "initiativeEnabled" to false,
          "active" to true,
        )
      )
    assertTrue(runs.session(a, workout, body(7))["accepted"].asBoolean())
    assertTrue(runs.session(a, workout, body(7))["accepted"].asBoolean())
    assertEquals(
      409,
      assertThrows(ApiException::class.java) { runs.session(a, workout, body(8)) }.status,
    )
  }

  @Test
  fun `disabling initiative supersedes running automatic work`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val id = UUID.randomUUID()
    val automatic = json.readTree(request(id, workout)) as tools.jackson.databind.node.ObjectNode
    automatic.put("automatic", true)
    runs.submit(a, json.writeValueAsBytes(automatic))
    db.update(
      "UPDATE coach_runs SET state='RUNNING',lease_token=?,lease_until=now()+interval '1 minute' WHERE request_id=?",
      UUID.randomUUID(),
      id,
    )
    val snapshot = automatic["snapshot"]
    runs.session(
      a,
      workout,
      json.writeValueAsBytes(
        mapOf(
          "eventId" to UUID.randomUUID().toString(),
          "sequence" to 1,
          "contextVersion" to "b".repeat(64),
          "snapshot" to snapshot,
          "initiativeEnabled" to false,
          "active" to true,
        )
      ),
    )
    assertEquals("SUPERSEDED", runs.status(a, id)["state"].asString())
  }

  @Test
  fun `worker preserves fifo behind an unexpired lease`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    runs.submit(a, request(first, workout))
    runs.submit(a, request(second, workout))
    db.update(
      "UPDATE coach_runs SET state='RUNNING',lease_token=?,lease_until=now()+interval '1 minute' WHERE request_id=?",
      UUID.randomUUID(),
      first,
    )
    runs.runNext()
    assertEquals(0, provider.calls)
    assertEquals("QUEUED", runs.status(a, second)["state"].asString())
    runs.cancel(a, first)
    runs.runNext()
    assertEquals("SUCCEEDED", runs.status(a, second)["state"].asString())
  }

  @Test
  fun `discovery provides a stable ordinal cursor`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    runs.submit(a, request(first, workout))
    runs.submit(a, request(second, workout))
    val page = runs.list(a, workout, 0)
    assertEquals(2, page.size)
    assertEquals(
      second.toString(),
      runs.list(a, workout, page.first()["ordinal"].asLong()).single()["runId"].asString(),
    )
  }

  @Test
  fun `an abandoned worker cannot publish after lease replacement`() {
    val a = owner()
    val id = UUID.randomUUID()
    runs.submit(a, request(id))
    provider.onCall = {
      db.update("UPDATE coach_runs SET lease_token=? WHERE request_id=?", UUID.randomUUID(), id)
    }
    runs.runNext()
    val status = runs.status(a, id)
    assertEquals("RUNNING", status["state"].asString())
    assertTrue(status["result"].isNull)
    assertTrue(runs.events(a, id, 0).none { it["type"].asString() == "completed" })
  }

  @Test
  fun `timer uses fresh sessions and schedules only once per context`() {
    val a = owner()
    val workout = UUID.randomUUID()
    val snapshot =
      mapOf(
        "workout_id" to workout.toString(),
        "revision" to 1,
        "exercises" to emptyList<Any>(),
        "available_time_ends_at_millis" to Instant.now().plusSeconds(120).toEpochMilli(),
      )
    db.update(
      "INSERT INTO coach_sessions(owner_id,workout_id,context_version,snapshot,initiative_enabled,active) VALUES (?,?,?,?::jsonb,true,true)",
      a.userId,
      workout,
      "c".repeat(64),
      json.writeValueAsString(snapshot),
    )
    runs.scheduleTimers()
    runs.scheduleTimers()
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    val timerInput =
      json.readTree(db.queryForObject("SELECT input::text FROM coach_runs", String::class.java))
    assertTrue(timerInput["snapshot"]["available_time_minutes"].asInt() in 1..2)
    db.update(
      "UPDATE coach_sessions SET context_version=?,updated_at=now()-interval '3 minutes'",
      "d".repeat(64),
    )
    db.update("UPDATE coach_runs SET state='CANCELLED'")
    runs.scheduleTimers()
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    db.update("UPDATE coach_sessions SET updated_at=now()")
    runs.scheduleTimers()
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
  }

  @Test
  fun `sse disconnect leaves execution intact and replay delivers terminal event`() {
    val a = owner()
    val id = UUID.randomUUID()
    runs.submit(a, request(id))
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    db.update("UPDATE sessions SET access_hash=? WHERE id=?", crypto.hash(token), a.sessionId)
    val client = java.net.http.HttpClient.newHttpClient()
    fun http(after: Long) =
      java.net.http.HttpRequest.newBuilder(
          java.net.URI("http://localhost:$port/v1/coach/runs/$id/events?after=$after")
        )
        .header("Authorization", "Bearer $token")
        .GET()
        .build()
    val stream = client.send(http(0), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
    assertEquals(200, stream.statusCode())
    stream.body().bufferedReader().use { reader ->
      var line = reader.readLine()
      while (line != null && !line.startsWith("data:")) line = reader.readLine()
      assertNotNull(line)
    }
    assertEquals("QUEUED", runs.status(a, id)["state"].asString())
    runs.runNext()
    assertEquals("SUCCEEDED", runs.status(a, id)["state"].asString())
    val replay = client.send(http(1), java.net.http.HttpResponse.BodyHandlers.ofString())
    assertEquals(200, replay.statusCode())
    assertTrue(replay.body().contains("event:completed"))
    assertFalse(replay.body().contains("id:1\n"))
  }

  @Test
  fun `malformed ids and mismatched workout snapshots are rejected`() {
    val a = owner()
    val input = json.readTree(request()) as tools.jackson.databind.node.ObjectNode
    input.remove("requestId")
    assertEquals(
      400,
      assertThrows(ApiException::class.java) { runs.submit(a, json.writeValueAsBytes(input)) }
        .status,
    )
    input.put("requestId", UUID.randomUUID().toString())
    input.put("workoutId", UUID.randomUUID().toString())
    assertEquals(
      400,
      assertThrows(ApiException::class.java) { runs.submit(a, json.writeValueAsBytes(input)) }
        .status,
    )
  }

  @Test
  fun `proposal receipts are idempotent and conflicting decisions are rejected`() {
    val a = owner()
    val id = UUID.randomUUID()
    val proposal = UUID.randomUUID()
    runs.submit(a, request(id))
    db.update(
      "UPDATE coach_runs SET state='SUCCEEDED',result=?::jsonb WHERE request_id=?",
      json.writeValueAsString(
        mapOf("kind" to "proposal", "proposal" to mapOf("proposalId" to proposal.toString()))
      ),
      id,
    )
    val receipt = UUID.randomUUID()
    fun body(status: String) =
      json.writeValueAsBytes(
        mapOf(
          "proposalId" to proposal.toString(),
          "receiptId" to receipt.toString(),
          "status" to status,
        )
      )
    runs.receipt(a, id, body("APPLIED"))
    runs.receipt(a, id, body("APPLIED"))
    assertEquals("APPLIED", runs.status(a, id)["applicationStatus"].asString())
    assertEquals(
      409,
      assertThrows(ApiException::class.java) { runs.receipt(a, id, body("REJECTED")) }.status,
    )
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_run_receipts", Int::class.java))
  }

  private fun sessionBody(
    workout: UUID,
    sequence: Long,
    enabled: Boolean = false,
    snapshot: JsonNode? = null,
  ): ByteArray =
    json.writeValueAsBytes(
      mapOf(
        "eventId" to UUID.randomUUID().toString(),
        "sequence" to sequence,
        "contextVersion" to "e".repeat(64),
        "snapshot" to (snapshot ?: json.readTree(request(workout = workout))["snapshot"]),
        "initiativeEnabled" to enabled,
        "active" to true,
      )
    )

  @Test
  fun `message snapshot admission rolls back together and replay cannot revert model`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    val id = UUID.randomUUID()
    val state = json.readTree(sessionBody(workout, 1)) as tools.jackson.databind.node.ObjectNode
    state.put("sequence", 0)
    fun body(model: String = "fixture") =
      json.writeValueAsBytes(
        mapOf(
          "requestId" to id.toString(),
          "message" to "Привет",
          "model" to model,
          "state" to state,
        )
      )
    assertThrows(ApiException::class.java) { runs.message(owner, workout, body()) }
    assertEquals(0, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    state.put("sequence", 1)
    val bytes = body()
    runs.message(owner, workout, bytes)
    db.update("UPDATE coach_sessions SET model='newer'")
    runs.message(owner, workout, bytes)
    assertEquals("newer", db.queryForObject("SELECT model FROM coach_sessions", String::class.java))
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_runs", Int::class.java))
    assertEquals("USER", runs.status(owner, id)["origin"].asString())
    assertThrows(ApiException::class.java) { runs.message(owner, workout, body("changed")) }
  }

  @Test
  fun `stale snapshots replay their original refusal without refreshing session`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    runs.session(owner, workout, sessionBody(workout, 9))
    val stale = sessionBody(workout, 2)
    val first = runs.session(owner, workout, stale)
    assertFalse(first["accepted"].asBoolean())
    assertEquals(
      first["accepted"].asBoolean(),
      runs.session(owner, workout, stale)["accepted"].asBoolean(),
    )
    assertEquals(
      first["sequence"].asLong(),
      runs.session(owner, workout, stale)["sequence"].asLong(),
    )
    assertEquals(9L, db.queryForObject("SELECT sequence FROM coach_sessions", Long::class.java))
  }

  @Test
  fun `semantic versions ignore clocks pulse and json field order`() {
    val a =
      json.readTree(
        """{"revision":1,"pulse":{"bpm":80},"observed_at_millis":1,"elapsed_seconds":3,"rest":{"start_id":"a","remaining_seconds":60},"available_time_ends_at_millis":1000,"available_time_minutes":1}"""
      )
    val b =
      json.readTree(
        """{"rest":{"remaining_seconds":40,"start_id":"a"},"revision":1,"observed_at_millis":2,"elapsed_seconds":4,"available_time_ends_at_millis":1000,"available_time_minutes":0}"""
      )
    assertEquals(runs.semanticVersion(a), runs.semanticVersion(b))
    (b as tools.jackson.databind.node.ObjectNode).put("revision", 2)
    assertNotEquals(runs.semanticVersion(a), runs.semanticVersion(b))
  }

  @Test
  fun `workout journal stays ordered across tasks and revalidates revoked login`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    assertTrue(runs.workoutEvents(owner, workout, 0).isEmpty())
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    runs.submit(owner, request(first, workout))
    runs.runNext()
    runs.submit(owner, request(second, workout))
    val events = runs.workoutEvents(owner, workout, 0)
    assertEquals((1L..events.size.toLong()).toList(), events.map { it["sequence"].asLong() })
    assertEquals("created", events.first()["type"].asString())
    assertEquals(second.toString(), events.last()["runId"].asString())
    assertTrue(events.all { it["origin"].asString() == "USER" })
    assertEquals(events.drop(2), runs.workoutEvents(owner, workout, 2))
    db.update("DELETE FROM sessions WHERE id=?", owner.sessionId)
    assertThrows(ApiException::class.java) { runs.workoutEvents(owner, workout, 0) }
  }

  @Test
  fun `automatic result expires before publication while accepted user work survives disconnection`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    val id = UUID.randomUUID()
    val input = json.readTree(request(id, workout)) as tools.jackson.databind.node.ObjectNode
    input.put("automatic", true)
    runs.session(owner, workout, sessionBody(workout, 1, true, input["snapshot"]))
    runs.submit(owner, json.writeValueAsBytes(input))
    provider.onCall = {
      db.update("UPDATE coach_sessions SET updated_at=TIMESTAMPTZ '2000-01-01 00:00:00+00'")
    }
    runs.runNext()
    assertEquals("SUPERSEDED", runs.status(owner, id)["state"].asString())
    assertTrue(runs.status(owner, id)["result"].isNull)
    val user = UUID.randomUUID()
    runs.submit(owner, request(user, workout))
    runs.runNext()
    assertEquals("SUCCEEDED", runs.status(owner, user)["state"].asString())
  }

  @Test
  fun `receipts persist rejection evidence and deduplicate migrated memory`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    val id = UUID.randomUUID()
    val proposal = UUID.randomUUID()
    runs.submit(owner, request(id, workout))
    db.update(
      "UPDATE coach_runs SET state='SUCCEEDED',result=?::jsonb WHERE request_id=?",
      json.writeValueAsString(
        mapOf(
          "kind" to "proposal",
          "proposal" to
            mapOf(
              "proposalId" to proposal.toString(),
              "operations" to listOf(mapOf("action" to "add_set", "section_id" to "section")),
            ),
        )
      ),
      id,
    )
    val snapshot =
      json.readTree(request(workout = workout))["snapshot"]
        as tools.jackson.databind.node.ObjectNode
    val raw =
      json.writeValueAsBytes(
        mapOf(
          "receiptId" to UUID.randomUUID().toString(),
          "proposalId" to proposal.toString(),
          "status" to "REJECTED",
          "reason" to "KEEP_EXERCISE",
          "snapshot" to snapshot,
        )
      )
    runs.receipt(owner, id, raw)
    runs.receipt(owner, id, raw)
    snapshot.set(
      "decisions",
      json.valueToTree<JsonNode>(
        listOf(
          mapOf(
            "proposalId" to proposal.toString(),
            "status" to "REJECTED",
            "reason" to "UNAVAILABLE_WEIGHT",
          )
        )
      ),
    )
    runs.session(owner, workout, sessionBody(workout, 1, snapshot = snapshot))
    assertEquals(1, db.queryForObject("SELECT count(*) FROM coach_decisions", Int::class.java))
    assertEquals(
      "KEEP_EXERCISE",
      db.queryForObject("SELECT payload->>'reason' FROM coach_decisions", String::class.java),
    )
    assertNotNull(
      db.queryForObject("SELECT snapshot::text FROM coach_run_receipts", String::class.java)
    )
  }

  @Test
  fun `idle workout SSE discovers tasks stays open after completion and resumes with last event id`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    db.update("UPDATE sessions SET access_hash=? WHERE id=?", crypto.hash(token), owner.sessionId)
    val client = java.net.http.HttpClient.newHttpClient()
    fun open(after: Long) =
      client.send(
        java.net.http.HttpRequest.newBuilder(
            java.net.URI("http://localhost:$port/v1/coach/sessions/$workout/events")
          )
          .header("Authorization", "Bearer $token")
          .header("Last-Event-ID", after.toString())
          .GET()
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofInputStream(),
      )
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
      val connection = open(0)
      assertEquals(200, connection.statusCode())
      connection.body().bufferedReader().use { reader ->
        fun nextData(): JsonNode =
          executor
            .submit<JsonNode> {
              generateSequence { reader.readLine() }
                .first { it.startsWith("data:") }
                .removePrefix("data:")
                .let(json::readTree)
            }
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
        val first = UUID.randomUUID()
        runs.submit(owner, request(first, workout))
        assertEquals("created", nextData()["type"].asString())
        runs.runNext()
        var terminal = nextData()
        while (terminal["type"].asString() != "completed") terminal = nextData()
        val cursor = terminal["sequence"].asLong()
        val second = UUID.randomUUID()
        runs.submit(owner, request(second, workout))
        assertEquals(second.toString(), nextData()["runId"].asString())
        open(cursor).body().bufferedReader().use { resumed ->
          val replay =
            executor
              .submit<String> {
                generateSequence { resumed.readLine() }.first { it.startsWith("data:") }
              }
              .get(5, java.util.concurrent.TimeUnit.SECONDS)
          assertEquals(
            second.toString(),
            json.readTree(replay.removePrefix("data:"))["runId"].asString(),
          )
          db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", owner.sessionId)
          executor
            .submit<Unit> { while (resumed.readLine() != null) {} }
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
      }
    }
  }

  @Autowired lateinit var dataSource: javax.sql.DataSource

  @Test
  fun `both applied variants of migration 022 upgrade without losing session events`() {
    val owner = owner()
    val workout = UUID.randomUUID()
    runs.session(owner, workout, sessionBody(workout, 1))
    val id = UUID.randomUUID()
    runs.submit(owner, request(id, workout))
    val eventCount = db.queryForObject("SELECT count(*) FROM coach_workout_events", Int::class.java)
    for ((checksum, hadResponse) in
      listOf(
        "9:94adf1dec72cd01e3aea086dc4c5c9d4" to false,
        "9:e6359f6df0e93593ba68ddd6229dba2d" to true,
      )) {
      db.update("DELETE FROM databasechangelog WHERE id='023-coach-session-acknowledgements'")
      db.update(
        "UPDATE databasechangelog SET md5sum=? WHERE id='022-coach-session-stream'",
        checksum,
      )
      if (!hadResponse) db.execute("ALTER TABLE coach_session_events DROP COLUMN response")
      liquibase.integration.spring.SpringLiquibase().apply {
        setDataSource(this@CoachRunIntegrationTest.dataSource)
        setChangeLog("classpath:db/changelog/master.yaml")
        afterPropertiesSet()
      }
      assertEquals(
        1,
        db.queryForObject(
          "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='coach_session_events' AND column_name='response'",
          Int::class.java,
        ),
      )
      assertEquals(
        eventCount,
        db.queryForObject("SELECT count(*) FROM coach_workout_events", Int::class.java),
      )
      assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM coach_session_events", Int::class.java),
      )
      assertEquals(id.toString(), runs.status(owner, id)["runId"].asString())
    }
  }
}
