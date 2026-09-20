package tech.valerochkagym

import java.math.BigInteger
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import tech.valerochkagym.service.ai.AiActionService
import tech.valerochkagym.service.ai.AiContextReader
import tech.valerochkagym.service.ai.AiProviderInput
import tech.valerochkagym.service.ai.CalendarAiExecutionHooks
import tech.valerochkagym.service.ai.PlannerToolCallingProvider
import tech.valerochkagym.service.ai.aiError
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Testcontainers
@SpringBootTest(
  classes = [Application::class, CalendarAiCaptureIntegrationTest.Fakes::class],
  properties = ["gym.calendar-jobs.enabled=false"],
)
class CalendarAiCaptureIntegrationTest {
  companion object {
    private val capturedAt = 1_805_005_800_000L

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

  class FakeProvider : PlannerToolCallingProvider {
    override var available = true
    var calls = 0
    var repairRejected = false
    var historyCandidateIds = emptyList<String>()
    var handler: (AiProviderInput) -> JsonNode = { error("test handler absent") }

    override fun generate(input: AiProviderInput): JsonNode {
      calls++
      return handler(input)
    }

    override fun generatePlannerTurn(
      input: AiProviderInput
    ): tech.valerochkagym.service.ai.PlannerTurn {
      if (
        historyCandidateIds.isNotEmpty() &&
          input.plannerTranscript.isNotEmpty() &&
          input.plannerTranscript.none { it.call.name == "get_candidate_details_and_history" }
      ) {
        return tech.valerochkagym.service.ai.PlannerTurn(
          calls =
            listOf(
              tech.valerochkagym.service.ai.PlannerToolProtocol.Call(
                "history",
                "get_candidate_details_and_history",
                historyCandidateIds,
                tools.jackson.databind.json.JsonMapper.builder()
                  .build()
                  .writeValueAsBytes(mapOf("candidateIds" to historyCandidateIds)),
              )
            )
        )
      }
      return TestPlannerTurns.turn(input, ::generate, repairRejected)
    }
  }

  class BarrierHooks : CalendarAiExecutionHooks {
    var afterReserve: (() -> Unit)? = null
    var afterCapture: (() -> Unit)? = null
    var finalLock: Gate? = null
    var proposalInsert: Gate? = null
    var failProposalInsert = false
    var beforeRefinementCommit: (() -> Unit)? = null

    override fun afterReserve() {
      afterReserve?.invoke()
    }

    override fun afterCapture() {
      afterCapture?.invoke()
    }

    override fun beforeFinalLock() {
      finalLock?.await()
    }

    override fun beforeProposalInsert() {
      if (failProposalInsert) error("database barrier failure")
      proposalInsert?.await()
    }

    override fun beforeRefinementCommit() {
      beforeRefinementCommit?.invoke()
    }
  }

  class Gate {
    val reached = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun await() {
      reached.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }

    fun open() = release.countDown()
  }

  class MutableCalendarClock : Clock() {
    var currentTime = capturedAt

    override fun instant(): Instant = Instant.ofEpochMilli(currentTime)

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = Clock.fixed(instant(), zone)
  }

  @TestConfiguration
  class Fakes {
    @Bean @Primary fun provider() = FakeProvider()

    @Bean @Primary fun calendarHooks() = BarrierHooks()

    @Bean @Primary fun fixedCalendarClock() = MutableCalendarClock()
  }

  @Autowired lateinit var jobs: tech.valerochkagym.service.ai.CalendarDraftJobService
  @Autowired
  lateinit var proposals: tech.valerochkagym.service.trainingproposal.TrainingProposalService
  @Autowired lateinit var testClock: MutableCalendarClock
  @Autowired lateinit var actions: AiActionService
  @Autowired lateinit var explanations: tech.valerochkagym.service.ai.PlannerExplanationStore
  @Autowired lateinit var contexts: AiContextReader
  @Autowired lateinit var provider: FakeProvider
  @Autowired lateinit var diagnostics: tech.valerochkagym.service.ai.AiDiagnostics
  @Autowired lateinit var hooks: BarrierHooks
  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper

  @BeforeEach
  @AfterEach
  fun reset() {
    testClock.currentTime = capturedAt
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=9,active=false")
    provider.calls = 0
    provider.available = true
    provider.repairRejected = false
    provider.historyCandidateIds = emptyList()
    provider.handler = { error("test handler absent") }
    hooks.finalLock = null
    hooks.proposalInsert = null
    hooks.failProposalInsert = false
    hooks.beforeRefinementCommit = null
    hooks.afterReserve = null
    hooks.afterCapture = null
  }

  @Test
  fun `sixty five finished parents reject before workout payload expansion`() {
    val owner = owner()
    repeat(65) { offset ->
      workout(owner, UUID.randomUUID(), capturedAt - offset - 1, capturedAt - offset, emptyList())
    }

    assertContextTooLarge { capture(owner) }
  }

  @Test
  fun `eight thousand one hundred ninety third valid fact rejects while eight thousand one hundred ninety two remain admissible`() {
    val owner = owner()
    val exercise = exercise(owner)
    workout(owner, UUID.randomUUID(), capturedAt - 10, capturedAt - 1, sets(exercise, 8192))
    assertEquals(8192, capture(owner).facts.size)

    reset()
    val rejectedOwner = owner()
    val rejectedExercise = exercise(rejectedOwner)
    workout(
      rejectedOwner,
      UUID.randomUUID(),
      capturedAt - 10,
      capturedAt - 1,
      sets(rejectedExercise, 8193),
    )
    assertContextTooLarge { capture(rejectedOwner) }
  }

  @Test
  fun `weight projection follows same exercise actual presence and canonical latest tuple`() {
    val owner = owner()
    val target = exercise(owner)
    val other = exercise(owner)
    val old = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val newer = UUID.fromString("00000000-0000-4000-8000-000000000002")
    workout(
      owner,
      old,
      capturedAt - 100,
      capturedAt - 10,
      sets(target, 1, actual = null, legacy = 70.0),
    )
    workout(
      owner,
      newer,
      capturedAt - 100,
      capturedAt - 1,
      sets(
        target,
        1,
        completedAt = capturedAt - 1,
        actualPresent = true,
        actual = null,
        legacy = 80.0,
      ),
    )
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt - 1,
      sets(other, 1, actual = 999.0),
    )
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt + 1,
      sets(target, 1, actual = 999.0),
    )
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt - 1,
      sets(target, 1, completedAt = capturedAt - 101, actual = 999.0),
    )

    assertNull(projectedWeight(owner, target))

    reset()
    val fallbackOwner = owner()
    val fallbackTarget = exercise(fallbackOwner)
    workout(
      fallbackOwner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt - 1,
      sets(fallbackTarget, 1, actualPresent = false, legacy = 63.5),
    )
    assertNull(projectedWeight(fallbackOwner, fallbackTarget))

    reset()
    val tupleOwner = owner()
    val tupleTarget = exercise(tupleOwner)
    val factTime = capturedAt - 1
    workout(
      tupleOwner,
      UUID.fromString("00000000-0000-4000-8000-000000000010"),
      capturedAt - 100,
      capturedAt - 1,
      sets(tupleTarget, 1, completedAt = factTime, actual = 10.0, actualReps = 8),
    )
    workout(
      tupleOwner,
      UUID.fromString("00000000-0000-4000-8000-000000000001"),
      capturedAt - 100,
      capturedAt - 1,
      sets(tupleTarget, 1, completedAt = factTime, actual = 20.0, actualReps = 8),
    )
    provider.handler = { providerResponse(tupleTarget) }
    val firstSet =
      actions
        .calendar(tupleOwner, rawRequest())
        .proposal
        .snapshot
        .draft
        .exercises
        .single()
        .plannedSets
        .first()
    // The descending ten-set plan starts at ten reps, projected from the canonical 20 kg x 8 set.
    assertEquals(10, firstSet.reps)
    assertEquals(17.5, firstSet.weightKg)
  }

  @Test
  fun `built in gym is captured and background job publishes its proposal`() {
    val owner = owner()
    val exercise = exercise(owner, equipment = listOf("rack"), known = true)
    val gym = UUID.randomUUID()
    db.update("UPDATE catalog_state SET active=true")
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('gym',?,9,false,?::jsonb)",
      gym,
      json.writeValueAsString(
        mapOf(
          "name" to "Built in gym",
          "updatedAt" to capturedAt,
          "exerciseIds" to emptyList<String>(),
          "inventoryConfigured" to true,
          "equipmentIds" to listOf("rack"),
        )
      ),
    )
    val context = providerContext(owner, gymIds = listOf(gym.toString()))
    assertEquals(
      listOf(exercise.toString()),
      context["candidates"].toList().map { it["exerciseId"].asString() },
    )
    provider.handler = { providerResponse(exercise) }
    val accepted = jobs.submit(owner, rawRequest(gymIds = listOf(gym.toString())))
    jobs.runNext()
    val result = jobs.status(owner, UUID.fromString(accepted.requestId))
    assertEquals("READY", result.state, result.errorCode)
    assertEquals(listOf(gym.toString()), result.result!!.proposal.snapshot.draft.gymIds)
  }

  @Test
  fun `personal gym overrides built in gym with the same identity`() {
    val owner = owner()
    val personalExercise = exercise(owner, equipment = listOf("rack"), known = true)
    exercise(owner, equipment = listOf("bench"), known = true)
    val gym = UUID.randomUUID()
    db.update("UPDATE catalog_state SET active=true")
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('gym',?,9,false,?::jsonb)",
      gym,
      json.writeValueAsString(
        mapOf("inventoryConfigured" to true, "equipmentIds" to listOf("bench"))
      ),
    )
    record(
      owner,
      "gym",
      gym,
      mapOf("inventoryConfigured" to true, "equipmentIds" to listOf("rack")),
    )
    val context = providerContext(owner, gymIds = listOf(gym.toString()))
    assertEquals(
      listOf(personalExercise.toString()),
      context["candidates"].toList().map { it["exerciseId"].asString() },
    )
  }

  @Test
  fun `archived and disabled catalog gyms remain unavailable`() {
    val owner = owner()
    exercise(owner)
    val gym = UUID.randomUUID()
    db.update("UPDATE catalog_state SET active=true")
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('gym',?,9,true,?::jsonb)",
      gym,
      json.writeValueAsString(
        mapOf("inventoryConfigured" to true, "equipmentIds" to emptyList<String>())
      ),
    )
    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> {
          actions.calendar(owner, rawRequest(gymIds = listOf(gym.toString())))
        }
        .code,
    )
    db.update("UPDATE standard_records SET archived=false WHERE kind='gym' AND id=?", gym)
    db.update("UPDATE catalog_state SET active=false")
    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> {
          actions.calendar(owner, rawRequest(gymIds = listOf(gym.toString())))
        }
        .code,
    )
  }

  @Test
  fun `selected gym intersection and exclusions leave only known covered candidates`() {
    val owner = owner()
    val plain = exercise(owner, equipment = emptyList(), known = true)
    val equipment = exercise(owner, equipment = listOf("rack"), known = true)
    val unknown = exercise(owner, equipment = listOf("rack"), known = false)
    val absent = exercise(owner, equipment = emptyList(), known = true)
    val gymA = UUID.randomUUID()
    val gymB = UUID.randomUUID()
    record(
      owner,
      "gym",
      gymA,
      mapOf("inventoryConfigured" to false, "exerciseIds" to listOf(plain, equipment, unknown)),
    )
    record(
      owner,
      "gym",
      gymB,
      mapOf("inventoryConfigured" to true, "equipmentIds" to listOf("rack")),
    )
    val context =
      providerContext(
        owner,
        gymIds = listOf(gymA.toString(), gymB.toString()),
        excludedEquipment = listOf("rack"),
      )
    assertEquals(
      listOf(plain.toString()),
      context["candidates"].toList().map { it["exerciseId"].asString() },
    )
    assertFalse(context.toString().contains(equipment.toString()))
    assertFalse(context.toString().contains(unknown.toString()))
    assertFalse(context.toString().contains(absent.toString()))
  }

  @Test
  fun `notes obey opt out timestamps whole entry and utf8 limits`() {
    val owner = owner()
    val exercise = exercise(owner)
    val section = UUID.randomUUID()
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt - 1,
      sets(exercise, 1, sectionId = section, note = "set-note"),
      note = "workout-note",
    )
    record(owner, "exercise_hint", exercise, mapOf("updatedAt" to capturedAt - 2, "text" to "hint"))
    record(
      owner,
      "exercise_hint",
      UUID.randomUUID(),
      mapOf("updatedAt" to capturedAt + 1, "text" to "future"),
    )
    record(
      owner,
      "exercise_hint",
      UUID.randomUUID(),
      mapOf("updatedAt" to capturedAt - 2_400_000_000L, "text" to "old"),
    )
    repeat(24) { index ->
      record(
        owner,
        "exercise_hint",
        UUID.randomUUID(),
        mapOf("updatedAt" to capturedAt - 3 - index, "text" to "я".repeat(2000)),
      )
    }

    val included = capture(owner, includeNotes = true).notes
    assertTrue(included.size <= 20)
    assertTrue(included.sumOf { (it["text"] as String).toByteArray().size } <= 16384)
    assertTrue(
      included.any { it["kind"] == "WORKOUT_NOTE" && it["sourceTimeMillis"] == capturedAt - 1 }
    )
    assertTrue(
      included.any { it["kind"] == "SET_NOTE" && it["sourceTimeMillis"] == capturedAt - 100 }
    )
    assertTrue(
      included.any { it["kind"] == "EXERCISE_HINT" && it["sourceTimeMillis"] == capturedAt - 2 }
    )
    assertFalse(included.any { it["text"] == "future" || it["text"] == "old" })
    assertTrue(capture(owner, includeNotes = false).notes.isEmpty())
  }

  @Test
  fun `agentic provider context excludes mass health and inbody details`() {
    val owner = owner()
    exercise(owner)
    record(
      owner,
      "measurement",
      UUID.randomUUID(),
      mapOf("measuredAt" to capturedAt - 5, "weightKg" to 72.5),
    )
    record(
      owner,
      "measurement",
      UUID.randomUUID(),
      mapOf("measuredAt" to capturedAt + 1, "weightKg" to 99.0),
    )

    val context = providerContext(owner)
    assertFalse(context.has("mass"))
    assertFalse(context.has("health"))
    assertFalse(context.toString().contains("inbody", ignoreCase = true))
  }

  @Test
  fun `source row and aggregate limits reject before hydration while valid records remain`() {
    val owner = owner()
    val oversized = UUID.randomUUID()
    record(
      owner,
      "workout",
      oversized,
      mapOf(
        "startedAt" to capturedAt - 2,
        "finishedAt" to capturedAt - 1,
        "note" to "x".repeat(1_048_576),
      ),
    )
    assertContextTooLarge { capture(owner) }
    assertEquals(
      1,
      db.queryForObject("SELECT count(*) FROM records WHERE id=?", Int::class.java, oversized),
    )
    db.update("DELETE FROM records WHERE user_id=? AND kind='workout'", owner.userId)

    repeat(9) {
      record(
        owner,
        "workout",
        UUID.randomUUID(),
        mapOf(
          "startedAt" to capturedAt - 2,
          "finishedAt" to capturedAt - 1,
          "note" to "x".repeat(1_048_000),
        ),
      )
    }
    assertContextTooLarge { capture(owner) }
    assertEquals(
      9,
      db.queryForObject("SELECT count(*) FROM records WHERE kind='workout'", Int::class.java),
    )
  }

  @Test
  fun `history query uses partial index and ranking keeps integer bounds`() {
    val owner = owner()
    val press =
      exercise(
        owner,
        muscles =
          listOf(
            mapOf("muscle" to "UPPER_CHEST", "contribution" to 100),
            mapOf("muscle" to "TRICEPS", "contribution" to 50),
          ),
      )
    val run = exercise(owner, muscles = listOf(mapOf("muscle" to "CALVES", "contribution" to 50)))
    workout(owner, UUID.randomUUID(), capturedAt - 2, capturedAt - 1, sets(press, 1))
    val simple =
      providerContext(owner, priority = listOf("UPPER_CHEST", "TRICEPS"))["candidates"].toList()
    assertEquals(
      listOf(press.toString(), run.toString()),
      simple.map { it["exerciseId"].asString() },
    )
    assertEquals(150, simple.first()["priority"].asInt())
    assertFalse(simple.first().has("coverage"))

    reset()
    val boundedOwner = owner()
    val exercise = exercise(boundedOwner, muscles = allMuscles())
    workout(boundedOwner, UUID.randomUUID(), capturedAt - 2, capturedAt - 1, sets(exercise, 8192))
    val context =
      providerContext(boundedOwner, priority = allMuscles().map { it["muscle"] as String })
    assertEquals(2500, context["candidates"][0]["priority"].asInt())
    assertEquals(
      8192,
      context["completedMuscleCoverage"]["last7Days"]
        .single { it["muscle"].asString() == "UPPER_CHEST" }["completedSetCount"]
        .asInt(),
    )

    repeat(500) { offset ->
      workout(
        boundedOwner,
        UUID.randomUUID(),
        capturedAt - 1_000_000 - offset,
        capturedAt - 999_999 - offset,
        emptyList(),
      )
    }
    db.execute("ANALYZE records")
    val plan =
      db
        .query(
          "EXPLAIN (COSTS OFF) SELECT id,octet_length(payload::text) FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number' AND ((payload->>'finishedAt')::numeric)>=? AND ((payload->>'finishedAt')::numeric)<=? ORDER BY ((payload->>'finishedAt')::numeric) DESC,id ASC LIMIT 65",
          { rs, _ -> rs.getString(1) },
          boundedOwner.userId,
          Instant.ofEpochMilli(capturedAt)
            .atZone(java.time.ZoneId.of("America/New_York"))
            .toLocalDate()
            .minusDays(27)
            .atStartOfDay(java.time.ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli(),
          capturedAt,
        )
        .joinToString("\n")
    assertTrue(plan.contains("records_calendar_ai_history"), plan)
    assertTrue(plan.contains("Limit"), plan)
    assertFalse(plan.contains("jsonb_array_elements"), plan)
    val olderPlan =
      db
        .query(
          "EXPLAIN (COSTS OFF) SELECT id,octet_length(payload::text) FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number' AND ((payload->>'finishedAt')::numeric)<? ORDER BY ((payload->>'finishedAt')::numeric) DESC,id ASC LIMIT 3",
          { rs, _ -> rs.getString(1) },
          boundedOwner.userId,
          capturedAt - 28L * 86_400_000,
        )
        .joinToString("\n")
    assertTrue(olderPlan.contains("records_calendar_ai_history"), olderPlan)
    assertTrue(olderPlan.contains("Limit"), olderPlan)
  }

  @Test
  fun `candidate sentinel and old workouts obey bounded capture windows`() {
    val candidateOwner = owner()
    repeat(1001) { exercise(candidateOwner) }
    assertContextTooLarge { capture(candidateOwner) }

    reset()
    val historyOwner = owner()
    repeat(65) { offset ->
      workout(
        historyOwner,
        UUID.randomUUID(),
        capturedAt - 2_500_000_000L - offset,
        capturedAt - 2_500_000_000L - offset + 1,
        emptyList(),
      )
    }
    assertTrue(capture(historyOwner).facts.isEmpty())
  }

  @Test
  fun `selected gym is hydrated before unrelated gym limits and payloads`() {
    val owner = owner()
    val exercise = exercise(owner)
    repeat(1001) { index ->
      record(
        owner,
        "gym",
        UUID.randomUUID(),
        mapOf("inventoryConfigured" to false, "exerciseIds" to emptyList<String>(), "note" to index),
      )
    }
    record(
      owner,
      "gym",
      UUID.randomUUID(),
      mapOf(
        "inventoryConfigured" to false,
        "exerciseIds" to emptyList<String>(),
        "note" to "x".repeat(1_048_576),
      ),
    )
    val selected = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")
    record(
      owner,
      "gym",
      selected,
      mapOf("inventoryConfigured" to false, "exerciseIds" to listOf(exercise.toString())),
    )
    val context = providerContext(owner, gymIds = listOf(selected.toString()))
    assertEquals(
      listOf(exercise.toString()),
      context["candidates"].toList().map { it["exerciseId"].asString() },
    )
  }

  @Test
  fun `personal exercise overlay accounts one effective source row without catalog false budget`() {
    val owner = owner()
    val id = UUID.randomUUID()
    db.update("UPDATE catalog_state SET active=true")
    db.update(
      "INSERT INTO standard_records(kind,id,revision,archived,payload) VALUES ('exercise',?,0,false,?::jsonb)",
      id,
      json.writeValueAsString(
        mapOf(
          "name" to "catalog-large",
          "type" to "STRENGTH",
          "muscles" to listOf(mapOf("muscle" to "UPPER_CHEST", "contribution" to 100)),
          "equipmentIds" to emptyList<String>(),
          "equipmentRequirementState" to "KNOWN",
          "shadowNumber" to BigInteger("9".repeat(1001)),
        )
      ),
    )
    record(
      owner,
      "exercise",
      id,
      mapOf(
        "name" to "personal",
        "type" to "STRENGTH",
        "muscles" to listOf(mapOf("muscle" to "UPPER_CHEST", "contribution" to 100)),
        "equipmentIds" to emptyList<String>(),
        "equipmentRequirementState" to "KNOWN",
      ),
    )

    val captured = capture(owner)
    assertEquals(1, captured.sourceRows.count { it.kind == "exercise" && it.key == id.toString() })
    assertEquals(
      "personal",
      captured.candidates.single { it.id == id.toString() }.payload["name"].asString(),
    )
  }

  @Test
  fun `raw duplicate members reject before reserve and provider admission`() {
    val owner = owner()
    val original = rawRequest().toString(Charsets.UTF_8)
    val duplicate = original.dropLast(1) + ",\"requestId\":\"${UUID.randomUUID()}\"}"
    val invalid =
      listOf(
        byteArrayOf(),
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + original.toByteArray(),
        byteArrayOf(0xFF.toByte()),
        "{".toByteArray(),
        duplicate.toByteArray(),
        (original + "{}").toByteArray(),
        (original.dropLast(1) + ",\"unknown\":1}").toByteArray(),
      )
    invalid.forEach { raw ->
      assertEquals(
        "invalid_request",
        assertThrows<ApiException> { actions.calendar(owner, raw) }.code,
      )
    }
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_ai_attempts", Int::class.java))
  }

  @Test
  fun `succeeded replay survives later revisions while changed body conflicts`() {
    val owner = owner()
    val exercise = exercise(owner)
    val request = rawRequest()
    provider.handler = { providerResponse(exercise) }
    val receipt = actions.calendar(owner, request)

    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    db.update("UPDATE catalog_state SET revision=10")

    assertEquals(receipt, actions.calendar(owner, request))
    val changed = json.readTree(request) as ObjectNode
    changed.put("expectedRevision", 18)
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { actions.calendar(owner, json.writeValueAsBytes(changed)) }.code,
    )
    assertEquals(1, provider.calls)
  }

  @Test
  fun `calendar session authorization stays distinct from stale revisions`() {
    val revoked = owner()
    db.update(
      "UPDATE sessions SET revoked_at=TIMESTAMPTZ '2026-01-01' WHERE id=?",
      revoked.sessionId,
    )
    assertCalendarSessionUnauthorized(revoked)

    reset()
    val expired = owner()
    db.update(
      "UPDATE sessions SET access_expires_at=TIMESTAMPTZ '2000-01-01' WHERE id=?",
      expired.sessionId,
    )
    assertCalendarSessionUnauthorized(expired)

    reset()
    val owner = owner()
    val other = owner()
    assertCalendarSessionUnauthorized(Identity(owner.userId, other.sessionId, owner.email))
  }

  @Test
  fun `capture and strict provider failures terminalize the reserved attempt`() {
    val owner = owner()
    val request = rawRequest()
    repeat(65) { offset ->
      workout(owner, UUID.randomUUID(), capturedAt - offset - 1, capturedAt - offset, emptyList())
    }

    assertEquals(
      "ai_context_too_large",
      assertThrows<ApiException> { actions.calendar(owner, request) }.code,
    )
    assertEquals(0, provider.calls)
    assertEquals(
      "FAILED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )

    reset()
    val providerOwner = owner()
    val exercise = exercise(providerOwner)
    provider.handler = {
      json.readTree(
        """{"result":{"name":"bad","exercises":[{"exerciseId":"$exercise","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null,"weightKg":1}]}]}}"""
      )
    }
    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(providerOwner, rawRequest()) }.code,
    )
    assertEquals(1, provider.calls)
    assertEquals(
      "FAILED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `known cardio duration above the slot fails without creating a proposal`() {
    val owner = owner()
    val exercise = exercise(owner, type = "CARDIO")
    provider.handler = {
      json.readTree(
        """{"result":{"name":"too long","exercises":[{"exerciseId":"$exercise","restSeconds":60,"plannedSets":[{"reps":null,"durationSec":2700},{"reps":null,"durationSec":1}]}]}}"""
      )
    }

    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(owner, rawRequest()) }.code,
    )
    assertEquals(1, provider.calls)
    assertEquals(
      "FAILED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `agentic planner rejects a tiny sixty minute plan before persistence`() {
    val owner = owner()
    val exercise = exercise(owner)
    val request = json.readTree(rawRequest()) as ObjectNode
    request.put("availableDurationMinutes", 60)
    provider.handler = { shortProviderResponse(exercise) }

    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(owner, json.writeValueAsBytes(request)) }.code,
    )
    assertEquals(1, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `provider strict shape rejects malformed rows and accepts exact timed fit`() {
    val owner = owner()
    val strength = exercise(owner)
    val invalid =
      listOf(
        """{"result":{"name":"x","exercises":[]}}""",
        """{"result":{"name":"x","exercises":[{"exerciseId":"$strength","restSeconds":"0","plannedSets":[{"reps":8,"durationSec":null}]}]}}""",
        """{"result":{"name":"x","exercises":[{"exerciseId":"$strength","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null}],"extra":true}]}}""",
      )
    invalid.forEach { body ->
      provider.handler = { json.readTree(body) }
      assertEquals(
        "ai_invalid_response",
        assertThrows<ApiException> { actions.calendar(owner, rawRequest()) }.code,
      )
    }

    reset()
    val timedOwner = owner()
    val timed = exercise(timedOwner, type = "TIMED")
    provider.handler = {
      json.readTree(
        """{"result":{"name":"fit","exercises":[{"exerciseId":"$timed","restSeconds":0,"plannedSets":[{"reps":null,"durationSec":2700}]}]}}"""
      )
    }
    assertEquals(1, actions.calendar(timedOwner, rawRequest()).proposal.currentVersion)

    reset()
    val cardioOwner = owner()
    val cardio = exercise(cardioOwner, type = "CARDIO")
    provider.handler = {
      json.readTree(
        """{"result":{"name":"cardio fit","exercises":[{"exerciseId":"$cardio","restSeconds":0,"plannedSets":[{"reps":null,"durationSec":2700}]}]}}"""
      )
    }
    val cardioResponse = json.valueToTree<JsonNode>(actions.calendar(cardioOwner, rawRequest()))
    val set = cardioResponse["proposal"]["snapshot"]["draft"]["exercises"][0]["plannedSets"][0]
    assertEquals(2700, set["durationSec"].asInt())
    assertTrue(set["speedKmh"].isNull)
    assertTrue(set["inclinePct"].isNull)
  }

  @Test
  fun `agentic provider context excludes notes even when the caller opts in`() {
    val owner = owner()
    val selected = exercise(owner)
    val excluded = exercise(owner)
    record(owner, "exercise_hint", selected, mapOf("updatedAt" to capturedAt - 1, "text" to "keep"))
    record(owner, "exercise_hint", excluded, mapOf("updatedAt" to capturedAt - 1, "text" to "drop"))
    val raw = rawRequest(excludedExercises = listOf(excluded.toString()), includeNotes = true)
    var context: JsonNode? = null
    provider.handler = { input ->
      context = json.readTree(input.context)
      throw aiError("ai_invalid_response")
    }
    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(owner, raw) }.code,
    )
    assertFalse(requireNotNull(context).has("notes"))
  }

  @Test
  fun `request digest conflict processing replay and expired lease never admit a provider`() {
    val owner = owner()
    val request = rawRequest()
    reserveProcessing(owner, request, capturedAt + 60_000)

    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { actions.calendar(owner, request + byteArrayOf(32)) }.code,
    )
    assertEquals(
      "ai_in_progress",
      assertThrows<ApiException> { actions.calendar(owner, request) }.code,
    )
    assertEquals(0, provider.calls)
    assertEquals(
      "PROCESSING",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )

    reset()
    val crashedOwner = owner()
    val crashed = rawRequest()
    reserveProcessing(crashedOwner, crashed, capturedAt - 1)
    assertEquals(
      "ai_interrupted",
      assertThrows<ApiException> { actions.calendar(crashedOwner, crashed) }.code,
    )
    assertEquals(0, provider.calls)
    assertEquals(
      "INTERRUPTED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
  }

  @Test
  fun `cancellation during provider work wins before final proposal commit`() {
    val owner = owner()
    val exercise = exercise(owner)
    val request = rawRequest()
    provider.handler = {
      actions.cancelCalendar(owner, request)
      providerResponse(exercise)
    }

    assertEquals("ai_timeout", assertThrows<ApiException> { actions.calendar(owner, request) }.code)
    assertEquals(1, provider.calls)
    assertEquals(
      "CANCELLED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `owner deletion before final guard leaves no attempt or proposal`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = {
      db.update("DELETE FROM users WHERE id=?", owner.userId)
      providerResponse(exercise)
    }

    assertEquals(
      "unauthorized",
      assertThrows<ApiException> { actions.calendar(owner, rawRequest()) }.code,
    )
    assertEquals(1, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_ai_attempts", Int::class.java))
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `owner deletion after reserve or capture prevents provider admission`() {
    val reservedOwner = owner()
    hooks.afterReserve = { db.update("DELETE FROM users WHERE id=?", reservedOwner.userId) }
    assertEquals(
      "unauthorized",
      assertThrows<ApiException> { actions.calendar(reservedOwner, rawRequest()) }.code,
    )
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_ai_attempts", Int::class.java))

    reset()
    val capturedOwner = owner()
    exercise(capturedOwner)
    hooks.afterCapture = { db.update("DELETE FROM users WHERE id=?", capturedOwner.userId) }
    assertEquals(
      "unauthorized",
      assertThrows<ApiException> { actions.calendar(capturedOwner, rawRequest()) }.code,
    )
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_ai_attempts", Int::class.java))
  }

  @Test
  fun `concurrent first refinement claim binds one proposal before provider work`() {
    val owner = owner()
    val focus = exercise(owner)
    val accessory = exercise(owner)
    allowRefinementCandidates(owner, focus, accessory)
    val first = refinableProposal(owner, focus)
    val second = refinableProposal(owner, focus)
    val request = refinementRequest(UUID.randomUUID())
    val providerGate = Gate()
    provider.handler = {
      providerGate.await()
      refinedProviderResponse(focus, accessory)
    }
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val outcomes =
        listOf(first.proposalId, second.proposalId).map { proposalId ->
          pool.submit<String> {
            start.await(5, TimeUnit.SECONDS)
            runCatching { actions.refineCalendar(owner, proposalId, request) }
              .fold({ "success:${it.proposalId}" }, { "error:${(it as ApiException).code}" })
          }
        }
      start.countDown()
      assertTrue(providerGate.reached.await(5, TimeUnit.SECONDS))
      providerGate.open()

      val result = outcomes.map { it.get(10, TimeUnit.SECONDS) }
      assertEquals(1, result.count { it.startsWith("success:") })
      assertEquals(1, result.count { it == "error:ai_request_conflict" })
      assertEquals(1, provider.calls)
      assertEquals(
        1,
        db.queryForObject(
          "SELECT count(*) FROM calendar_planner_refinements WHERE owner_id=? AND request_id=?",
          Int::class.java,
          owner.userId,
          UUID.fromString(json.readTree(request)["requestId"].asString()),
        ),
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `failed refinement keeps raw receipt binding terminal without another provider call`() {
    val owner = owner()
    val focus = exercise(owner)
    allowRefinementCandidates(owner, focus)
    val proposal = refinableProposal(owner, focus)
    val request = refinementRequest(UUID.randomUUID())
    provider.handler = { throw aiError("ai_invalid_response") }

    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, request) }
        .code,
    )
    assertEquals(1, provider.calls)
    provider.calls = 0

    assertEquals(
      "ai_interrupted",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, request) }
        .code,
    )
    assertEquals(0, provider.calls)

    val rebound = json.readTree(request) as ObjectNode
    rebound.put("refinement", "Другой текст")
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> {
          actions.refineCalendar(owner, proposal.proposalId, json.writeValueAsBytes(rebound))
        }
        .code,
    )
    assertEquals(0, provider.calls)
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM calendar_planner_refinements WHERE owner_id=? AND request_id=?",
        Int::class.java,
        owner.userId,
        UUID.fromString(json.readTree(request)["requestId"].asString()),
      ),
    )
  }

  @Test
  fun `refinement provider deadline crossing writes no version or success receipt`() {
    val owner = owner()
    val focus = exercise(owner)
    val accessory = exercise(owner)
    allowRefinementCandidates(owner, focus, accessory)
    val proposal = refinableProposal(owner, focus)
    val request = refinementRequest(UUID.randomUUID())
    provider.handler = {
      testClock.currentTime += 45_000
      refinedProviderResponse(focus, accessory)
    }

    assertEquals(
      "ai_timeout",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, request) }
        .code,
    )
    assertEquals(1, provider.calls)
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_versions WHERE proposal_id=?",
        Int::class.java,
        proposal.proposalId,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM calendar_planner_refinements WHERE receipt IS NOT NULL",
        Int::class.java,
      ),
    )
    provider.calls = 0
    assertEquals(
      "ai_interrupted",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, request) }
        .code,
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `refinement commit fence rejects a lease that expires after provider validation`() {
    val owner = owner()
    val focus = exercise(owner)
    val accessory = exercise(owner)
    allowRefinementCandidates(owner, focus, accessory)
    val proposal = refinableProposal(owner, focus)
    val request = refinementRequest(UUID.randomUUID())
    provider.handler = { refinedProviderResponse(focus, accessory) }
    hooks.beforeRefinementCommit = { testClock.currentTime += 45_000 }

    assertEquals(
      "ai_interrupted",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, request) }
        .code,
    )
    assertEquals(1, provider.calls)
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM training_proposal_versions WHERE proposal_id=?",
        Int::class.java,
        proposal.proposalId,
      ),
    )
    assertEquals(
      0,
      db.queryForObject(
        "SELECT count(*) FROM calendar_planner_refinements WHERE receipt IS NOT NULL",
        Int::class.java,
      ),
    )
  }

  private fun owner(): Identity {
    val owner = UUID.randomUUID()
    val session = UUID.randomUUID()
    db.update(
      "INSERT INTO users(id,email,email_verified) VALUES (?,?,true)",
      owner,
      "$owner@example.com",
    )
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      session,
      owner,
      session.toString().replace("-", "").repeat(2),
    )
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,17)", owner)
    return Identity(owner, session, "$owner@example.com")
  }

  private fun capture(owner: Identity, includeNotes: Boolean = false) =
    contexts.captureCalendar(owner, 17, 9, "America/New_York", includeNotes, emptyList())

  private fun assertCalendarSessionUnauthorized(identity: Identity) {
    listOf<() -> Unit>(
        { capture(identity) },
        { contexts.verifyCalendarAdmission(identity, 17, 9) },
        { actions.calendar(identity, rawRequest()) },
      )
      .forEach { call -> assertEquals("unauthorized", assertThrows<ApiException> { call() }.code) }
  }

  private fun exercise(
    owner: Identity,
    equipment: List<String> = emptyList(),
    known: Boolean = true,
    type: String = "STRENGTH",
    muscles: List<Map<String, Any>> =
      listOf(mapOf("muscle" to "UPPER_CHEST", "contribution" to 100)),
  ): UUID =
    UUID.randomUUID().also { id ->
      record(
        owner,
        "exercise",
        id,
        mapOf(
          "name" to id.toString(),
          "type" to type,
          "muscles" to muscles,
          "equipmentIds" to equipment,
          "equipmentRequirementState" to if (known) "KNOWN" else "UNKNOWN",
        ),
      )
    }

  private fun workout(
    owner: Identity,
    id: UUID,
    startedAt: Long,
    finishedAt: Long,
    sections: List<Map<String, Any>>,
    note: String? = null,
  ) =
    record(
      owner,
      "workout",
      id,
      buildMap {
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("exercises", sections)
        note?.let { put("note", it) }
      },
    )

  private fun sets(
    exercise: UUID,
    count: Int,
    sectionId: UUID = UUID.randomUUID(),
    completedAt: Long? = null,
    actualPresent: Boolean = false,
    actual: Double? = null,
    legacy: Double? = null,
    note: String? = null,
    actualReps: Int? = null,
  ): List<Map<String, Any>> =
    listOf(
      buildMap {
        put("exerciseId", exercise.toString())
        put("sectionId", sectionId.toString())
        put(
          "sets",
          List(count) {
            buildMap {
              put("isCompleted", true)
              put("setType", "WORK")
              actualReps?.let { put("actualReps", it) }
              completedAt?.let { put("completedAt", it) }
              if (actualPresent) put("actualWeightKg", actual)
              else actual?.let { put("actualWeightKg", it) }
              legacy?.let { put("weightKg", it) }
              note?.let { put("note", it) }
            }
          },
        )
      }
    )

  private fun record(owner: Identity, kind: String, id: UUID, payload: Map<String, Any?>) {
    db.update(
      "INSERT INTO records(user_id,kind,id,revision,deleted,payload) VALUES (?,?,?,0,false,?::jsonb)",
      owner.userId,
      kind,
      id,
      json.writeValueAsString(payload),
    )
  }

  @Test
  fun `lost response and final barriers preserve exactly one terminal proposal outcome`() {
    val owner = owner()
    val exercise = exercise(owner)
    val request = rawRequest()
    val providerGate = Gate()
    provider.handler = {
      providerGate.await()
      providerResponse(exercise)
    }
    val first = FutureTask { actions.calendar(owner, request) }
    Thread.ofVirtual().start(first)
    assertTrue(providerGate.reached.await(5, TimeUnit.SECONDS))
    assertEquals(
      "ai_in_progress",
      assertThrows<ApiException> { actions.calendar(owner, request) }.code,
    )
    providerGate.open()
    val committed = first.get(5, TimeUnit.SECONDS)
    assertEquals(committed, actions.calendar(owner, request))
    assertEquals(1, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))

    reset()
    val timeoutOwner = owner()
    val timeoutExercise = exercise(timeoutOwner)
    val timeoutRequest = rawRequest()
    val finalLock = Gate()
    hooks.finalLock = finalLock
    provider.handler = { providerResponse(timeoutExercise) }
    val timeout = FutureTask { actions.calendar(timeoutOwner, timeoutRequest) }
    Thread.ofVirtual().start(timeout)
    assertTrue(finalLock.reached.await(5, TimeUnit.SECONDS))
    actions.cancelCalendar(timeoutOwner, timeoutRequest)
    finalLock.open()
    val timeoutError = assertThrows<Exception> { timeout.get(5, TimeUnit.SECONDS) }
    assertEquals("ai_timeout", requireNotNull(timeoutError.cause as? ApiException).code)
    assertEquals(
      "CANCELLED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))

    reset()
    val failureOwner = owner()
    val failureExercise = exercise(failureOwner)
    hooks.failProposalInsert = true
    provider.handler = { providerResponse(failureExercise) }
    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(failureOwner, rawRequest()) }.code,
    )
    assertEquals(
      "FAILED",
      db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
    )
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `v2 replay returns the committed frozen projection without another provider turn`() {
    val owner = owner()
    val exercise = exercise(owner)
    record(
      owner,
      "planner_exercise_preferences",
      UUID.randomUUID(),
      mapOf(
        "preferences" to listOf(mapOf("exerciseId" to exercise.toString(), "preference" to "MORE"))
      ),
    )
    val raw = rawRequest()
    provider.handler = { input ->
      // The agentic serializer must not reuse v1's measurement/notes-shaped context.
      assertFalse(input.context.contains("\"mass\""))
      assertFalse(input.context.contains("\"notes\""))
      json.valueToTree(
        mapOf(
          "result" to
            mapOf(
              "name" to "Frozen v2 draft",
              "exercises" to
                listOf(
                  mapOf(
                    "exerciseId" to exercise.toString(),
                    "restSeconds" to 240,
                    "plannedSets" to List(10) { mapOf("reps" to 8, "durationSec" to null) },
                  )
                ),
            )
        )
      )
    }
    val first = actions.calendarV2(owner, raw)
    val replay = actions.calendarV2(owner, raw)
    assertEquals(first, replay)
    assertEquals(1, provider.calls)
    val persisted =
      db.queryForObject("SELECT v2_receipt::text FROM calendar_ai_attempts", String::class.java)!!
    assertEquals(json.readTree(json.writeValueAsString(first)), json.readTree(persisted))
  }

  @Test
  fun `job acceptance survives lost acknowledgement and only worker creates proposal`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val raw = rawRequest()
    val first = jobs.submit(owner, raw)
    assertEquals("QUEUED", first.state)
    assertEquals(first, jobs.submit(owner, raw))
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    jobs.runNext()
    val ready = jobs.status(owner, UUID.fromString(first.requestId))
    assertEquals("READY", ready.state)
    assertEquals(first.requestId, ready.result!!.requestId)
    assertEquals(ready, jobs.submit(owner, raw))
    jobs.runNext()
    assertEquals(1, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM records WHERE kind='calendar_plan'", Int::class.java),
    )
  }

  @Test
  fun `out of order replacement lineage tombstones never restore old requests`() {
    val owner = owner()
    val a = rawRequest()
    val b = rawRequest()
    val aId = json.readTree(a)["requestId"].asString()
    val bId = json.readTree(b)["requestId"].asString()
    val c = json.readTree(rawRequest()) as ObjectNode
    c.putArray("replacesRequestIds").add(aId).add(bId)
    val current = jobs.submit(owner, json.writeValueAsBytes(c))
    assertEquals("SUPERSEDED", jobs.submit(owner, a).state)
    assertEquals("SUPERSEDED", jobs.submit(owner, b).state)
    assertEquals("QUEUED", jobs.status(owner, UUID.fromString(current.requestId)).state)
    assertEquals(
      1,
      db.queryForObject(
        "SELECT count(*) FROM calendar_draft_jobs WHERE current_job",
        Int::class.java,
      ),
    )
  }

  @Test
  fun `expired lease recovers work after restart without duplicating a ready result`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val job = jobs.submit(owner, rawRequest())
    db.update(
      "UPDATE calendar_draft_jobs SET state='RUNNING',executions=1,lease_token=?,lease_until=?",
      UUID.randomUUID(),
      Timestamp.from(Instant.ofEpochMilli(capturedAt - 1)),
    )
    jobs.runNext()
    assertEquals("READY", jobs.status(owner, UUID.fromString(job.requestId)).state)
    assertEquals(
      2,
      db.queryForObject("SELECT executions FROM calendar_draft_jobs", Int::class.java),
    )
    jobs.runNext()
    assertEquals(1, provider.calls)
  }

  @Test
  fun `restart retries are bounded`() {
    val owner = owner()
    val job = jobs.submit(owner, rawRequest())
    db.update(
      "UPDATE calendar_draft_jobs SET state='RUNNING',executions=3,lease_token=?,lease_until=?",
      UUID.randomUUID(),
      Timestamp.from(Instant.ofEpochMilli(capturedAt - 1)),
    )
    jobs.runNext()
    assertEquals("FAILED", jobs.status(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `late provider result cannot publish after changing conditions`() {
    val owner = owner()
    val exercise = exercise(owner)
    val old = jobs.submit(owner, rawRequest())
    provider.handler = {
      jobs.submit(owner, rawRequest())
      providerResponse(exercise)
    }
    jobs.runNext()
    assertEquals("SUPERSEDED", jobs.status(owner, UUID.fromString(old.requestId)).state)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `ready superseded proposal remains readable but cannot be approved`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val job = jobs.submit(owner, rawRequest())
    jobs.runNext()
    val ready = jobs.status(owner, UUID.fromString(job.requestId)).result!!
    jobs.submit(owner, rawRequest())
    val request =
      tech.valerochkagym.controller.model.ApprovalRequest(
        UUID.randomUUID().toString(),
        1,
        ready.proposal.snapshot.draft,
      )
    assertEquals(
      "proposal_stale",
      assertThrows<ApiException> {
          proposals.approve(
            owner,
            ready.proposal.proposalId,
            json.writeValueAsBytes(request),
            "0".repeat(64),
            request,
          )
        }
        .code,
    )
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals("SUPERSEDED", jobs.status(owner, UUID.fromString(job.requestId)).state)
  }

  @Test
  fun `changed history invalidates job before provider and other owner cannot see it`() {
    val owner = owner()
    val other = owner()
    val job = jobs.submit(owner, rawRequest())
    assertEquals(
      404,
      assertThrows<ApiException> { jobs.status(other, UUID.fromString(job.requestId)) }.status,
    )
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    jobs.runNext()
    assertEquals("STALE", jobs.status(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `date already passed yields explicit expired state and no provider request`() {
    val owner = owner()
    val raw = json.readTree(rawRequest()) as ObjectNode
    raw.put("startsAtMillis", capturedAt - 1)
    val job = jobs.submit(owner, json.writeValueAsBytes(raw))
    assertEquals("EXPIRED", job.state)
    jobs.runNext()
    assertEquals(0, provider.calls)
  }

  @Test
  fun `invalid provider output persists only fixed error code`() {
    val owner = owner()
    exercise(owner)
    val previous = diagnostics.snapshot().map { it.id }.toSet()
    provider.handler = { throw IllegalStateException("secret raw content") }
    val job = jobs.submit(owner, rawRequest())
    jobs.runNext()
    val failed = jobs.status(owner, UUID.fromString(job.requestId))
    assertEquals("FAILED", failed.state)
    assertEquals("ai_invalid_response", failed.errorCode)
    assertNull(failed.result)
    val run = diagnostics.snapshot().single { it.id !in previous }
    assertEquals(
      tech.valerochkagym.service.ai.AiDiagnosticFailureCategory.INTERNAL,
      run.failureCategory,
    )
    assertFalse(json.writeValueAsString(run).contains("secret raw content"))
  }

  @Test
  fun `job diagnostics capture unavailable provider before calendar execution without private data`() {
    val owner = owner()
    exercise(owner)
    val previous = diagnostics.snapshot().map { it.id }.toSet()
    val job = jobs.submit(owner, rawRequest())
    provider.available = false
    jobs.runNext()
    val failed = jobs.status(owner, UUID.fromString(job.requestId))
    assertEquals("FAILED", failed.state)
    assertEquals("ai_unavailable", failed.errorCode)
    assertEquals(0, provider.calls)
    val run = diagnostics.snapshot().single { it.id !in previous }
    assertEquals(tech.valerochkagym.service.ai.AiDiagnosticOutcome.FAILURE, run.outcome)
    assertEquals(
      tech.valerochkagym.service.ai.AiDiagnosticFailureCategory.PROVIDER_UNCONFIGURED,
      run.failureCategory,
    )
    assertEquals(
      listOf(tech.valerochkagym.service.ai.AiDiagnosticStage.CALENDAR_JOB),
      run.stages.map { it.stage },
    )
    val encoded = json.writeValueAsString(run)
    assertFalse(encoded.contains(owner.userId.toString()))
    assertFalse(encoded.contains(owner.sessionId.toString()))
    assertFalse(encoded.contains(job.requestId))
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `cancellation before acknowledgement tombstones the delayed delivery`() {
    val owner = owner()
    val raw = rawRequest()
    val id = UUID.fromString(json.readTree(raw)["requestId"].asString())
    jobs.cancel(owner, id)
    jobs.cancel(owner, id)
    assertEquals("SUPERSEDED", jobs.submit(owner, raw).state)
    jobs.runNext()
    assertEquals(0, provider.calls)
  }

  @Test
  fun `ready job becomes stale on read after history change`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val job = jobs.submit(owner, rawRequest())
    jobs.runNext()
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    val stale = jobs.status(owner, UUID.fromString(job.requestId))
    assertEquals("STALE", stale.state)
    assertTrue(stale.result != null)
  }

  @Test
  fun `revoked session cannot execute a queued job`() {
    val owner = owner()
    val job = jobs.submit(owner, rawRequest())
    db.update(
      "UPDATE sessions SET revoked_at=? WHERE id=?",
      Timestamp.from(Instant.ofEpochMilli(capturedAt)),
      owner.sessionId,
    )
    jobs.runNext()
    assertEquals(
      "FAILED",
      db.queryForObject(
        "SELECT state FROM calendar_draft_jobs WHERE request_id=?",
        String::class.java,
        UUID.fromString(job.requestId),
      ),
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `reclaimed lease fences late execution and inserts exactly one proposal`() {
    val owner = owner()
    val exercise = exercise(owner)
    val job = jobs.submit(owner, rawRequest())
    provider.handler = {
      provider.handler = { providerResponse(exercise) }
      db.update(
        "UPDATE calendar_draft_jobs SET lease_until=?",
        Timestamp.from(Instant.ofEpochMilli(capturedAt - 1)),
      )
      jobs.runNext()
      providerResponse(exercise)
    }
    jobs.runNext()
    assertEquals("READY", jobs.status(owner, UUID.fromString(job.requestId)).state)
    assertEquals(2, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `changed bytes for existing job identity conflict without more work`() {
    val owner = owner()
    val raw = rawRequest()
    jobs.submit(owner, raw)
    val altered = json.readTree(raw) as ObjectNode
    altered.put("availableDurationMinutes", 60)
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { jobs.submit(owner, json.writeValueAsBytes(altered)) }.code,
    )
    assertEquals(1, db.queryForObject("SELECT count(*) FROM calendar_draft_jobs", Int::class.java))
  }

  @Test
  fun `expired preparation cannot approve a moved draft without refreshing job status`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val job = jobs.submit(owner, rawRequest())
    jobs.runNext()
    val ready = jobs.status(owner, UUID.fromString(job.requestId)).result!!
    testClock.currentTime = capturedAt + 3_600_001
    val request =
      tech.valerochkagym.controller.model.ApprovalRequest(
        UUID.randomUUID().toString(),
        1,
        ready.proposal.snapshot.draft.copy(startsAtMillis = capturedAt + 7_200_000),
      )
    assertEquals(
      "READY",
      db.queryForObject("SELECT state FROM calendar_draft_jobs", String::class.java),
    )
    assertEquals(
      "proposal_stale",
      assertThrows<ApiException> {
          proposals.approve(
            owner,
            ready.proposal.proposalId,
            json.writeValueAsBytes(request),
            "0".repeat(64),
            request,
          )
        }
        .code,
    )
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM records WHERE kind='calendar_plan'", Int::class.java),
    )
  }

  @Test
  fun `older finished parents are bounded and never restore monthly load or assigned weight`() {
    val owner = owner()
    val target = exercise(owner)
    repeat(5) { index ->
      val time = capturedAt - (40L + index) * 86_400_000
      workout(
        owner,
        UUID.randomUUID(),
        time - 1000,
        time,
        sets(target, 2, actual = 90.0),
        note = "old-private-note",
      )
    }
    val otherOwner = owner()
    workout(
      otherOwner,
      UUID.randomUUID(),
      capturedAt - 30L * 86_400_000 - 1000,
      capturedAt - 30L * 86_400_000,
      sets(target, 1, actual = 999.0),
    )
    val context = capture(owner, includeNotes = true)
    assertTrue(context.facts.isEmpty())
    assertEquals(3, context.workouts.size)
    assertEquals(6, context.olderFacts.size)
    assertTrue(context.notes.isEmpty())
    assertNull(projectedWeight(owner, target))
    val providerContext = providerContext(owner)
    assertFalse(providerContext.has("history"))
    val coverage = providerContext["completedMuscleCoverage"]
    assertEquals(25, coverage["capturedHistory"]["muscles"].size())
    assertEquals(4, coverage["weeklyTrends"].size())
  }

  @Test
  fun `actual result presence and corrected deleted unfinished history remain truthful`() {
    val owner = owner()
    val target = exercise(owner, type = "CARDIO")
    val workout = UUID.randomUUID()
    val set =
      mapOf(
        "isCompleted" to true,
        "actualReps" to null,
        "reps" to 8,
        "actualDurationSec" to 120,
        "durationSec" to 90,
        "actualSpeedKmh" to 7.5,
        "actualInclinePct" to 2.0,
        "setType" to "CARDIO",
      )
    workout(
      owner,
      workout,
      capturedAt - 1000,
      capturedAt - 1,
      listOf(
        mapOf(
          "exerciseId" to target.toString(),
          "sectionId" to UUID.randomUUID().toString(),
          "sets" to listOf(set, set + ("isCompleted" to false)),
        )
      ),
    )
    val fact = capture(owner).facts.single()
    assertNull(fact.results["reps"])
    assertFalse("reps" in fact.legacyFields)
    assertEquals(120.0, fact.results["durationSec"])
    assertEquals(7.5, fact.results["speedKmh"])
    assertEquals("WORKOUT_START_FALLBACK", fact.timeSource)
    db.update(
      "UPDATE records SET payload=jsonb_set(payload,'{exercises,0,sets,0,actualDurationSec}','180') WHERE user_id=? AND id=?",
      owner.userId,
      workout,
    )
    assertEquals(180.0, capture(owner).facts.single().results["durationSec"])
    db.update(
      "UPDATE records SET payload=jsonb_set(payload,'{finishedAt}','null') WHERE user_id=? AND id=?",
      owner.userId,
      workout,
    )
    assertTrue(capture(owner).facts.isEmpty())
    db.update(
      "UPDATE records SET deleted=true,payload=null WHERE user_id=? AND id=?",
      owner.userId,
      workout,
    )
    assertTrue(capture(owner).facts.isEmpty())
  }

  @Test
  fun `agentic context keeps completed coverage while explanations ignore invented rationale`() {
    val owner = owner()
    val first = exercise(owner)
    val second = exercise(owner)
    val last = UUID.randomUUID()
    workout(owner, last, capturedAt - 3_600_000, capturedAt - 1_000, sets(first, 3, actual = 42.0))
    val raw = json.readTree(rawRequest()) as ObjectNode
    raw.put("availableDurationMinutes", 60)
    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertFalse(context.has("history"))
      assertEquals(25, context["completedMuscleCoverage"]["last7Days"].size())
      assertEquals(60, context["intent"]["desiredDurationMinutes"].asInt())
      json.readTree(
        """{"result":{"name":"Synthetic session","exercises":[
        {"exerciseId":"$first","restSeconds":120,"plannedSets":[{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null}]},
        {"exerciseId":"$second","restSeconds":120,"plannedSets":[{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null},{"reps":10,"durationSec":null}]}],
        "rationale":{"selection":"invented","repeat":"NONE","shortfall":"NONE"}}}"""
      )
    }
    val response = actions.calendar(owner, json.writeValueAsBytes(raw))
    val explanation = explanations.read(owner, response.proposal.proposalId)
    assertEquals(3150L, explanation.estimatedSeconds)
    assertEquals(2880L, explanation.minimumSeconds)
    assertEquals(listOf(first.toString()), explanation.repeatedExerciseIds)
    assertEquals(capturedAt - 1000, explanation.lastFinishedAtMillis)
    assertEquals("UNSPECIFIED", explanation.selectionReason)
    assertEquals("UNSPECIFIED", explanation.repeatReason)
    assertEquals("NONE", explanation.shortfallReason)
    assertNull(response.proposal.snapshot.draft.exercises.first().plannedSets.first().weightKg)
    assertEquals(1, provider.calls)
    assertEquals(response, actions.calendar(owner, json.writeValueAsBytes(raw)))
    assertEquals(1, db.queryForObject("SELECT count(*) FROM planner_explanations", Int::class.java))
    assertEquals(
      404,
      assertThrows<ApiException> { explanations.read(owner(), response.proposal.proposalId) }.status,
    )
    assertFalse(json.valueToTree<JsonNode>(response.proposal).has("explanation"))
  }

  @Test
  fun `creation and refinement send completed history in initial context and tool response`() {
    val owner = owner()
    val upper = exercise(owner, muscles = listOf(mapOf("muscle" to "LATS", "contribution" to 100)))
    val lower = exercise(owner, muscles = listOf(mapOf("muscle" to "QUADS", "contribution" to 100)))
    record(
      owner,
      "profile",
      UUID.randomUUID(),
      mapOf(
        "trainingGoal" to "MUSCLE_GAIN",
        "sex" to null,
        "birthDate" to null,
        "experienceLevel" to null,
        "plannedSessionsPerWeek" to 3,
        "preferredSessionDurationMinutes" to 45,
        "manualConstraints" to null,
        "equipmentIds" to emptyList<String>(),
      ),
    )
    val last = UUID.randomUUID()
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 3 * 86_400_000L,
      capturedAt - 3 * 86_400_000L + 1000,
      sets(lower, 2),
    )
    workout(
      owner,
      last,
      capturedAt - 2 * 86_400_000L,
      capturedAt - 2 * 86_400_000L + 1000,
      sets(upper, 3, actual = 50.0, actualReps = 10, note = "private-set-note"),
      note = "private-workout-note",
    )
    val unfinished = UUID.randomUUID()
    workout(owner, unfinished, capturedAt - 1000, capturedAt, sets(lower, 9))
    db.update(
      "UPDATE records SET payload=jsonb_set(payload,'{finishedAt}','null') WHERE user_id=? AND id=?",
      owner.userId,
      unfinished,
    )
    assertEquals(2, capture(owner).workouts.size)
    // Active workouts intentionally prevent proposal publication; deletion also must not restore
    // this unfinished session to the context used by creation or refinement.
    db.update(
      "UPDATE records SET deleted=true,payload=null WHERE user_id=? AND id=?",
      owner.userId,
      unfinished,
    )
    val other = owner()
    workout(other, UUID.randomUUID(), capturedAt - 500, capturedAt, sets(upper, 99))
    provider.historyCandidateIds = listOf(upper.toString())
    provider.handler = { input ->
      val context = json.readTree(input.context)
      val planning = context["planningContext"] ?: context
      val recent = planning["workoutHistory"]["recentWorkouts"]
      assertEquals(2, recent.size())
      assertEquals(upper.toString(), recent[0]["exercises"][0]["exerciseId"].asString())
      assertEquals(3, recent[0]["exercises"][0]["completedSetCounts"]["work"].asInt())
      assertEquals(100, recent[0]["exercises"][0]["currentMuscleContributions"]["LATS"].asInt())
      assertEquals(lower.toString(), recent[1]["exercises"][0]["exerciseId"].asString())
      val historyCall =
        input.plannerTranscript.single { it.call.name == "get_candidate_details_and_history" }
      val history = json.readTree(historyCall.result)["history"]
      assertEquals("AVAILABLE", history["status"].asString())
      assertEquals(1, history["recentWorkouts"].size())
      assertEquals(
        recent[0]["finishedLocalTime"],
        history["recentWorkouts"][0]["finishedLocalTime"],
      )
      assertEquals(
        3,
        history["recentWorkouts"][0]["exercises"][0]["completedSetCounts"]["work"].asInt(),
      )
      val outgoing = input.context + historyCall.result.toString(Charsets.UTF_8)
      listOf(
          "private-set-note",
          "private-workout-note",
          last.toString(),
          other.userId.toString(),
          "actualWeightKg",
          "actualReps",
        )
        .forEach { assertFalse(outgoing.contains(it), it) }
      if (context.has("planningContext")) refinedProviderResponse(upper, lower)
      else providerResponse(upper)
    }
    val created = actions.calendar(owner, rawRequest())
    actions.refineCalendar(owner, created.proposal.proposalId, refinementRequest(UUID.randomUUID()))
    assertEquals(2, provider.calls)
  }

  @Test
  fun `agentic planner repairs a short plan through the validation tool`() {
    val owner = owner()
    val exercises = (1..6).map { exercise(owner) }
    provider.repairRejected = true
    provider.handler = {
      if (provider.calls == 1) shortProviderResponse(exercises.first())
      else providerResponse(exercises.first())
    }
    val response = actions.calendar(owner, rawRequest())
    assertEquals(2, provider.calls)
    assertEquals("NONE", explanations.read(owner, response.proposal.proposalId).shortfallReason)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `agentic validation repair can meet desired time with unchanged history capture`() {
    val owner = owner()
    val exercises = (1..6).map { exercise(owner) }
    provider.repairRejected = true
    provider.handler = { input ->
      if (provider.calls == 1) shortProviderResponse(exercises.first())
      else {
        assertEquals(
          1,
          input.plannerTranscript.count { it.call.name == "validate_and_finalize_plan" },
        )
        assertFalse(json.readTree(input.plannerTranscript.last().result)["valid"].asBoolean())
        json.valueToTree(
          mapOf(
            "result" to
              mapOf(
                "name" to "Full session",
                "exercises" to
                  exercises.map { id ->
                    mapOf(
                      "exerciseId" to id.toString(),
                      "restSeconds" to 60,
                      "plannedSets" to List(4) { mapOf("reps" to 10, "durationSec" to null) },
                    )
                  },
              )
          )
        )
      }
    }
    val response = actions.calendar(owner, rawRequest())
    val explanation = explanations.read(owner, response.proposal.proposalId)
    assertEquals(2, provider.calls)
    assertEquals(2610, explanation.estimatedSeconds.toInt())
    assertEquals("NONE", explanation.shortfallReason)
  }

  private fun rawRequest(
    gymIds: List<String> = emptyList(),
    excludedEquipment: List<String> = emptyList(),
    priority: List<String> = listOf("UPPER_CHEST"),
    excludedExercises: List<String> = emptyList(),
    includeNotes: Boolean = false,
  ) =
    json.writeValueAsBytes(
      linkedMapOf(
        "requestId" to UUID.randomUUID().toString(),
        "expectedRevision" to 17,
        "expectedCatalogRevision" to 9,
        "startsAtMillis" to capturedAt + 3_600_000,
        "timeZoneId" to "America/New_York",
        "gymIds" to gymIds,
        "excludedExerciseIds" to excludedExercises,
        "excludedEquipmentIds" to excludedEquipment,
        "priorityMuscles" to priority,
        "includeNotes" to includeNotes,
        "availableDurationMinutes" to 45,
        "currentState" to null,
        "preferences" to null,
      )
    )

  private fun refinementRequest(requestId: UUID) =
    json.writeValueAsBytes(
      linkedMapOf(
        "requestId" to requestId.toString(),
        "expectedRevision" to 17,
        "expectedCatalogRevision" to 9,
        "expectedProposalVersion" to 1,
        "refinement" to "Больше отдыха",
      )
    )

  private fun refinableProposal(
    owner: Identity,
    focus: UUID,
  ): tech.valerochkagym.controller.model.ProposalResponse {
    return proposals.createCalendarInternalAi(
      owner,
      17,
      9,
      tech.valerochkagym.controller.model.ApprovalDraft(
        "Original draft",
        emptyList(),
        listOf(
          tech.valerochkagym.controller.model.PlannedExercise(
            focus.toString(),
            0,
            listOf(tech.valerochkagym.controller.model.PlannedSet(null, 8, null, null, null)),
          )
        ),
        capturedAt + 3_600_000,
        "America/New_York",
      ),
    )
  }

  private fun allowRefinementCandidates(owner: Identity, vararg exercises: UUID) {
    record(
      owner,
      "planner_exercise_preferences",
      UUID.randomUUID(),
      mapOf(
        "preferences" to
          exercises.map { exerciseId ->
            mapOf("exerciseId" to exerciseId.toString(), "preference" to "MORE")
          }
      ),
    )
  }

  private fun refinedProviderResponse(focus: UUID, accessory: UUID) =
    json.valueToTree<JsonNode>(
      mapOf(
        "result" to
          mapOf(
            "name" to "Refined draft",
            "exercises" to
              listOf(focus, accessory).map { exerciseId ->
                mapOf(
                  "exerciseId" to exerciseId.toString(),
                  "restSeconds" to 800,
                  "plannedSets" to List(8) { mapOf("reps" to 8, "durationSec" to null) },
                )
              },
          )
      )
    )

  private fun reserveProcessing(owner: Identity, raw: ByteArray, leaseUntilMillis: Long) {
    val requestId = UUID.fromString(json.readTree(raw)["requestId"].asString())
    db.update(
      "INSERT INTO calendar_ai_attempts(owner_id,request_id,raw_request_sha256,state,admitted_at,deadline_at,lease_until) VALUES (?,?,?,'PROCESSING',?,?,?)",
      owner.userId,
      requestId,
      MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
      Timestamp.from(Instant.ofEpochMilli(capturedAt)),
      Timestamp.from(Instant.ofEpochMilli(capturedAt + 45_000)),
      Timestamp.from(Instant.ofEpochMilli(leaseUntilMillis)),
    )
  }

  private fun providerResponse(exercise: UUID) =
    json.readTree(
      """{"result":{"name":"Draft","exercises":[{"exerciseId":"$exercise","restSeconds":240,"plannedSets":[{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null}]}]}}"""
    )

  private fun shortProviderResponse(exercise: UUID) =
    json.readTree(
      """{"result":{"name":"Short draft","exercises":[{"exerciseId":"$exercise","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null}]}]}}"""
    )

  private fun providerContext(
    owner: Identity,
    gymIds: List<String> = emptyList(),
    excludedEquipment: List<String> = emptyList(),
    priority: List<String> = listOf("UPPER_CHEST"),
  ): JsonNode {
    var context: JsonNode? = null
    provider.handler = { input ->
      context = json.readTree(input.context)
      throw aiError("ai_invalid_response")
    }
    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> {
          actions.calendar(owner, rawRequest(gymIds, excludedEquipment, priority))
        }
        .code,
    )
    return requireNotNull(context)
  }

  private fun projectedWeight(owner: Identity, exercise: UUID): Double? {
    provider.handler = { providerResponse(exercise) }
    val response = actions.calendar(owner, rawRequest())
    return json
      .valueToTree<JsonNode>(response)["proposal"]["snapshot"]["draft"]["exercises"][0][
        "plannedSets"][0]["weightKg"]
      .takeUnless { it.isNull }
      ?.asDouble()
  }

  private fun allMuscles() =
    listOf(
        "UPPER_CHEST",
        "LOWER_CHEST",
        "FRONT_DELTS",
        "SIDE_DELTS",
        "REAR_DELTS",
        "ROTATOR_CUFF",
        "SERRATUS_ANTERIOR",
        "BICEPS",
        "TRICEPS",
        "FOREARMS",
        "ABS",
        "OBLIQUES",
        "HIP_FLEXORS",
        "ADDUCTORS",
        "QUADS",
        "TIBIALIS_ANTERIOR",
        "CALVES",
        "HAMSTRINGS",
        "GLUTES",
        "HIP_ABDUCTORS",
        "LOWER_BACK",
        "LATS",
        "UPPER_BACK",
        "TRAPS",
        "NECK",
      )
      .map { mapOf("muscle" to it, "contribution" to 100) }

  private fun assertContextTooLarge(action: () -> Unit) {
    assertEquals("ai_context_too_large", assertThrows<ApiException>(action).code)
  }
}
