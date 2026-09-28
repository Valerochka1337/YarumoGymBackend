package tech.valerochkagym

import java.sql.Timestamp
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.data.TrainingProposalController
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.ObjectMapper

@Testcontainers
@SpringBootTest(
  classes = [Application::class, CalendarAiCaptureIntegrationTest.Fakes::class],
  properties = ["gym.calendar-jobs.enabled=false"],
)
class DeterministicPlannerIntegrationTest {
  companion object {
    private const val now = 1_805_005_800_000L
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

  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var jobs: CalendarDraftJobService
  @Autowired lateinit var runtime: DeterministicPlannerRuntime
  @Autowired lateinit var transaction: org.springframework.transaction.support.TransactionTemplate
  @Autowired lateinit var config: PlannerConfigurationService
  @Autowired lateinit var mappings: PlannerMovementMappingService
  @Autowired lateinit var provider: CalendarAiCaptureIntegrationTest.FakeProvider
  @Autowired lateinit var clock: CalendarAiCaptureIntegrationTest.MutableCalendarClock
  @Autowired lateinit var hooks: CalendarAiCaptureIntegrationTest.BarrierHooks
  @Autowired lateinit var proposals: TrainingProposalController
  @Autowired lateinit var actions: AiActionService

  @BeforeEach
  @AfterEach
  fun reset() {
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=9,active=false")
    db.update("DELETE FROM planner_configuration")
    clock.currentTime = now
    provider.available = false
    provider.calls = 0
    hooks.afterCapture = null
    hooks.afterReserve = null
    hooks.finalLock = null
    hooks.proposalInsert = null
    hooks.failProposalInsert = false
  }

  @Test
  fun `persisted execution snapshot roundtrips and publishes through runtime`() {
    val owner = owner()
    setupConfig()
    val exercise = exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    val stored =
      db.queryForObject(
        "SELECT execution_snapshot::text FROM calendar_draft_jobs WHERE owner_id=? AND request_id=?",
        String::class.java,
        owner.userId,
        requestId(raw),
      )!!
    val snapshot = json.readValue(stored, PlannerExecutionSnapshot::class.java)
    val computed = runtime.compute(snapshot)
    assertInstanceOf(DeterministicPlannerResult.Ready::class.java, computed)
    val plan = (computed as DeterministicPlannerResult.Ready).plan
    val draft = runtime.draft(snapshot, plan)
    assertEquals(exercise.toString(), draft.exercises.single().exerciseId)
    val published = transaction.execute { runtime.publish(owner, snapshot, plan) }!!
    assertEquals("RULE_BASED", published.proposal.source)
    assertEquals(draft, published.proposal.snapshot.draft)
    assertEquals(1, count("planner_explanations"))
    assertEquals(0, provider.calls)
  }

  @ParameterizedTest
  @ValueSource(strings = ["STRENGTH", "MUSCLE_GAIN", "FAT_LOSS", "GENERAL_FITNESS", "ENDURANCE"])
  fun `all goals publish RULE_BASED without an available provider`(goal: String) {
    val owner = owner(goal)
    setupConfig()
    val exercise = exercise(owner)
    val raw = request()
    assertEquals("QUEUED", jobs.submitV2(owner, raw).state)
    jobs.runNext()
    val ready = jobs.statusV2(owner, requestId(raw))
    assertEquals("READY", ready.state, ready.errorCode)
    assertEquals("RULE_BASED", ready.result!!.proposal.source)
    assertEquals(
      exercise.toString(),
      ready.result.proposal.snapshot.draft.exercises.single().exerciseId,
    )
    assertEquals(0, provider.calls)
    assertEquals(ready, jobs.submitV2(owner, raw))
    assertArrayEquals(
      raw,
      db.queryForObject(
        "SELECT raw_request FROM calendar_draft_jobs WHERE owner_id=? AND request_id=?",
        ByteArray::class.java,
        owner.userId,
        requestId(raw),
      ),
    )
  }

  @Test
  fun `configuration changes after admission do not invalidate captured execution`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    config.save(config.snapshot().copy(weightStepKg = 5.0))
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, requestId(raw)).state)
  }

  @Test
  fun `byte binding cannot cross owners protocols or refinement routes`() {
    val owner = owner()
    val other = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { jobs.submitV2(owner, raw + byteArrayOf(32)) }.code,
    )
    assertEquals(404, assertThrows<ApiException> { jobs.statusV2(other, requestId(raw)) }.status)
    assertEquals(404, assertThrows<ApiException> { jobs.status(owner, requestId(raw)) }.status)
  }

  @Test
  fun `unknown duplicate and coercible scalar inputs are rejected before persistence`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request().toString(Charsets.UTF_8)
    listOf(
        raw.replace("\"variant\":0", "\"variant\":0,\"variant\":0"),
        raw.dropLast(1) + ",\"preferences\":\"text\"}",
        raw.replace("\"availableDurationMinutes\":45", "\"availableDurationMinutes\":\"45\""),
      )
      .forEach { assertThrows<ApiException> { jobs.submitV2(owner, it.toByteArray()) } }
    assertEquals(0, count("calendar_draft_jobs"))
  }

  @Test
  fun `cancellation and lost lease never publish a partial proposal`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.cancel(owner, requestId(raw), 2)
    jobs.runNext()
    assertEquals("SUPERSEDED", jobs.statusV2(owner, requestId(raw)).state)
    assertEquals(0, count("training_proposals"))
  }

  @Test
  fun `expired SQL lease resumes the stored snapshot and ready result is not duplicated`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    db.update(
      "UPDATE calendar_draft_jobs SET state='RUNNING',lease_token=?,lease_until=?,executions=1 WHERE request_id=?",
      UUID.randomUUID(),
      Timestamp(now - 1),
      requestId(raw),
    )
    jobs.runNext()
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, requestId(raw)).state)
    assertEquals(1, count("training_proposals"))
  }

  @Test
  fun `changed owner catalog and session each fence queued execution`() {
    for (kind in listOf("head", "catalog", "session")) {
      reset()
      val owner = owner()
      setupConfig()
      exercise(owner)
      val raw = request()
      jobs.submitV2(owner, raw)
      when (kind) {
        "head" -> db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
        "catalog" -> db.update("UPDATE catalog_state SET revision=10")
        else -> db.update("UPDATE sessions SET revoked_at=now() WHERE id=?", owner.sessionId)
      }
      jobs.runNext()
      assertEquals(0, count("training_proposals"))
      assertTrue(
        db.queryForObject(
          "SELECT state FROM calendar_draft_jobs WHERE request_id=?",
          String::class.java,
          requestId(raw),
        ) in setOf("STALE", "FAILED")
      )
    }
  }

  @Test
  fun `unmapped and NEVER exercises cannot produce an automatic plan`() {
    val owner = owner()
    setupConfig()
    val exercise = exercise(owner)
    mappings.delete(owner, exercise)
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.runNext()
    val result = jobs.statusV2(owner, requestId(raw))
    assertEquals("IMPOSSIBLE", result.state)
    assertEquals("NO_FEASIBLE_PLAN", result.errorCode)
    assertNull(result.result)
  }

  @Test
  fun `owner mappings cannot list read write or delete another owners exercise`() {
    val first = owner()
    val second = owner()
    val exercise = exercise(first)
    val value = mappings.get(first, exercise)
    assertTrue(mappings.list(second).items.isEmpty())
    assertEquals(404, assertThrows<ApiException> { mappings.get(second, exercise) }.status)
    assertEquals(404, assertThrows<ApiException> { mappings.put(second, exercise, value) }.status)
    assertEquals(404, assertThrows<ApiException> { mappings.delete(second, exercise) }.status)
    assertEquals(value, mappings.get(first, exercise))
  }

  @Test
  fun `refinement makes new proposal and preserves manual name date rest and sets on untouched rows`() {
    val owner = owner()
    setupConfig(twoSlots = true)
    exercise(owner)
    exercise(owner, "CORE")
    val replacement = exercise(owner, "CORE")
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.runNext()
    val original = jobs.statusV2(owner, requestId(raw)).result!!.proposal
    val explanation =
      db.queryForObject(
        "SELECT payload::text FROM planner_explanations WHERE proposal_id=?",
        String::class.java,
        original.proposalId,
      )!!
    val target = json.readTree(explanation)["slotSelections"].toList().last()
    val manual =
      original.snapshot.draft.copy(
        name = "Ручное имя",
        exercises =
          original.snapshot.draft.exercises.mapIndexed { index, row ->
            if (index == 0)
              row.copy(
                restSeconds = 17,
                plannedSets = listOf(PlannedSet(11.5, 7, null, null, null)),
              )
            else row
          },
      )
    val request =
      PlannerV2RefinementRequest(
        UUID.randomUUID(),
        1,
        17,
        9,
        1,
        manual,
        listOf(
          PlannerV2Change(
            "REPLACE",
            target["slotId"].asString(),
            target["selectionId"].asString(),
            replacement,
          )
        ),
      )
    val bytes = json.writeValueAsBytes(request)
    jobs.submitV2Refinement(owner, original.proposalId, bytes)
    jobs.runNext()
    val refined = jobs.statusV2(owner, request.requestId).result!!.proposal
    assertNotEquals(original.proposalId, refined.proposalId)
    assertEquals("Ручное имя", refined.snapshot.draft.name)
    assertEquals(manual.exercises.first(), refined.snapshot.draft.exercises.first())
    assertEquals(original, proposals.detail(owner, original.proposalId, v2()))
    val refinedExplanation =
      json.readTree(
        db.queryForObject(
          "SELECT payload::text FROM planner_explanations WHERE proposal_id=?",
          String::class.java,
          refined.proposalId,
        )!!
      )
    assertTrue(
      refinedExplanation["eligibleExerciseCount"].asInt() >= refined.snapshot.draft.exercises.size
    )
    assertEquals("CONSTRAINTS", refinedExplanation["shortfallReason"].asString())
  }

  @Test
  fun `legacy proposal projections hide RULE_BASED while V2 reads complete saved proposal`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.runNext()
    val saved = jobs.statusV2(owner, requestId(raw)).result!!.proposal
    assertEquals(
      404,
      assertThrows<ApiException> {
          proposals.detail(owner, saved.proposalId, MockHttpServletRequest())
        }
        .status,
    )
    assertTrue(proposals.list(owner, 20, null, MockHttpServletRequest()).items.isEmpty())
    assertEquals(saved, proposals.detail(owner, saved.proposalId, v2()))
  }

  @Test
  fun `legacy create terminalizes without consulting unavailable provider and retains request binding`() {
    val owner = owner()
    val raw = legacyRequest()
    assertEquals(
      "ai_invalid_request",
      assertThrows<ApiException> { actions.calendar(owner, raw) }.code,
    )
    assertEquals(
      "ai_invalid_request",
      assertThrows<ApiException> { actions.calendar(owner, raw) }.code,
    )
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { actions.calendar(owner, raw + byteArrayOf(32)) }.code,
    )
    assertEquals("FAILED", jobs.submit(owner, raw).state)
    jobs.runNext()
    assertEquals(0, provider.calls)
    assertEquals(0, count("training_proposals"))
  }

  @Test
  fun `standard general fitness configuration publishes actual timed core work`() {
    val owner = owner()
    exercise(owner, "SQUAT")
    exercise(owner, "HORIZONTAL_PUSH")
    exercise(owner, "HORIZONTAL_PULL")
    val timed = exercise(owner, "CORE", "TIMED")
    exercise(owner, "CARDIO", "CARDIO")
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.runNext()
    val ready = jobs.statusV2(owner, requestId(raw))
    assertEquals("READY", ready.state, ready.errorCode)
    val sets =
      ready.result!!
        .proposal
        .snapshot
        .draft
        .exercises
        .single { it.exerciseId == timed.toString() }
        .plannedSets
    assertEquals(3, sets.size)
    assertTrue(sets.all { it.durationSec in setOf(30, 45, 60) })
    assertTrue(sets.all { it.reps == null && it.weightKg == null })
  }

  @Test
  fun `new legacy delivery retains exact spaced bytes while unavailable historical bytes remain null`() {
    val owner = owner()
    val raw =
      ("  " + legacyRequest().toString(Charsets.UTF_8).replace(",", ", \n") + "  ").toByteArray()
    jobs.submit(owner, raw)
    assertArrayEquals(
      raw,
      db.queryForObject(
        "SELECT raw_request FROM calendar_draft_jobs WHERE request_id=?",
        ByteArray::class.java,
        requestId(raw),
      ),
    )
    assertEquals(
      1,
      db.queryForObject(
        "SELECT protocol FROM calendar_draft_jobs WHERE request_id=?",
        Int::class.java,
        requestId(raw),
      ),
    )
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { jobs.submit(owner, raw + byteArrayOf(32)) }.code,
    )
    db.update("UPDATE calendar_draft_jobs SET raw_request=null WHERE request_id=?", requestId(raw))
    jobs.status(owner, requestId(raw))
    jobs.submit(owner, raw)
    assertNull(
      db.queryForObject(
        "SELECT raw_request FROM calendar_draft_jobs WHERE request_id=?",
        ByteArray::class.java,
        requestId(raw),
      )
    )
  }

  @Test
  fun `migration widens the actual legacy source constraint without rewriting saved bytes`() {
    val owner = owner()
    val raw = legacyRequest()
    jobs.submit(owner, raw)
    val before =
      db.queryForMap(
        "SELECT request_digest,intent::text,raw_request,state FROM calendar_draft_jobs WHERE request_id=?",
        requestId(raw),
      )
    val legacyId = UUID.randomUUID()
    val routineId = UUID.randomUUID()
    val calendarId = UUID.randomUUID()
    val approvalBytes = "  { \"version\": 1, \"draft\": {} }  ".toByteArray()
    db.update(
      "INSERT INTO training_proposals(id,recipient_id,source,status,current_version,created_at,updated_at,expires_at) VALUES (?,?,'AI','APPROVED',1,?,?,?)",
      legacyId,
      owner.userId,
      Timestamp(now),
      Timestamp(now),
      Timestamp(now + 86_400_000),
    )
    db.update(
      "INSERT INTO training_proposal_versions(proposal_id,version,draft,owner_revision,catalog_revision,created_at) VALUES (?,1,'{}',17,9,?)",
      legacyId,
      Timestamp(now),
    )
    db.update(
      "INSERT INTO training_proposal_receipts(proposal_id,version,routine_id,calendar_plan_id,revision,approved_at) VALUES (?,1,?,?,18,?)",
      legacyId,
      routineId,
      calendarId,
      Timestamp(now),
    )
    db.update(
      "INSERT INTO training_proposal_operations(recipient_id,operation_id,proposal_id,version,request_sha256,raw_request,created_at) VALUES (?,?,?,1,?,?,?)",
      owner.userId,
      UUID.randomUUID(),
      legacyId,
      DeterministicPlannerRuntime.fingerprint(approvalBytes),
      approvalBytes,
      Timestamp(now),
    )
    fun legacyRow() = db.queryForMap("SELECT * FROM training_proposals WHERE id=?", legacyId)
    fun legacyReceipt() =
      db.queryForMap("SELECT * FROM training_proposal_receipts WHERE proposal_id=?", legacyId)
    val oldProposal = legacyRow()
    val oldReceipt = legacyReceipt()
    // Reproduce schema 033's actual constraint name, then execute 034's exact source migration.
    db.execute("ALTER TABLE training_proposals DROP CONSTRAINT training_proposals_source_check")
    db.execute("ALTER TABLE training_proposals ALTER COLUMN source TYPE VARCHAR(8)")
    db.execute(
      "ALTER TABLE training_proposals ADD CONSTRAINT training_proposals_source_ai CHECK (source='AI')"
    )
    val migration =
      javaClass
        .getResourceAsStream("/db/changelog/034-deterministic-planner.sql")!!
        .bufferedReader()
        .readText()
    migration
      .substringBefore("ALTER TABLE calendar_draft_jobs")
      .split(';')
      .filter { it.contains("ALTER TABLE") }
      .forEach(db::execute)
    val after =
      db.queryForMap(
        "SELECT request_digest,intent::text,raw_request,state FROM calendar_draft_jobs WHERE request_id=?",
        requestId(raw),
      )
    assertEquals(oldProposal, legacyRow())
    assertEquals(oldReceipt, legacyReceipt())
    assertArrayEquals(
      approvalBytes,
      db.queryForObject(
        "SELECT raw_request FROM training_proposal_operations WHERE proposal_id=?",
        ByteArray::class.java,
        legacyId,
      ),
    )
    assertEquals(
      16,
      db.queryForObject(
        "SELECT character_maximum_length FROM information_schema.columns WHERE table_name='training_proposals' AND column_name='source'",
        Int::class.java,
      ),
    )
    assertEquals(before["request_digest"], after["request_digest"])
    assertEquals(before["intent"], after["intent"])
    assertEquals(before["state"], after["state"])
    assertArrayEquals(before["raw_request"] as ByteArray, after["raw_request"] as ByteArray)
    setupConfig()
    exercise(owner)
    val next = request()
    jobs.submitV2(owner, next)
    jobs.runNext()
    assertEquals("RULE_BASED", jobs.statusV2(owner, requestId(next)).result!!.proposal.source)
  }

  @Test
  fun `persisted shortfall explanation roundtrips frozen Android wire keys and enums`() {
    val owner = owner()
    setupConfig()
    exercise(owner)
    val raw = request()
    jobs.submitV2(owner, raw)
    jobs.runNext()
    val ready = jobs.statusV2(owner, requestId(raw)).result!!
    val persisted =
      db.queryForObject(
        "SELECT payload::text FROM planner_explanations WHERE proposal_id=?",
        String::class.java,
        ready.proposal.proposalId,
      )!!
    val roundtrip = json.readTree(json.writeValueAsBytes(json.readTree(persisted)))
    val contract =
      javaClass
        .getResourceAsStream("/deterministic-planner-v2-shortfall-contract.json")!!
        .use(json::readTree)
    assertEquals(
      contract.properties().map { it.key }.toSet(),
      roundtrip.properties().map { it.key }.toSet(),
    )
    assertEquals(contract["shortfallReason"], roundtrip["shortfallReason"])
    assertTrue(roundtrip["repeatReason"].asString() in setOf("NONE", "CONTINUITY"))
    assertTrue(
      roundtrip["eligibleExerciseCount"].asInt() >= ready.proposal.snapshot.draft.exercises.size
    )
  }

  private fun v2() = MockHttpServletRequest().apply { addHeader("X-Planner-Protocol", "2") }

  private fun count(table: String) =
    db.queryForObject("SELECT count(*) FROM $table", Int::class.java)

  private fun requestId(raw: ByteArray) =
    UUID.fromString(json.readTree(raw)["requestId"].asString())

  private fun request() =
    json.writeValueAsBytes(
      PlannerV2CreateRequest(
        UUID.randomUUID(),
        0,
        17,
        9,
        now + 3_600_000,
        "UTC",
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        false,
        45,
      )
    )

  private fun legacyRequest() =
    json.writeValueAsBytes(
      CalendarDraftRequest(
        UUID.randomUUID().toString(),
        17,
        9,
        now + 3_600_000,
        "UTC",
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        false,
        45,
        null,
        null,
      )
    )

  private fun owner(goal: String = "GENERAL_FITNESS"): Identity {
    val id = UUID.randomUUID()
    val session = UUID.randomUUID()
    db.update("INSERT INTO users(id,email,email_verified) VALUES (?,?,true)", id, "$id@example.com")
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      id,
      session.toString().replace("-", "").repeat(2),
    )
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,17)", id)
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'profile',?,17,false,?::jsonb)",
      id,
      UUID.randomUUID(),
      json.writeValueAsString(mapOf("trainingGoal" to goal)),
    )
    return Identity(id, session, "$id@example.com")
  }

  private fun exercise(
    owner: Identity,
    movement: String = "HORIZONTAL_PUSH",
    type: String = "STRENGTH",
  ): UUID {
    val id = UUID.randomUUID()
    val payload =
      mapOf(
        "name" to "Test",
        "type" to type,
        "muscles" to listOf(mapOf("muscle" to "UPPER_CHEST", "contribution" to 100)),
        "equipmentIds" to emptyList<String>(),
        "equipmentRequirementState" to "KNOWN",
      )
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,'exercise',?,17,false,?::jsonb)",
      owner.userId,
      id,
      json.writeValueAsString(payload),
    )
    mappings.put(
      owner,
      id,
      PlannerExerciseMappingDto(
        id,
        movement,
        listOf("ACCESSORY", "CONDITIONING", "PRIMARY"),
        DeterministicPlannerRuntime.goals.sorted(),
        type,
        emptyList(),
      ),
    )
    return id
  }

  private fun setupConfig(twoSlots: Boolean = false) {
    val original = config.snapshot()
    config.save(
      original.copy(
        collections =
          original.collections.map { collection ->
            val slots =
              listOf(
                PlannerPatternSlot(
                  "PRIMARY",
                  "Typed",
                  sets = 3,
                  repsMin = 8,
                  repsMax = 12,
                  slotId = "push",
                  movementClass = "HORIZONTAL_PUSH",
                )
              ) +
                if (twoSlots)
                  listOf(
                    PlannerPatternSlot(
                      "ACCESSORY",
                      "Typed",
                      sets = 2,
                      repsMin = 8,
                      repsMax = 12,
                      slotId = "core",
                      movementClass = "CORE",
                    )
                  )
                else emptyList()
            collection.copy(
              patterns =
                listOf(PlannerPattern("${collection.id}-test", "Test", "FULL_BODY", "", slots))
            )
          }
      )
    )
  }
}
