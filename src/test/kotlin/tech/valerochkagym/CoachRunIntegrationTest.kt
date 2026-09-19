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
  properties = ["gym.coach-runs.enabled=false", "gym.calendar-jobs.enabled=false"],
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

    override fun catalog() = CoachModelCatalog("AVAILABLE", "fixture", listOf("fixture"))

    override fun complete(input: CoachTurnInput): JsonNode {
      calls++
      onCall()
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
      db.update("UPDATE coach_sessions SET updated_at=now()-interval '121 seconds'")
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
