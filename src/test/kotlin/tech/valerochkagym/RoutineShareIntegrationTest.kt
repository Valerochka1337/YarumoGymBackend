package tech.valerochkagym

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutineShareIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
        "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
      )

    @DynamicPropertySource
    @JvmStatic
    fun properties(registry: DynamicPropertyRegistry) {
      registry.add("spring.datasource.url", postgres::getJdbcUrl)
      registry.add("spring.datasource.username", postgres::getUsername)
      registry.add("spring.datasource.password", postgres::getPassword)
      registry.add("gym.token-pepper") { "test-pepper-with-at-least-thirty-two-bytes" }
    }
  }

  data class Actor(val id: UUID, val session: UUID, val token: String, val email: String)

  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var crypto: Crypto
  @LocalServerPort var port = 0
  private val client = HttpClient.newHttpClient()

  @BeforeEach
  fun reset() {
    db.execute("TRUNCATE users,standard_records CASCADE")
    db.update(
      "UPDATE catalog_state SET revision=0,active=false,source_user_id=NULL,activated_at=NULL"
    )
  }

  @Test
  fun `snapshot is immutable private and maps repeated custom exercise to one recipient identity`() {
    val author = actor()
    val source = sourceRoutine(author, "<img src=x onerror=alert(1)>")
    // The record itself may be older than the current account head: expectedRevision is the head.
    db.update("UPDATE sync_heads SET revision=2 WHERE user_id=?", author.id)
    val token = create(author, source.routine, 2)
    val stale = createResponse(author, source.routine, expectedRevision = 1, expectedStatus = 409)
    assertEquals("routine_share_stale", stale["code"].asString())
    val preview = publicJson(token)

    assertEquals(source.standard.toString(), preview["exercises"][0]["exerciseKey"].asString())
    assertEquals(
      preview["exercises"][1]["exerciseKey"].asString(),
      preview["exercises"][2]["exerciseKey"].asString(),
    )
    assertFalse(preview.toString().contains(source.secret))
    assertFalse(preview.toString().contains(source.gym.toString()))
    assertEquals(
      setOf("title", "estimatedDurationSeconds", "exercises"),
      preview.properties().map { it.key }.toSet(),
    )
    assertEquals(90, preview["exercises"][1]["restSeconds"].asInt())
    assertTrue(preview["estimatedDurationSeconds"].asLong() > 0)

    db.update(
      "UPDATE records SET payload=?::jsonb WHERE user_id=? AND kind='routine' AND id=?",
      routinePayload(
        "Changed after share",
        source.standard,
        source.custom,
        source.gym,
        "changed secret",
      ),
      author.id,
      source.routine,
    )
    val afterChange = publicJson(token)
    assertEquals("<img src=x onerror=alert(1)>", afterChange["title"].asString())
    assertEquals(preview, afterChange)

    val recipient = actor()
    val first = import(recipient, token, UUID.randomUUID())
    val importedRoutine = UUID.fromString(first["routineId"].asString())
    val repeated = import(recipient, token, UUID.randomUUID())
    assertTrue(repeated["alreadyImported"].asBoolean())
    assertEquals(importedRoutine, UUID.fromString(repeated["routineId"].asString()))
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind='exercise' AND deleted=false",
        Int::class.java,
        recipient.id,
      ),
    )
    val routine =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='routine' AND id=?",
          String::class.java,
          recipient.id,
          importedRoutine,
        )
      )
    assertEquals(source.standard.toString(), routine["exercises"][0]["exerciseId"].asString())
    assertEquals(routine["exercises"][1]["exerciseId"], routine["exercises"][2]["exerciseId"])
    assertNotEquals(source.custom.toString(), routine["exercises"][1]["exerciseId"].asString())

    val secondToken = create(author, source.routine, 2)
    val secondImport = import(recipient, secondToken, UUID.randomUUID())
    val secondRoutine =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='routine' AND id=?",
          String::class.java,
          recipient.id,
          UUID.fromString(secondImport["routineId"].asString()),
        )
      )
    assertNotEquals(
      routine["exercises"][1]["exerciseId"].asString(),
      secondRoutine["exercises"][1]["exerciseId"].asString(),
    )
  }

  @Test
  fun `concurrent imports share one receipt and revoke blocks public and replay access`() {
    val author = actor()
    val source = sourceRoutine(author, "Concurrent")
    val token = create(author, source.routine, 1)
    val list =
      request("GET", "/v1/routine-shares?routineId=${source.routine}&limit=50", actor = author)
    assertEquals(200, list.statusCode(), list.body())
    assertTrue(list.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"))
    val recipient = actor()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val importOperations = (1..2).map { UUID.randomUUID() }
      val requests =
        importOperations.map { operation ->
          executor.submit<HttpResponse<String>> {
            request(
              "POST",
              "/v1/routine-shares/preview/$token/import",
              mapOf("operationId" to operation.toString()),
              recipient,
            )
          }
        }
      val outcomes = requests.map { it.get() }
      outcomes.forEach { assertEquals(200, it.statusCode(), it.body()) }
      val routineIds = outcomes.map { json.readTree(it.body())["routineId"].asString() }.toSet()
      assertEquals(1, routineIds.size)
      assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM routine_share_import_receipts", Int::class.java),
      )
      val shareId =
        UUID.fromString(createResponse(author, source.routine, token)["shareId"].asString())
      val revokeOperation = UUID.randomUUID().toString()
      val revoked =
        request(
          "POST",
          "/v1/routine-shares/$shareId/revoke",
          mapOf("operationId" to revokeOperation),
          author,
        )
      assertEquals(200, revoked.statusCode(), revoked.body())
      assertEquals(
        revoked.body(),
        request(
            "POST",
            "/v1/routine-shares/$shareId/revoke",
            mapOf("operationId" to revokeOperation),
            author,
          )
          .body(),
      )
      assertEquals(404, request("GET", "/v1/routine-shares/preview/$token").statusCode())
      assertEquals(
        404,
        request(
            "POST",
            "/v1/routine-shares/preview/$token/import",
            mapOf("operationId" to UUID.randomUUID().toString()),
            recipient,
          )
          .statusCode(),
      )
      assertEquals(
        404,
        request(
            "POST",
            "/v1/routine-shares/preview/$token/import",
            mapOf("operationId" to importOperations.first().toString()),
            recipient,
          )
          .statusCode(),
      )
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `active public link redirects browsers while preserving privacy headers and import`() {
    val author = actor()
    val source = sourceRoutine(author, "<script>bad()</script>")
    val token = create(author, source.routine, 1)
    val page = request("GET", "/r/$token")
    assertEquals(302, page.statusCode())
    assertEquals(
      "https://app.valerochkagym.tech/r/$token",
      page.headers().firstValue("Location").orElseThrow(),
    )
    assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow())
    assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow())
    assertTrue(page.headers().firstValue("X-Robots-Tag").orElseThrow().contains("noindex"))
    assertTrue(
      page
        .headers()
        .firstValue("Content-Security-Policy")
        .orElseThrow()
        .contains("default-src 'none'")
    )
    val assetLinks = request("GET", "/.well-known/assetlinks.json")
    assertEquals(200, assetLinks.statusCode())
    assertEquals(
      "A0:C2:6E:8E:34:44:DF:72:C9:4A:1D:27:EB:17:84:7D:5A:10:4C:0E:B4:C4:DC:C5:18:0F:2F:32:76:9A:1A:61",
      json.readTree(assetLinks.body())[0]["target"]["sha256_cert_fingerprints"][0].asString(),
    )

    val recipient = actor()
    import(recipient, token, UUID.randomUUID())
    val code = "delete-proof"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01')",
      author.email,
      crypto.hash("${author.email}:delete:$code"),
    )
    assertEquals(200, request("DELETE", "/v1/me", mapOf("code" to code), author).statusCode())
    assertEquals(404, request("GET", "/v1/routine-shares/preview/$token").statusCode())
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM routine_share_import_receipts", Int::class.java),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind='routine' AND deleted=false",
        Int::class.java,
        recipient.id,
      ),
    )
  }

  @Test
  fun `sharing denies foreign owners and unavailable standards without a partial import`() {
    val author = actor()
    val other = actor()
    val source = sourceRoutine(author, "Private")
    assertEquals(
      "routine_not_found",
      createResponse(other, source.routine, expectedRevision = 0, expectedStatus = 404)["code"]
        .asString(),
    )
    val token = create(author, source.routine, 1)
    val shareId =
      UUID.fromString(createResponse(author, source.routine, token)["shareId"].asString())
    assertEquals(
      404,
      request(
          "POST",
          "/v1/routine-shares/$shareId/revoke",
          mapOf("operationId" to UUID.randomUUID().toString()),
          other,
        )
        .statusCode(),
    )
    assertEquals(
      0,
      json
        .readTree(
          request("GET", "/v1/routine-shares?routineId=${source.routine}&limit=50", actor = other)
            .body()
        )["items"]
        .size(),
    )
    val duplicateCreate =
      """{"operationId":"${UUID.randomUUID()}","operationId":"${UUID.randomUUID()}","routineId":"${source.routine}","expectedRevision":1,"catalogRevision":0}"""
    assertEquals(400, requestRaw("POST", "/v1/routine-shares", duplicateCreate, other).statusCode())
    val quotedRevision =
      """{"operationId":"${UUID.randomUUID()}","routineId":"${source.routine}","expectedRevision":"1","catalogRevision":0}"""
    assertEquals(400, requestRaw("POST", "/v1/routine-shares", quotedRevision, author).statusCode())
    val nullRevision =
      """{"operationId":"${UUID.randomUUID()}","routineId":"${source.routine}","expectedRevision":null,"catalogRevision":0}"""
    assertEquals(400, requestRaw("POST", "/v1/routine-shares", nullRevision, author).statusCode())
    val duplicateRevoke =
      """{"operationId":"${UUID.randomUUID()}","operationId":"${UUID.randomUUID()}"}"""
    assertEquals(
      400,
      requestRaw("POST", "/v1/routine-shares/$shareId/revoke", duplicateRevoke, author).statusCode(),
    )
    val duplicateImport =
      """{"operationId":"${UUID.randomUUID()}","operationId":"${UUID.randomUUID()}"}"""
    assertEquals(
      400,
      requestRaw("POST", "/v1/routine-shares/preview/$token/import", duplicateImport, other)
        .statusCode(),
    )
    db.update(
      "UPDATE standard_records SET archived=true WHERE kind='exercise' AND id=?",
      source.standard,
    )
    val recipient = actor()
    val rejected =
      request(
        "POST",
        "/v1/routine-shares/preview/$token/import",
        mapOf("operationId" to UUID.randomUUID().toString()),
        recipient,
      )
    assertEquals(409, rejected.statusCode(), rejected.body())
    assertEquals("standard_exercise_unavailable", json.readTree(rejected.body())["code"].asString())
    assertEquals(
      0L,
      db.queryForObject(
        "SELECT revision FROM sync_heads WHERE user_id=?",
        Long::class.java,
        recipient.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=?",
        Int::class.java,
        recipient.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM routine_share_import_receipts", Int::class.java),
    )
  }

  @Test
  fun `trial result creates one portable routine and completed workout then replays its receipt`() {
    val author = actor()
    val source = sourceRoutine(author, "Browser trial")
    val token = create(author, source.routine, 1)
    val recipient = actor()
    val operation = UUID.randomUUID().toString()
    val body =
      mapOf(
        "operationId" to operation,
        "startedAt" to 1_700_000_000_000L,
        "finishedAt" to 1_700_000_010_000L,
        "completedSets" to
          listOf(
            mapOf(
              "exerciseIndex" to 0,
              "setIndex" to 0,
              "completedAt" to 1_700_000_005_000L,
              "weightKg" to 25.0,
              "reps" to 9,
              "durationSec" to null,
              "speedKmh" to null,
              "inclinePct" to null,
            )
          ),
      )
    val first = request("POST", "/v1/routine-shares/preview/$token/trial-results", body, recipient)
    assertEquals(200, first.statusCode(), first.body())
    val receipt = json.readTree(first.body())
    assertFalse(receipt["alreadySaved"].asBoolean())
    assertEquals(5, receipt.properties().size)
    assertEquals(
      3,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND deleted=false",
        Int::class.java,
        recipient.id,
      ),
    )
    assertEquals(
      3,
      db.queryForObject(
        "SELECT min_sync_version FROM sync_heads WHERE user_id=?",
        Int::class.java,
        recipient.id,
      ),
    )
    val workout =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='workout'",
          String::class.java,
          recipient.id,
        )
      )
    val savedSet = workout["exercises"][0]["sets"][0]
    assertEquals(20.0, savedSet["originalWeightKg"].asDouble())
    assertEquals(25.0, savedSet["actualWeightKg"].asDouble())
    assertTrue(savedSet["syncId"].isTextual)
    db.update(
      "UPDATE standard_records SET archived=true WHERE kind='exercise' AND id=?",
      source.standard,
    )
    val replay = request("POST", "/v1/routine-shares/preview/$token/trial-results", body, recipient)
    assertEquals(200, replay.statusCode(), replay.body())
    assertTrue(json.readTree(replay.body())["alreadySaved"].asBoolean())
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM routine_share_trial_receipts", Int::class.java),
    )
    val changed = body + ("finishedAt" to 1_700_000_010_001L)
    assertEquals(
      409,
      request("POST", "/v1/routine-shares/preview/$token/trial-results", changed, recipient)
        .statusCode(),
    )
    assertEquals(
      413,
      requestRaw(
          "POST",
          "/v1/routine-shares/preview/$token/trial-results",
          "x".repeat(64 * 1024 + 1),
          recipient,
        )
        .statusCode(),
    )
  }

  private data class Source(
    val routine: UUID,
    val standard: UUID,
    val custom: UUID,
    val gym: UUID,
    val secret: String,
  )

  private fun sourceRoutine(author: Actor, title: String): Source {
    val standard = UUID.randomUUID()
    val custom = UUID.randomUUID()
    val routine = UUID.randomUUID()
    val gym = UUID.randomUUID()
    val secret = "private-note-${UUID.randomUUID()}"
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('exercise',?,0,false,?::jsonb)",
      standard,
      "{\"name\":\"Canonical press\",\"type\":\"STRENGTH\"}",
    )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'exercise',?,1,false,?::jsonb)",
      author.id,
      custom,
      customExercisePayload("Same name as another personal exercise"),
    )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'routine',?,1,false,?::jsonb)",
      author.id,
      routine,
      routinePayload(title, standard, custom, gym, secret),
    )
    db.update("UPDATE sync_heads SET revision=1 WHERE user_id=?", author.id)
    return Source(routine, standard, custom, gym, secret)
  }

  private fun create(actor: Actor, routine: UUID, expectedRevision: Long): String =
    createResponse(actor, routine, expectedRevision = expectedRevision)["url"]
      .asString()
      .substringAfterLast('/')

  private fun createResponse(
    actor: Actor,
    routine: UUID,
    knownToken: String? = null,
    expectedRevision: Long = 1,
    expectedStatus: Int = 200,
  ): JsonNode {
    if (knownToken != null) {
      val list = request("GET", "/v1/routine-shares?routineId=$routine&limit=50", actor = actor)
      return json.readTree(list.body())["items"][0].also {
        assertEquals(knownToken, it["url"].asString().substringAfterLast('/'))
      }
    }
    val created =
      request(
        "POST",
        "/v1/routine-shares",
        mapOf(
          "operationId" to UUID.randomUUID().toString(),
          "routineId" to routine.toString(),
          "expectedRevision" to expectedRevision,
          "catalogRevision" to 0,
        ),
        actor,
      )
    assertEquals(expectedStatus, created.statusCode(), created.body())
    return json.readTree(created.body())
  }

  private fun import(actor: Actor, token: String, operation: UUID): JsonNode {
    val result =
      request(
        "POST",
        "/v1/routine-shares/preview/$token/import",
        mapOf("operationId" to operation.toString()),
        actor,
      )
    assertEquals(200, result.statusCode(), result.body())
    return json.readTree(result.body())
  }

  private fun publicJson(token: String): JsonNode {
    val result = request("GET", "/v1/routine-shares/preview/$token")
    assertEquals(200, result.statusCode(), result.body())
    assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow())
    return json.readTree(result.body())
  }

  private fun actor(): Actor {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    val email = "$id@example.com"
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, email)
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,0)", id)
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'routine-share-test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      crypto.hash(token),
    )
    return Actor(id, session, token, email)
  }

  private fun request(
    method: String,
    path: String,
    body: Any? = null,
    actor: Actor? = null,
  ): HttpResponse<String> {
    val builder =
      HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        .header("Content-Type", "application/json")
    if (actor != null) builder.header("Authorization", "Bearer ${actor.token}")
    return client.send(
      builder
        .method(
          method,
          body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
            ?: HttpRequest.BodyPublishers.noBody(),
        )
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
  }

  private fun requestRaw(
    method: String,
    path: String,
    body: String,
    actor: Actor,
  ): HttpResponse<String> =
    client.send(
      HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer ${actor.token}")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )

  private fun customExercisePayload(name: String) =
    """{"name":"$name","muscleGroup":"LEGS","type":"STRENGTH","isCustom":true,"updatedAt":1,"needsMuscleMapReview":false,"equipmentRequirementState":"KNOWN","muscles":[],"equipmentIds":[]}"""

  private fun routinePayload(title: String, standard: UUID, custom: UUID, gym: UUID, note: String) =
    """{"name":"$title","note":"$note","updatedAt":1,"gymIds":["$gym"],"exercises":[{"exerciseId":"$standard","position":0,"restSeconds":60,"plannedSets":[{"weightKg":20.0,"reps":8,"durationSec":null,"speedKmh":null,"inclinePct":null}]},{"exerciseId":"$custom","position":1,"restSeconds":null,"plannedSets":[]},{"exerciseId":"$custom","position":2,"restSeconds":30,"plannedSets":[]}]}"""
}
