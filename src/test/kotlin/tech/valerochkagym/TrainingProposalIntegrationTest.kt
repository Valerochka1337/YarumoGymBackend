package tech.valerochkagym

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.InternalAiProposalRequest
import tech.valerochkagym.service.ai.TrainingProposalAiCreator
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  classes = [Application::class],
)
class TrainingProposalIntegrationTest {
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

  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var crypto: Crypto
  @Autowired lateinit var ai: TrainingProposalAiCreator
  @Autowired lateinit var proposals: TrainingProposalService
  @Autowired lateinit var auth: AuthService
  @LocalServerPort var port = 0
  private val client = HttpClient.newHttpClient()

  data class Owner(
    val id: UUID,
    val session: UUID,
    val token: String,
    val exercise: UUID,
    val gym: UUID,
  )

  @BeforeEach
  fun reset() {
    // Keep the auth cleanup order: session descendants precede users. CASCADE clears proposal rows.
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=0,active=false")
  }

  @Test
  fun `approval atomically creates one routine plan receipt and replay ledger`() {
    val owner = owner()
    val proposal = proposal(owner)
    val raw = body(UUID.randomUUID(), 1, draft(owner))
    val first =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals(200, first.statusCode(), first.body())
    val accepted = json.readTree(first.body())
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind='routine'",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind='calendar_plan'",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM training_proposal_operations", Int::class.java),
    )
    assertEquals(
      1L,
      db.queryForObject(
        "SELECT revision FROM sync_heads WHERE user_id=?",
        Long::class.java,
        owner.id,
      ),
    )
    val retry =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals(200, retry.statusCode())
    assertEquals(accepted, json.readTree(retry.body()))
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `refinement receipt replays one changed next version and rejects rebound request bytes`() {
    val owner = owner()
    val proposal = proposal(owner)
    val identity = Identity(owner.id, owner.session, "${owner.id}@example.com")
    val requestId = UUID.randomUUID()
    val raw = "{\"requestId\":\"$requestId\",\"refinement\":\"Больше отдыха\"}".encodeToByteArray()
    val changed = draft(owner).copy(name = "План с отдыхом")
    val first =
      proposals.refineInternalAi(
        identity,
        proposal.proposalId,
        1,
        0,
        0,
        requestId,
        raw,
        sha256(raw),
        changed,
      )
    assertEquals(2, first.currentVersion)
    assertEquals("План с отдыхом", first.snapshot.draft.name)
    val replay =
      proposals.refineInternalAi(
        identity,
        proposal.proposalId,
        1,
        0,
        0,
        requestId,
        raw,
        sha256(raw),
        changed,
      )
    assertEquals(first, replay)
    val rebound = raw + byteArrayOf(0x20)
    val error =
      assertThrows<tech.valerochkagym.controller.advice.ApiException> {
        proposals.refineInternalAi(
          identity,
          proposal.proposalId,
          1,
          0,
          0,
          requestId,
          rebound,
          sha256(rebound),
          changed,
        )
      }
    assertEquals("ai_request_conflict", error.code)
    assertEquals(
      2,
      db.queryForObject(
        "SELECT current_version FROM training_proposals WHERE id=?",
        Int::class.java,
        proposal.proposalId,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `concurrent refinement versions create exactly one next version`() {
    val owner = owner()
    val proposal = proposal(owner)
    val identity = Identity(owner.id, owner.session, "${owner.id}@example.com")
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val outcomes =
        (1..2).map { index ->
          pool.submit<String> {
            start.await(5, TimeUnit.SECONDS)
            val requestId = UUID.randomUUID()
            val raw =
              "{\"requestId\":\"$requestId\",\"refinement\":\"Вариант $index\"}".encodeToByteArray()
            runCatching {
                proposals.refineInternalAi(
                  identity,
                  proposal.proposalId,
                  1,
                  0,
                  0,
                  requestId,
                  raw,
                  sha256(raw),
                  draft(owner).copy(name = "План $index"),
                )
              }
              .fold(
                { "version:${it.currentVersion}" },
                { "error:${(it as tech.valerochkagym.controller.advice.ApiException).code}" },
              )
          }
        }
      start.countDown()
      val results = outcomes.map { it.get(10, TimeUnit.SECONDS) }.toSet()
      assertEquals(setOf("version:2", "error:proposal_version_conflict"), results)
      assertEquals(
        2,
        db.queryForObject(
          "SELECT current_version FROM training_proposals WHERE id=?",
          Int::class.java,
          proposal.proposalId,
        ),
      )
      assertEquals(
        2,
        db.queryForObject(
          "SELECT count(*) FROM training_proposal_versions WHERE proposal_id=?",
          Int::class.java,
          proposal.proposalId,
        ),
      )
      assertEquals(
        0,
        db.queryForObject(
          "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
          Int::class.java,
          owner.id,
        ),
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `same refinement request cannot bind a different proposal during contention`() {
    val owner = owner()
    val first = proposal(owner)
    val second = proposal(owner)
    val identity = Identity(owner.id, owner.session, "${owner.id}@example.com")
    val requestId = UUID.randomUUID()
    val raw = "{\"requestId\":\"$requestId\",\"refinement\":\"Больше отдыха\"}".encodeToByteArray()
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val outcomes =
        listOf(first, second).map { target ->
          pool.submit<String> {
            start.await(5, TimeUnit.SECONDS)
            runCatching {
                proposals.refineInternalAi(
                  identity,
                  target.proposalId,
                  1,
                  0,
                  0,
                  requestId,
                  raw,
                  sha256(raw),
                  draft(owner),
                )
              }
              .fold(
                { "version:${it.currentVersion}" },
                { "error:${(it as tech.valerochkagym.controller.advice.ApiException).code}" },
              )
          }
        }
      start.countDown()
      assertEquals(
        setOf("version:2", "error:ai_request_conflict"),
        outcomes.map { it.get(10, TimeUnit.SECONDS) }.toSet(),
      )
      val totalVersions =
        (db.queryForObject(
          "SELECT current_version FROM training_proposals WHERE id=?",
          Int::class.java,
          first.proposalId,
        ) ?: 0) +
          (db.queryForObject(
            "SELECT current_version FROM training_proposals WHERE id=?",
            Int::class.java,
            second.proposalId,
          ) ?: 0)
      assertEquals(3, totalVersions)
      assertEquals(
        1,
        db.queryForObject(
          "SELECT count(*) FROM calendar_planner_refinements WHERE owner_id=? AND request_id=?",
          Int::class.java,
          owner.id,
          requestId,
        ),
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `raw bytes bind operation while a new operation after approval reuses result`() {
    val owner = owner()
    val proposal = proposal(owner)
    val operation = UUID.randomUUID()
    val raw = body(operation, 1, draft(owner))
    val first =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals(200, first.statusCode())
    val changedBytes = raw.replace("\"version\":1", "\"version\" : 1")
    val conflict =
      call(
        "POST",
        "/v1/training-proposals/${proposal.proposalId}/approve",
        owner,
        changedBytes,
        true,
      )
    assertEquals(409, conflict.statusCode())
    assertEquals("proposal_operation_conflict", json.readTree(conflict.body())["code"].asString())
    val replay =
      call(
        "POST",
        "/v1/training-proposals/${proposal.proposalId}/approve",
        owner,
        body(UUID.randomUUID(), 1, draft(owner)),
        true,
      )
    assertEquals(200, replay.statusCode())
    assertEquals(
      json.readTree(first.body())["routineId"],
      json.readTree(replay.body())["routineId"],
    )
    assertEquals(
      2,
      db.queryForObject("SELECT count(*) FROM training_proposal_operations", Int::class.java),
    )
  }

  @Test
  fun `recipient edit materializes new records while author version remains immutable`() {
    val owner = owner()
    val proposal = proposal(owner)
    val authorDraft =
      db.queryForObject(
        "SELECT draft::text FROM training_proposal_versions WHERE proposal_id=? AND version=1",
        String::class.java,
        proposal.proposalId,
      )!!
    val edited =
      draft(owner)
        .copy(
          name = "Изменённая программа",
          exercises =
            listOf(
              PlannedExercise(
                owner.exercise.toString(),
                120,
                listOf(PlannedSet(42.5, 12, null, null, null)),
              )
            ),
          startsAtMillis = 1_893_456_000_001,
        )
    val raw = body(UUID.randomUUID(), 1, edited)
    val first =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals(200, first.statusCode(), first.body())
    assertEquals(
      authorDraft,
      db.queryForObject(
        "SELECT draft::text FROM training_proposal_versions WHERE proposal_id=? AND version=1",
        String::class.java,
        proposal.proposalId,
      ),
    )
    val accepted = json.readTree(first.body())
    val routine =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='routine' AND id=?",
          String::class.java,
          owner.id,
          UUID.fromString(accepted["routineId"].asString()),
        )!!
      )
    val plan =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='calendar_plan' AND id=?",
          String::class.java,
          owner.id,
          UUID.fromString(accepted["calendarPlanId"].asString()),
        )!!
      )
    assertEquals("Изменённая программа", routine["name"].asString())
    assertEquals(42.5, routine["exercises"][0]["plannedSets"][0]["weightKg"].asDouble())
    assertEquals(1_893_456_000_001, plan["startsAtMillis"].asLong())
    val replay =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals(200, replay.statusCode())
    assertEquals(accepted, json.readTree(replay.body()))
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `invalid recipient edit creates no proposal approval state`() {
    val owner = owner()
    val proposal = proposal(owner)
    val invalid = body(UUID.randomUUID(), 1, draft(owner).copy(name = " "))
    assertEquals(
      400,
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, invalid, true)
        .statusCode(),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_operations", Int::class.java),
    )
    assertEquals(
      0L,
      db.queryForObject(
        "SELECT revision FROM sync_heads WHERE user_id=?",
        Long::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `recipient routes hide proposals and enforce capability and raw schema`() {
    val owner = owner()
    val outsider = owner()
    val proposal = proposal(owner)
    assertEquals(
      404,
      call("GET", "/v1/training-proposals/${proposal.proposalId}", outsider).statusCode(),
    )
    assertEquals(
      426,
      call(
          "POST",
          "/v1/training-proposals/${proposal.proposalId}/approve",
          owner,
          body(UUID.randomUUID(), 1, draft(owner)),
        )
        .statusCode(),
    )
    val duplicate =
      "{\"operationId\":\"${UUID.randomUUID()}\",\"operationId\":\"${UUID.randomUUID()}\",\"version\":1,\"draft\":{}}"
    assertEquals(
      400,
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, duplicate, true)
        .statusCode(),
    )
    assertEquals(
      413,
      call(
          "POST",
          "/v1/training-proposals/${proposal.proposalId}/approve",
          owner,
          "x".repeat(524289),
          true,
        )
        .statusCode(),
    )
  }

  @Test
  fun `recipient pagination detail and accepted result expose only fixture projection`() {
    val owner = owner()
    val first = proposal(owner)
    val second = proposal(owner)
    val third = proposal(owner)
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
      val page =
        call("GET", "/v1/training-proposals?limit=1" + (cursor?.let { "&cursor=$it" } ?: ""), owner)
      assertEquals(200, page.statusCode())
      val body = json.readTree(page.body())
      body["items"].forEach { seen.add(it["proposalId"].asString()) }
      cursor = body["nextCursor"].takeUnless { it.isNull }?.asString()
    } while (cursor != null)
    assertEquals(
      setOf(first.proposalId.toString(), second.proposalId.toString(), third.proposalId.toString()),
      seen,
    )
    val detail = call("GET", "/v1/training-proposals/${first.proposalId}", owner)
    assertEquals(200, detail.statusCode())
    assertFalse(detail.body().contains("profile"))
    assertEquals(
      409,
      call("GET", "/v1/training-proposals/${second.proposalId}/accepted-result", owner).statusCode(),
    )
  }

  @Test
  fun `proposal cursor is recipient scoped and unknown cursors expose no rows`() {
    val owner = owner()
    val outsider = owner()
    proposal(owner)
    proposal(owner)
    val first = call("GET", "/v1/training-proposals?limit=1", owner)
    assertEquals(200, first.statusCode())
    val cursor = json.readTree(first.body())["nextCursor"].asString()
    assertEquals(
      400,
      call("GET", "/v1/training-proposals?limit=1&cursor=$cursor", outsider).statusCode(),
    )
    val unknown =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(UUID.randomUUID().toString().toByteArray(StandardCharsets.US_ASCII))
    assertEquals(
      400,
      call("GET", "/v1/training-proposals?limit=1&cursor=$unknown", owner).statusCode(),
    )
  }

  @Test
  fun `large proposal list stays under one MiB while every cursor emits each ID once`() {
    val owner = owner()
    val gymIds = (1..1000).map { UUID.randomUUID().toString() }.sorted()
    val draft = draft(owner).copy(gymIds = gymIds)
    val now = java.sql.Timestamp.from(Instant.now())
    val ids = mutableSetOf<String>()
    repeat(35) {
      val id = UUID.randomUUID()
      ids += id.toString()
      db.update(
        "INSERT INTO training_proposals(id,recipient_id,source,status,current_version,created_at,updated_at,expires_at) VALUES (?,?,'AI','PENDING',1,?,?,?)",
        id,
        owner.id,
        now,
        now,
        java.sql.Timestamp.from(now.toInstant().plusSeconds(3600)),
      )
      db.update(
        "INSERT INTO training_proposal_versions(proposal_id,version,draft,owner_revision,catalog_revision,created_at) VALUES (?,1,?::jsonb,0,0,?)",
        id,
        json.writeValueAsString(draft),
        now,
      )
    }
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    var pages = 0
    do {
      val response =
        call(
          "GET",
          "/v1/training-proposals?limit=50" + (cursor?.let { "&cursor=$it" } ?: ""),
          owner,
        )
      assertEquals(200, response.statusCode())
      assertTrue(response.body().toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024)
      val body = json.readTree(response.body())
      assertTrue(body["items"].size() > 0, "cursor emitted an empty page")
      body["items"].forEach { item ->
        assertTrue(seen.add(item["proposalId"].asString()), "duplicate proposal")
      }
      cursor = body["nextCursor"].takeUnless { it.isNull }?.asString()
      pages++
      assertTrue(pages <= 35, "cursor did not converge")
    } while (cursor != null)
    assertEquals(ids, seen)
    assertTrue(pages > 1, "fixture must exercise the one MiB page cap")
  }

  @Test
  fun `internal AI proposal hook locks authenticated current context and preserves prior version`() {
    val owner = owner()
    val first = proposal(owner)
    val revised =
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          draft(owner).copy(name = "Изменённый план"),
          first.proposalId,
        )
      )
    assertEquals(2, revised.currentVersion)
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_versions WHERE proposal_id=?",
        Int::class.java,
        first.proposalId,
      ),
    )
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          1,
          0,
          draft(owner),
        )
      )
    }
    db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", owner.session)
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          draft(owner),
        )
      )
    }
  }

  @Test
  fun `confirmed recipient deletion removes only own proposal journal and keeps other approval`() {
    val owner = owner()
    val other = owner()
    val own = proposal(owner)
    val otherProposal = proposal(other)
    assertEquals(
      200,
      call(
          "POST",
          "/v1/training-proposals/${otherProposal.proposalId}/approve",
          other,
          body(UUID.randomUUID(), 1, draft(other)),
          true,
        )
        .statusCode(),
    )
    val code = "12345678"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${owner.id}@example.com",
      crypto.hash("${owner.id}@example.com:delete:$code"),
    )
    auth.delete(Identity(owner.id, owner.session, "${owner.id}@example.com"), code)
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM training_proposals WHERE recipient_id=?",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposals WHERE id=?",
        Int::class.java,
        otherProposal.proposalId,
      ),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind='calendar_plan'",
        Int::class.java,
        other.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM users WHERE id=?", Int::class.java, owner.id),
    )
    assertNotEquals(own.proposalId, otherProposal.proposalId)
  }

  @Test
  fun `invalid deletion code leaves recipient proposal journal intact`() {
    val owner = owner()
    proposal(owner)
    db.update("DELETE FROM sync_heads WHERE user_id=?", owner.id)
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      auth.delete(Identity(owner.id, owner.session, "${owner.id}@example.com"), "invalid")
    }
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposals WHERE recipient_id=?",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM users WHERE id=?", Int::class.java, owner.id),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM sync_heads WHERE user_id=?",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `approval and confirmed deletion barrier leave no deadlock or recipient residue`() {
    val owner = owner()
    val proposal = proposal(owner)
    val other = owner()
    val otherProposal = proposal(other)
    assertEquals(
      200,
      call(
          "POST",
          "/v1/training-proposals/${otherProposal.proposalId}/approve",
          other,
          body(UUID.randomUUID(), 1, draft(other)),
          true,
        )
        .statusCode(),
    )
    val code = "34567890"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${owner.id}@example.com",
      crypto.hash("${owner.id}@example.com:delete:$code"),
    )
    val ready = CountDownLatch(2)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val approval =
        pool.submit<HttpResponse<String>> {
          ready.countDown()
          assertTrue(release.await(10, TimeUnit.SECONDS))
          call(
            "POST",
            "/v1/training-proposals/${proposal.proposalId}/approve",
            owner,
            body(UUID.randomUUID(), 1, draft(owner)),
            true,
          )
        }
      val deletion =
        pool.submit<Boolean> {
          ready.countDown()
          assertTrue(release.await(10, TimeUnit.SECONDS))
          auth.delete(Identity(owner.id, owner.session, "${owner.id}@example.com"), code)
          true
        }
      assertTrue(ready.await(10, TimeUnit.SECONDS))
      release.countDown()
      assertTrue(deletion.get(20, TimeUnit.SECONDS))
      assertTrue(approval.get(20, TimeUnit.SECONDS).statusCode() in setOf(200, 401, 404))
    } finally {
      pool.shutdownNow()
    }
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM users WHERE id=?", Int::class.java, owner.id),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM training_proposals WHERE recipient_id=?",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_receipts WHERE proposal_id=?",
        Int::class.java,
        otherProposal.proposalId,
      ),
    )
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        other.id,
      ),
    )
  }

  @Test
  fun `reject is idempotent and approval rejects active expired and stale proposals without records`() {
    val owner = owner()
    val rejected = proposal(owner)
    val request = "{\"version\":1,\"reason\":null}"
    assertEquals(
      200,
      call("POST", "/v1/training-proposals/${rejected.proposalId}/reject", owner, request)
        .statusCode(),
    )
    assertEquals(
      200,
      call("POST", "/v1/training-proposals/${rejected.proposalId}/reject", owner, request)
        .statusCode(),
    )
    assertEquals(
      409,
      call(
          "POST",
          "/v1/training-proposals/${rejected.proposalId}/approve",
          owner,
          body(UUID.randomUUID(), 1, draft(owner)),
          true,
        )
        .statusCode(),
    )

    val stale = proposal(owner)
    db.update("UPDATE sync_heads SET revision=revision+1 WHERE user_id=?", owner.id)
    val staleResponse =
      call(
        "POST",
        "/v1/training-proposals/${stale.proposalId}/approve",
        owner,
        body(UUID.randomUUID(), 1, draft(owner)),
        true,
      )
    assertEquals("proposal_stale", json.readTree(staleResponse.body())["code"].asString())
    val expired = proposal(owner)
    db.update(
      "UPDATE training_proposals SET expires_at=TIMESTAMPTZ '2000-01-01' WHERE id=?",
      expired.proposalId,
    )
    val expiredResponse =
      call(
        "POST",
        "/v1/training-proposals/${expired.proposalId}/approve",
        owner,
        body(UUID.randomUUID(), 1, draft(owner)),
        true,
      )
    assertEquals("proposal_expired", json.readTree(expiredResponse.body())["code"].asString())
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `active workout blocks approval and rolls back all configuration writes`() {
    val owner = owner()
    val proposal = proposal(owner)
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'workout',?,0,false,?::jsonb)",
      owner.id,
      UUID.randomUUID(),
      "{\"name\":\"Live\",\"note\":\"\",\"routineId\":null,\"startedAt\":1,\"finishedAt\":null,\"exercises\":[],\"gymIds\":[],\"coachRevision\":0}",
    )
    val raw = body(UUID.randomUUID(), 1, draft(owner))
    val response =
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
    assertEquals("active_workout", json.readTree(response.body())["code"].asString())
    assertEquals(
      "PENDING",
      db.queryForObject(
        "SELECT status FROM training_proposals WHERE id=?",
        String::class.java,
        proposal.proposalId,
      ),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
    db.update("DELETE FROM records WHERE user_id=? AND kind='workout'", owner.id)
    assertEquals(
      200,
      call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
        .statusCode(),
    )
  }

  @Test
  fun `internal AI clears weights only without completed same exercise history`() {
    val owner = owner()
    val weighted =
      draft(owner)
        .copy(
          exercises =
            listOf(
              PlannedExercise(
                owner.exercise.toString(),
                60,
                listOf(PlannedSet(25.0, 10, null, null, null)),
              )
            )
        )
    val first =
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          weighted,
        )
      )
    assertTrue(
      json
        .readTree(
          db.queryForObject(
            "SELECT draft::text FROM training_proposal_versions WHERE proposal_id=?",
            String::class.java,
            first.proposalId,
          )!!
        )["exercises"][0]["plannedSets"][0]["weightKg"]
        .isNull
    )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'workout',?,0,false,?::jsonb)",
      owner.id,
      UUID.randomUUID(),
      "{\"name\":\"Done\",\"note\":\"\",\"routineId\":null,\"startedAt\":1,\"finishedAt\":2,\"exercises\":[{\"sectionId\":\"${UUID.randomUUID()}\",\"exerciseId\":\"${owner.exercise}\",\"position\":0,\"sets\":[]}],\"gymIds\":[],\"coachRevision\":0}",
    )
    val second =
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          weighted,
        )
      )
    assertEquals(
      25.0,
      json
        .readTree(
          db.queryForObject(
            "SELECT draft::text FROM training_proposal_versions WHERE proposal_id=?",
            String::class.java,
            second.proposalId,
          )!!
        )["exercises"][0]["plannedSets"][0]["weightKg"]
        .asDouble(),
    )
  }

  @Test
  fun `approval rejects narrowing overflow and gym cap before writes`() {
    val owner = owner()
    val proposal = proposal(owner)
    val base = body(UUID.randomUUID(), 1, draft(owner))
    listOf(
        base.replace("\"version\":1", "\"version\":9223372036854775807"),
        base.replace("\"restSeconds\":60", "\"restSeconds\":9223372036854775807"),
      )
      .forEach { raw ->
        assertEquals(
          400,
          call("POST", "/v1/training-proposals/${proposal.proposalId}/approve", owner, raw, true)
            .statusCode(),
        )
      }
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_operations", Int::class.java),
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
  }

  @Test
  fun `internal AI accepts exactly one thousand gyms and rejects one thousand one`() {
    val owner = owner()
    val gymIds = (listOf(owner.gym) + List(999) { UUID.randomUUID() }).map(UUID::toString).sorted()
    gymIds
      .filter { it != owner.gym.toString() }
      .forEach { id ->
        db.update(
          "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'gym',?,0,false,?::jsonb)",
          owner.id,
          UUID.fromString(id),
          json.writeValueAsString(gym(owner.exercise)),
        )
      }
    val exact = draft(owner).copy(gymIds = gymIds)
    val accepted =
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          exact,
        )
      )
    assertEquals(gymIds, accepted.snapshot.draft.gymIds)
    assertThrows(tech.valerochkagym.controller.advice.ApiException::class.java) {
      ai.createOrRevise(
        InternalAiProposalRequest(
          Identity(owner.id, owner.session, "${owner.id}@example.com"),
          0,
          0,
          exact.copy(gymIds = (gymIds + UUID.randomUUID().toString()).sorted()),
        )
      )
    }
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposals WHERE recipient_id=?",
        Int::class.java,
        owner.id,
      ),
    )
  }

  @Test
  fun `internal AI validates personal timed and cardio exercise set shapes`() {
    val owner = owner()
    fun create(type: String, set: PlannedSet) {
      val exercise = UUID.randomUUID()
      db.update(
        "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'exercise',?,0,false,?::jsonb)",
        owner.id,
        exercise,
        json.writeValueAsString(
          exercise().toMutableMap().apply {
            put("type", type)
            put("isCustom", true)
          }
        ),
      )
      db.update(
        "UPDATE records SET payload=?::jsonb WHERE user_id=? AND kind='gym' AND id=?",
        json.writeValueAsString(gym(exercise)),
        owner.id,
        owner.gym,
      )
      val proposal =
        ai.createOrRevise(
          InternalAiProposalRequest(
            Identity(owner.id, owner.session, "${owner.id}@example.com"),
            0,
            0,
            draft(owner)
              .copy(exercises = listOf(PlannedExercise(exercise.toString(), null, listOf(set)))),
          )
        )
      assertEquals(exercise.toString(), proposal.snapshot.draft.exercises.single().exerciseId)
    }
    create("TIMED", PlannedSet(null, null, 60, null, null))
    create("CARDIO", PlannedSet(null, null, 60, 8.0, 0.0))
  }

  @Test
  fun `concurrent approval barrier produces one record pair and identical recovery result`() {
    val owner = owner()
    val proposal = proposal(owner)
    val request = body(UUID.randomUUID(), 1, draft(owner))
    val ready = CountDownLatch(2)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val responses =
        (1..2).map {
          pool.submit<HttpResponse<String>> {
            ready.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            call(
              "POST",
              "/v1/training-proposals/${proposal.proposalId}/approve",
              owner,
              request,
              true,
            )
          }
        }
      assertTrue(ready.await(10, TimeUnit.SECONDS))
      release.countDown()
      val result = responses.map { it.get(20, TimeUnit.SECONDS) }
      assertEquals(listOf(200, 200), result.map { it.statusCode() }.sorted())
      assertEquals(json.readTree(result[0].body()), json.readTree(result[1].body()))
    } finally {
      pool.shutdownNow()
    }
    assertEquals(
      2,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
        Int::class.java,
        owner.id,
      ),
    )
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
    )
  }

  @Test
  fun `database fault rolls back proposal approval records head receipt and operation`() {
    val owner = owner()
    val proposal = proposal(owner)
    db.execute(
      "CREATE FUNCTION fail_proposal_plan() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'planned test failure'; END $$"
    )
    db.execute(
      "CREATE TRIGGER fail_proposal_plan BEFORE INSERT ON records FOR EACH ROW WHEN (NEW.kind='calendar_plan') EXECUTE FUNCTION fail_proposal_plan()"
    )
    try {
      val failure =
        call(
          "POST",
          "/v1/training-proposals/${proposal.proposalId}/approve",
          owner,
          body(UUID.randomUUID(), 1, draft(owner)),
          true,
        )
      assertTrue(failure.statusCode() >= 400)
      assertEquals(
        0L,
        db.queryForObject(
          "SELECT revision FROM sync_heads WHERE user_id=?",
          Long::class.java,
          owner.id,
        ),
      )
      assertEquals(
        0,
        db.queryForObject(
          "SELECT count(*) FROM records WHERE user_id=? AND kind IN ('routine','calendar_plan')",
          Int::class.java,
          owner.id,
        ),
      )
      assertEquals(
        0,
        db.queryForObject("SELECT count(*) FROM training_proposal_receipts", Int::class.java),
      )
      assertEquals(
        0,
        db.queryForObject("SELECT count(*) FROM training_proposal_operations", Int::class.java),
      )
    } finally {
      db.execute("DROP TRIGGER fail_proposal_plan ON records")
      db.execute("DROP FUNCTION fail_proposal_plan()")
    }
  }

  private fun owner(): Owner {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    val exercise = UUID.randomUUID()
    val gym = UUID.randomUUID()
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, "$id@example.com")
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      crypto.hash(token),
    )
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,0)", id)
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('exercise',?,0,false,?::jsonb)",
      exercise,
      json.writeValueAsString(exercise()),
    )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'gym',?,0,false,?::jsonb)",
      id,
      gym,
      json.writeValueAsString(gym(exercise)),
    )
    return Owner(id, session, token, exercise, gym)
  }

  private fun proposal(owner: Owner) =
    ai.createOrRevise(
      InternalAiProposalRequest(
        Identity(owner.id, owner.session, "${owner.id}@example.com"),
        db.queryForObject(
          "SELECT revision FROM sync_heads WHERE user_id=?",
          Long::class.java,
          owner.id,
        )!!,
        db.queryForObject("SELECT revision FROM catalog_state WHERE id=1", Long::class.java)!!,
        draft(owner),
      )
    )

  private fun draft(owner: Owner) =
    ApprovalDraft(
      "План",
      listOf(owner.gym.toString()),
      listOf(
        PlannedExercise(
          owner.exercise.toString(),
          60,
          listOf(PlannedSet(null, 10, null, null, null)),
        )
      ),
      1_893_456_000_000,
      "UTC",
    )

  private fun body(operation: UUID, version: Int, draft: ApprovalDraft) =
    json.writeValueAsString(ApprovalRequest(operation.toString(), version, draft))

  private fun call(
    method: String,
    path: String,
    owner: Owner,
    body: String? = null,
    capability: Boolean = false,
  ): HttpResponse<String> {
    val request =
      HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        .header("Authorization", "Bearer ${owner.token}")
    if (body != null) request.header("Content-Type", "application/json")
    if (capability) request.header("X-Gym-Capabilities", "calendar-plans")
    request.method(
      method,
      body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
    )
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun exercise() =
    mapOf(
      "name" to "Присед",
      "muscleGroup" to "LEGS",
      "type" to "STRENGTH",
      "isCustom" to false,
      "updatedAt" to 1,
      "needsMuscleMapReview" to false,
      "equipmentRequirementState" to "KNOWN",
      "muscles" to emptyList<Any>(),
      "equipmentIds" to emptyList<Any>(),
    )

  private fun gym(exercise: UUID) =
    mapOf(
      "name" to "Зал",
      "updatedAt" to 1,
      "inventoryConfigured" to false,
      "exerciseIds" to listOf(exercise.toString()),
      "equipmentIds" to emptyList<Any>(),
    )

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
