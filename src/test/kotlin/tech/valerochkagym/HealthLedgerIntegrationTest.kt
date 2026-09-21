package tech.valerochkagym

import java.net.URI
import java.net.http.*
import java.util.UUID
import java.util.concurrent.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.*
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.health.*
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.*

@Testcontainers
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = ["gym.coach-runs.enabled=false", "gym.calendar-jobs.enabled=false"],
)
class HealthLedgerIntegrationTest {
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
  @Autowired lateinit var ledger: HealthLedgerService
  @Autowired lateinit var disclosure: HealthAiDisclosureService
  @Autowired lateinit var auth: AuthService
  @LocalServerPort var port = 0
  private val client = HttpClient.newHttpClient()
  private val fixture
    get() = json.readTree(javaClass.getResourceAsStream("/manual-health-contract.json"))

  data class Owner(val id: UUID, val session: UUID, val token: String) {
    fun identity() = Identity(id, session, "$id@example.com")
  }

  @BeforeEach
  fun reset() {
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=0,active=false")
  }

  private fun owner(): Owner {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    db.update("INSERT INTO users(id,email,email_verified) VALUES(?,?,true)", id, "$id@example.com")
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES(?,?,'health',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      crypto.hash(token),
    )
    return Owner(id, session, token)
  }

  private fun call(
    a: Owner,
    path: String = "/health-ledger/operations",
    body: ByteArray? = null,
    capable: Boolean = true,
  ): HttpResponse<String> {
    val b =
      HttpRequest.newBuilder(URI("http://localhost:$port/v1$path"))
        .header("Authorization", "Bearer ${a.token}")
        .header("Content-Type", "application/json")
    if (capable) b.header("X-Gym-Capabilities", "health-ledger-v1")
    if (body == null) b.GET() else b.POST(HttpRequest.BodyPublishers.ofByteArray(body))
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun vector(i: Int = 0) =
    fixture["canonicalOperationRequest"]["vectors"][i]["utf8"].asString().toByteArray()

  private fun operation(
    versions: List<JsonNode>,
    heads: List<JsonNode>,
    id: String = UUID.randomUUID().toString(),
  ) =
    json.writeValueAsBytes(
      linkedMapOf("operationId" to id, "versions" to versions, "heads" to heads)
    )

  private fun version(
    id: String = UUID.randomUUID().toString(),
    logical: String = UUID.randomUUID().toString(),
    parent: String? = null,
    kind: String = "health_restriction",
    payload: Any? = mapOf("textOriginal" to "x", "confirmedAtEpochMs" to 1),
    state: String = "CONFIRMED",
  ) =
    json.valueToTree<JsonNode>(
      linkedMapOf(
        "versionId" to id,
        "logicalId" to logical,
        "parentVersionId" to parent,
        "kind" to kind,
        "state" to state,
        "enteredAtEpochMs" to 1,
        "payload" to payload,
      )
    )

  private fun head(v: JsonNode, base: Long = 0) =
    json.valueToTree<JsonNode>(
      mapOf(
        "logicalId" to v["logicalId"].asString(),
        "currentVersionId" to v["versionId"].asString(),
        "baseHeadRevision" to base,
      )
    )

  private fun count(table: String) =
    db.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

  private fun accept(a: Owner, v: JsonNode, base: Long = 0) =
    call(a, body = operation(listOf(v), listOf(head(v, base)))).also {
      assertEquals(200, it.statusCode(), it.body())
    }

  @Test
  fun `exact raw replay preserves result and changed bytes conflict`() {
    val a = owner()
    val raw = vector()
    val first = call(a, body = raw)
    assertEquals(200, first.statusCode(), first.body())
    assertEquals(first.body(), call(a, body = raw).body())
    assertEquals(
      "health_operation_reused",
      json.readTree(call(a, body = raw + byteArrayOf(32)).body())["code"].asString(),
    )
    assertEquals(1, count("health_versions"))
    assertEquals(2, count("health_events"))
    assertEquals(1, count("health_operations"))
    val old = json.readTree(raw)
    val equal = call(a, body = operation(old["versions"].toList(), old["heads"].toList()))
    assertEquals(
      "ALREADY_CURRENT",
      json.readTree(equal.body())["headResults"][0]["outcome"].asString(),
    )
    assertEquals(2, count("health_events"))
  }

  @Test
  fun `invalid raw capability and schema leave every health table empty`() {
    val a = owner()
    assertEquals(426, call(a, body = vector(), capable = false).statusCode())
    val invalid =
      listOf(
        byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + vector(),
        byteArrayOf(0xc3.toByte(), 0x28),
        "{} {}".toByteArray(),
        "{\"operationId\":1,\"operationId\":2}".toByteArray(),
        "{}".toByteArray(),
      )
    for (raw in invalid) assertEquals(400, call(a, body = raw).statusCode())
    assertEquals(0, count("health_owner_state"))
    assertEquals(0, count("health_events"))
  }

  @Test
  fun `collision parent order and missing head base reject atomically`() {
    val a = owner()
    val v = version()
    accept(a, v)
    val changed = json.readTree(json.writeValueAsBytes(v)) as tools.jackson.databind.node.ObjectNode
    changed.set(
      "payload",
      json.valueToTree<JsonNode>(mapOf("textOriginal" to "different", "confirmedAtEpochMs" to 1)),
    )
    assertEquals(
      "health_version_collision",
      json
        .readTree(call(a, body = operation(listOf(changed), emptyList())).body())["code"]
        .asString(),
    )
    val root = version()
    val child =
      version(logical = root["logicalId"].asString(), parent = root["versionId"].asString())
    assertEquals(400, call(a, body = operation(listOf(child, root), emptyList())).statusCode())
    assertEquals(400, call(a, body = operation(listOf(root), listOf(head(root, 1)))).statusCode())
    assertEquals(1, count("health_versions"))
    assertEquals(1, count("health_operations"))
  }

  @Test
  fun `two corrections keep both versions and one winner`() {
    val a = owner()
    val v = version()
    accept(a, v)
    val pool = Executors.newFixedThreadPool(2)
    val go = CountDownLatch(1)
    try {
      val tasks =
        (1..2).map {
          pool.submit<HttpResponse<String>> {
            go.await()
            val next =
              version(logical = v["logicalId"].asString(), parent = v["versionId"].asString())
            call(a, body = operation(listOf(next), listOf(head(next, 1))))
          }
        }
      go.countDown()
      val results = tasks.map { it.get(10, TimeUnit.SECONDS) }
      results.forEach { assertEquals(200, it.statusCode(), it.body()) }
      assertEquals(
        setOf("APPLIED", "STALE"),
        results.map { json.readTree(it.body())["headResults"][0]["outcome"].asString() }.toSet(),
      )
      assertEquals(3, count("health_versions"))
      assertEquals(5, count("health_events"))
      assertEquals(2, count("health_head_history"))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `frozen snapshot and changes retain head only history in revision order`() {
    val a = owner()
    val v = version()
    accept(a, v)
    val v2 = version(logical = v["logicalId"].asString(), parent = v["versionId"].asString())
    accept(a, v2, 1)
    val first = json.readTree(call(a, "/health-ledger/snapshot?limit=1").body())
    val back = call(a, body = operation(emptyList(), listOf(head(v, 2))))
    assertEquals(200, back.statusCode())
    var page = first
    val versions = mutableListOf<JsonNode>()
    val heads = mutableListOf<JsonNode>()
    while (true) {
      versions.addAll(page["versions"].toList())
      heads.addAll(page["heads"].toList())
      if (page["nextPageToken"].isNull) break
      page =
        json.readTree(
          call(a, "/health-ledger/snapshot?limit=1&pageToken=${page["nextPageToken"].asString()}")
            .body()
        )
    }
    assertEquals(2, versions.size)
    assertEquals(1, heads.size)
    assertEquals(v2["versionId"].asString(), heads.single()["currentVersionId"].asString())
    val cursor = page["commitCursor"].asString()
    val changes = json.readTree(call(a, "/health-ledger/changes?after=$cursor").body())
    assertEquals(0, changes["versions"].size())
    assertEquals(1, changes["heads"].size())
    assertEquals(5, changes["heads"][0]["healthRevision"].asLong())
    assertEquals(410, call(owner(), "/health-ledger/changes?after=$cursor").statusCode())
    assertEquals(410, call(a, "/health-ledger/snapshot?pageToken=$cursor").statusCode())
  }

  @Test
  fun `stored observation retries survive report tombstone but new selection fails`() {
    val a = owner()
    val report =
      version(
        kind = "health_report",
        payload =
          mapOf(
            "title" to "Report",
            "sourceText" to null,
            "observedAt" to "2030-01-01",
            "observedPrecision" to "DATE",
          ),
      )
    accept(a, report)
    val payload =
      json.readTree(json.writeValueAsBytes(fixture["validFixtures"][1]["value"]))
        as tools.jackson.databind.node.ObjectNode
    payload.put("reportLogicalId", report["logicalId"].asString())
    payload.put("enteredAtEpochMs", 1)
    val obs = version(kind = "health_observation", payload = payload)
    val raw = operation(listOf(obs), listOf(head(obs)))
    val saved = call(a, body = raw)
    assertEquals(200, saved.statusCode(), saved.body())
    val tomb =
      version(
        logical = report["logicalId"].asString(),
        parent = report["versionId"].asString(),
        kind = "health_report",
        payload = null,
        state = "TOMBSTONE",
      )
    accept(a, tomb, 1)
    assertEquals(saved.body(), call(a, body = raw).body())
    assertEquals(200, call(a, body = operation(listOf(obs), listOf(head(obs, 1)))).statusCode())
    val correction =
      version(
        logical = obs["logicalId"].asString(),
        parent = obs["versionId"].asString(),
        kind = "health_observation",
        payload = payload,
      )
    assertEquals(400, call(a, body = operation(listOf(correction), emptyList())).statusCode())
    assertEquals(3, count("health_versions"))
  }

  @Test
  fun `disclosure exact replay and CAS are independent of ledger revisions`() {
    val a = owner()
    assertEquals(0, json.readTree(call(a, "/health-ai-disclosure").body())["revision"].asLong())
    val raw = fixture["canonicalAiDisclosureRequest"]["vectors"][0]["utf8"].asString().toByteArray()
    val enabled = call(a, "/health-ai-disclosure", raw)
    assertEquals(200, enabled.statusCode(), enabled.body())
    disclosure.requireEnabled(a.identity(), 1)
    assertEquals(enabled.body(), call(a, "/health-ai-disclosure", raw).body())
    assertEquals(409, call(a, "/health-ai-disclosure", raw + byteArrayOf(32)).statusCode())
    val disabled =
      fixture["canonicalAiDisclosureRequest"]["vectors"][1]["utf8"].asString().toByteArray()
    assertEquals(200, call(a, "/health-ai-disclosure", disabled).statusCode())
    assertThrows(ApiException::class.java) { disclosure.requireEnabled(a.identity(), 1) }
    assertEquals(enabled.body(), call(a, "/health-ai-disclosure", raw).body())
    assertEquals(0, count("health_events"))
  }

  @Test
  fun `invalid delete code preserves owner health and unrelated records`() {
    val a = owner()
    val b = owner()
    accept(a, version())
    accept(b, version())
    assertThrows(ApiException::class.java) { auth.delete(a.identity(), "wrong") }
    assertEquals(2, count("health_versions"))
    val noHealth = owner()
    assertThrows(ApiException::class.java) { auth.delete(noHealth.identity(), "wrong") }
    assertEquals(2, count("health_owner_state"))
  }

  @Autowired lateinit var rows: tech.valerochkagym.repository.health.HealthLedgerRepositories
  @Autowired lateinit var tx: org.springframework.transaction.support.TransactionTemplate
  @Autowired lateinit var validator: HealthLedgerValidator
  @Autowired lateinit var rawReader: HealthRawBodyReader
  @Autowired lateinit var cursors: HealthCursorCodec
  @Autowired lateinit var limits: tech.valerochkagym.security.RateLimiter
  @Autowired lateinit var catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository

  @Test
  fun `tiny quotas reject before allocation and exact replay costs zero`() {
    val a = owner()
    val v = version()
    val raw = operation(listOf(v), listOf(head(v)))
    val tiny = HealthLedgerService(rows, tx, validator, rawReader, json, cursors, 1, 1)
    assertEquals(
      "health_account_limit",
      assertThrows(ApiException::class.java) { tiny.operation(a.identity(), raw) }.code,
    )
    assertEquals(0, count("health_owner_state"))
    val first = ledger.operation(a.identity(), raw)
    assertArrayEquals(first, tiny.operation(a.identity(), raw))
    val countLimited =
      HealthLedgerService(rows, tx, validator, rawReader, json, cursors, 209715200, 1)
    assertEquals(
      "health_account_limit",
      assertThrows(ApiException::class.java) {
          countLimited.operation(a.identity(), operation(listOf(version()), emptyList()))
        }
        .code,
    )
    assertEquals(1, count("health_versions"))
    assertEquals(2, count("health_events"))
    assertEquals(1, count("health_operations"))
  }

  @Test
  fun `filter rejects absent stale disabled consent and capability with zero body reads including async`() {
    val a = owner()
    val filter = tech.valerochkagym.security.BearerFilter(auth, disclosure, limits, catalog)
    fun check(
      path: String,
      revision: String?,
      capability: Boolean,
      dispatch: jakarta.servlet.DispatcherType,
      expected: Int,
    ) {
      var reads = 0
      var chains = 0
      val request =
        object : org.springframework.mock.web.MockHttpServletRequest("POST", path) {
          override fun getInputStream(): jakarta.servlet.ServletInputStream =
            object : jakarta.servlet.ServletInputStream() {
              override fun read(): Int {
                reads++
                return -1
              }

              override fun isFinished() = false

              override fun isReady() = true

              override fun setReadListener(listener: jakarta.servlet.ReadListener) {}
            }
        }
      request.setDispatcherType(dispatch)
      request.addHeader("Authorization", "Bearer ${a.token}")
      if (revision != null) request.addHeader("X-Health-AI-Disclosure-Revision", revision)
      if (capability) request.addHeader("X-Gym-Capabilities", "health-ledger-v1")
      val response = org.springframework.mock.web.MockHttpServletResponse()
      filter.doFilter(request, response, jakarta.servlet.FilterChain { _, _ -> chains++ })
      assertEquals(expected, response.status)
      assertEquals(0, reads)
      assertEquals(0, chains)
    }
    for (dispatch in
      listOf(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC)) {
      check("/v1/ai/inbody-drafts", null, true, dispatch, 403)
      check("/v1/health-ledger/operations", null, false, dispatch, 426)
    }
    val enable =
      fixture["canonicalAiDisclosureRequest"]["vectors"][0]["utf8"].asString().toByteArray()
    disclosure.mutate(a.identity(), enable)
    check("/v1/ai/inbody-drafts", "2", true, jakarta.servlet.DispatcherType.REQUEST, 403)
    disclosure.mutate(
      a.identity(),
      fixture["canonicalAiDisclosureRequest"]["vectors"][1]["utf8"].asString().toByteArray(),
    )
    check("/v1/ai/inbody-drafts", "2", true, jakarta.servlet.DispatcherType.ASYNC, 403)
    db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", a.session)
    check("/v1/ai/inbody-drafts", "2", true, jakarta.servlet.DispatcherType.ASYNC, 401)
  }

  @Test
  fun `confirmed delete removes only owner ledger including self referenced versions`() {
    val a = owner()
    val b = owner()
    val v = version()
    accept(a, v)
    accept(a, version(logical = v["logicalId"].asString(), parent = v["versionId"].asString()), 1)
    accept(b, version())
    disclosure.mutate(
      a.identity(),
      fixture["canonicalAiDisclosureRequest"]["vectors"][0]["utf8"].asString().toByteArray(),
    )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'measurement',?,1,false,'{\"measuredAt\":1,\"weightKg\":70}'::jsonb)",
      b.id,
      UUID.randomUUID(),
    )
    val code = "12345678"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${a.id}@example.com",
      crypto.hash("${a.id}@example.com:delete:$code"),
    )
    auth.delete(a.identity(), code)
    assertEquals(1, count("health_versions"))
    assertEquals(1, count("health_owner_state"))
    assertEquals(0, count("health_ai_disclosures"))
    assertEquals(1, count("records"))
    val page = call(b, "/health-ledger/snapshot")
    assertEquals(200, page.statusCode())
    assertFalse(page.body().contains("measurement"))
  }

  @Test
  fun `revoked session while blocked cannot commit health mutation`() {
    val a = owner()
    accept(a, version())
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val holder =
        pool.submit {
          tx.executeWithoutResult {
            rows.lock(a.id)
            locked.countDown()
            release.await(5, TimeUnit.SECONDS)
          }
        }
      assertTrue(locked.await(3, TimeUnit.SECONDS))
      val action =
        pool.submit<HttpResponse<String>> {
          call(a, body = operation(listOf(version()), emptyList()))
        }
      db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", a.session)
      release.countDown()
      holder.get(5, TimeUnit.SECONDS)
      assertEquals(401, action.get(5, TimeUnit.SECONDS).statusCode())
      assertEquals(1, count("health_versions"))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `confirmed deletion and health mutation serialize without recreating owner state`() {
    val a = owner()
    val b = owner()
    accept(a, version())
    accept(b, version())
    val code = "12345678"
    db.update(
      "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
      "${a.id}@example.com",
      crypto.hash("${a.id}@example.com:delete:$code"),
    )
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val deletion =
        pool.submit {
          tx.executeWithoutResult {
            catalog.readLock()
            rows.lock(a.id)
            locked.countDown()
            release.await(5, TimeUnit.SECONDS)
            auth.delete(a.identity(), code)
          }
        }
      assertTrue(locked.await(3, TimeUnit.SECONDS))
      val attempted = CountDownLatch(1)
      val mutation =
        pool.submit<Any> {
          attempted.countDown()
          try {
            ledger.operation(a.identity(), operation(listOf(version()), emptyList()))
          } catch (e: ApiException) {
            e.code
          }
        }
      assertTrue(attempted.await(3, TimeUnit.SECONDS))
      release.countDown()
      deletion.get(8, TimeUnit.SECONDS)
      assertEquals("unauthorized", mutation.get(8, TimeUnit.SECONDS))
      assertEquals(1, count("health_owner_state"))
      assertEquals(1, count("health_versions"))
      assertEquals(1, count("users"))
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `cross owner references head target substitution and cyclic parents are atomic`() {
    val a = owner()
    val b = owner()
    val root = version()
    accept(a, root)
    val foreignChild =
      version(logical = root["logicalId"].asString(), parent = root["versionId"].asString())
    assertEquals(400, call(b, body = operation(listOf(foreignChild), emptyList())).statusCode())
    val other = version()
    accept(a, other)
    val substituted =
      json.valueToTree<JsonNode>(
        mapOf(
          "logicalId" to root["logicalId"].asString(),
          "currentVersionId" to other["versionId"].asString(),
          "baseHeadRevision" to 1,
        )
      )
    assertEquals(400, call(a, body = operation(emptyList(), listOf(substituted))).statusCode())
    val x = UUID.randomUUID().toString()
    val y = UUID.randomUUID().toString()
    val logical = UUID.randomUUID().toString()
    assertEquals(
      400,
      call(
          a,
          body =
            operation(
              listOf(
                version(id = x, logical = logical, parent = y),
                version(id = y, logical = logical, parent = x),
              ),
              emptyList(),
            ),
        )
        .statusCode(),
    )
    assertEquals(2, count("health_versions"))
    assertEquals(2, count("health_operations"))
    assertEquals(1, count("health_owner_state"))
  }

  @Test
  fun `concurrent additions cannot exceed a tiny version quota`() {
    val a = owner()
    accept(a, version())
    val limited = HealthLedgerService(rows, tx, validator, rawReader, json, cursors, 209715200, 2)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val requests =
        (1..2).map {
          pool.submit<String> {
            go.await()
            try {
              limited.operation(a.identity(), operation(listOf(version()), emptyList()))
              "accepted"
            } catch (e: ApiException) {
              e.code
            }
          }
        }
      go.countDown()
      assertEquals(
        setOf("accepted", "health_account_limit"),
        requests.map { it.get(8, TimeUnit.SECONDS) }.toSet(),
      )
      assertEquals(2, count("health_versions"))
      assertEquals(3, count("health_events"))
      assertEquals(2, count("health_operations"))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `matching head selection after report tombstone rejects while stale selection is retained`() {
    val a = owner()
    val report =
      version(
        kind = "health_report",
        payload =
          mapOf(
            "title" to "R",
            "sourceText" to null,
            "observedAt" to "2030-01-01",
            "observedPrecision" to "DATE",
          ),
      )
    accept(a, report)
    val payload =
      json.readTree(json.writeValueAsBytes(fixture["validFixtures"][1]["value"]))
        as tools.jackson.databind.node.ObjectNode
    payload.put("reportLogicalId", report["logicalId"].asString())
    payload.put("enteredAtEpochMs", 1)
    val first = version(kind = "health_observation", payload = payload)
    accept(a, first)
    val next =
      version(
        kind = "health_observation",
        payload = payload,
        logical = first["logicalId"].asString(),
        parent = first["versionId"].asString(),
      )
    accept(a, next, 1)
    accept(
      a,
      version(
        kind = "health_report",
        logical = report["logicalId"].asString(),
        parent = report["versionId"].asString(),
        payload = null,
        state = "TOMBSTONE",
      ),
      1,
    )
    val before = count("health_events")
    assertEquals(400, call(a, body = operation(emptyList(), listOf(head(first, 2)))).statusCode())
    val stale = call(a, body = operation(listOf(first), listOf(head(first, 1))))
    assertEquals(200, stale.statusCode(), stale.body())
    assertEquals("STALE", json.readTree(stale.body())["headResults"][0]["outcome"].asString())
    assertEquals(before, count("health_events"))
  }

  @Autowired lateinit var sessions: tech.valerochkagym.repository.auth.SessionRepository
  @Autowired lateinit var clock: java.time.Clock
  @Autowired lateinit var healthCleanup: tech.valerochkagym.repository.health.HealthAccountCleanup
  @Autowired lateinit var users: tech.valerochkagym.repository.auth.UserRepository

  @Test
  fun `nullable original strings enforce empty and UTF16 size boundaries without mutation`() {
    val a = owner()
    val report =
      version(
        kind = "health_report",
        payload =
          mapOf(
            "title" to "R",
            "sourceText" to null,
            "observedAt" to "2030-01-01",
            "observedPrecision" to "DATE",
          ),
      )
    accept(a, report)
    for (key in listOf("unitOriginal", "methodOriginal", "specimenOriginal", "sourceOriginal")) {
      for (size in listOf(0, 1, 1000, 1001)) {
        val payload =
          json.readTree(json.writeValueAsBytes(fixture["validFixtures"][1]["value"]))
            as tools.jackson.databind.node.ObjectNode
        payload.put("reportLogicalId", report["logicalId"].asString())
        payload.put("enteredAtEpochMs", 1)
        payload.put(key, "x".repeat(size))
        val before =
          listOf(count("health_versions"), count("health_events"), count("health_operations"))
        val response =
          call(
            a,
            body =
              operation(
                listOf(version(kind = "health_observation", payload = payload)),
                emptyList(),
              ),
          )
        if (size in 1..1000) assertEquals(200, response.statusCode(), response.body())
        else {
          assertEquals(400, response.statusCode())
          assertEquals("invalid_request", json.readTree(response.body())["code"].asString())
          assertEquals(
            before,
            listOf(count("health_versions"), count("health_events"), count("health_operations")),
          )
        }
      }
    }
  }

  @Test
  fun `invalid page query parameters return exact ApiError envelope`() {
    val a = owner()
    for (path in
      listOf(
        "/health-ledger/snapshot?limit=wrong",
        "/health-ledger/snapshot?limit=2147483648",
        "/health-ledger/changes",
        "/health-ledger/changes?limit=no",
      )) {
      val response = call(a, path)
      assertEquals(400, response.statusCode(), response.body())
      val error = json.readTree(response.body())
      assertEquals(setOf("code", "message"), error.propertyNames().toSet())
      assertEquals("invalid_request", error["code"].asString())
    }
    assertEquals(0, count("health_owner_state"))
  }

  @Test
  fun `first health and disclosure writes serialize with absent state confirmed deletion`() {
    for (consent in listOf(false, true)) {
      val a = owner()
      val code = "12345678"
      db.update(
        "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
        "${a.id}@example.com",
        crypto.hash("${a.id}@example.com:delete:$code"),
      )
      val locked = CountDownLatch(1)
      val release = CountDownLatch(1)
      val pool = Executors.newFixedThreadPool(2)
      try {
        val deleting =
          pool.submit {
            tx.executeWithoutResult {
              catalog.readLock()
              healthCleanup.preflight(a.id)
              users.lock(a.id)
              assertEquals(0, count("health_owner_state"))
              locked.countDown()
              release.await(5, TimeUnit.SECONDS)
              auth.delete(a.identity(), code)
            }
          }
        assertTrue(locked.await(3, TimeUnit.SECONDS))
        val started = CountDownLatch(1)
        val first =
          pool.submit<String> {
            started.countDown()
            try {
              if (consent)
                disclosure.mutate(
                  a.identity(),
                  fixture["canonicalAiDisclosureRequest"]["vectors"][0]["utf8"]
                    .asString()
                    .toByteArray(),
                )
              else ledger.operation(a.identity(), operation(listOf(version()), emptyList()))
              "accepted"
            } catch (e: ApiException) {
              e.code
            }
          }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun waiting() =
          db.queryForObject(
            "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",
            Long::class.java,
          )!!
        while (waiting() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1L, waiting()) // First write is actually waiting before deletion commits.
        release.countDown()
        deleting.get(8, TimeUnit.SECONDS)
        assertEquals("unauthorized", first.get(8, TimeUnit.SECONDS))
        assertEquals(0, count("health_owner_state"))
        assertEquals(0, count("health_operations"))
        assertEquals(0, count("health_disclosure_operations"))
      } finally {
        release.countDown()
        pool.shutdownNow()
      }
    }
  }

  @Test
  fun `page read releases owner locks and discards data after deletion or session revoke`() {
    for (ending in listOf("write", "delete", "revoke")) {
      val a = owner()
      accept(a, version())
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val blockedJdbc =
        object : JdbcTemplate(db.dataSource!!) {
          override fun queryForList(
            sql: String,
            vararg args: Any?,
          ): MutableList<MutableMap<String, Any?>> {
            val result = super.queryForList(sql, *args)
            if (sql.startsWith("SELECT e.body,e.event_type")) {
              assertTrue(result.isNotEmpty())
              entered.countDown()
              assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            return result
          }
        }
      val reader =
        HealthLedgerService(
          tech.valerochkagym.repository.health.HealthLedgerRepositories(
            blockedJdbc,
            sessions,
            clock,
          ),
          tx,
          validator,
          rawReader,
          json,
          cursors,
          209715200,
          100000,
        )
      val pool = Executors.newFixedThreadPool(2)
      try {
        val pending =
          pool.submit<Any> {
            try {
              reader.page(a.identity(), "snapshot", null, null, 500)
            } catch (e: ApiException) {
              e.code
            }
          }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val concurrent =
          pool.submit {
            when (ending) {
              "write" -> ledger.operation(a.identity(), operation(listOf(version()), emptyList()))
              "revoke" -> auth.logout(a.identity())
              else -> {
                val code = "12345678"
                db.update(
                  "INSERT INTO email_challenges(email,purpose,code_hash,expires_at,attempts) VALUES (?,'delete',?,TIMESTAMPTZ '2100-01-01',0)",
                  "${a.id}@example.com",
                  crypto.hash("${a.id}@example.com:delete:$code"),
                )
                auth.delete(a.identity(), code)
              }
            }
          }
        concurrent.get(3, TimeUnit.SECONDS) // Must finish while the event query remains paused.
        release.countDown()
        val result = pending.get(5, TimeUnit.SECONDS)
        if (ending == "write") {
          val page = json.readTree(result as ByteArray)
          assertEquals(1, page["versions"].size())
          assertEquals(1, page["heads"].size())
        } else assertEquals("unauthorized", result)
      } finally {
        release.countDown()
        pool.shutdownNow()
      }
    }
  }
}
