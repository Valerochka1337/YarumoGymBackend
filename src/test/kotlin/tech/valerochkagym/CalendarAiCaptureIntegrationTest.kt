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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.ai.AiActionService
import tech.valerochkagym.service.ai.AiContextReader
import tech.valerochkagym.service.ai.AiProviderInput
import tech.valerochkagym.service.ai.CalendarAiExecutionHooks
import tech.valerochkagym.service.ai.PlannerConfigurationService
import tech.valerochkagym.service.ai.PlannerToolCallingProvider
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

  @Autowired lateinit var mappings: PlannerMovementMappingService
  @Autowired lateinit var jobs: tech.valerochkagym.service.ai.CalendarDraftJobService
  @Autowired
  lateinit var proposals: tech.valerochkagym.service.trainingproposal.TrainingProposalService
  @Autowired lateinit var testClock: MutableCalendarClock
  @Autowired lateinit var actions: AiActionService
  @Autowired lateinit var plannerConfiguration: PlannerConfigurationService
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
    db.update("DELETE FROM planner_configuration WHERE id=1")
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
    configure()
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
    val firstSet = ready(tupleOwner).proposal.snapshot.draft.exercises.single().plannedSets.first()
    assertEquals(12, firstSet.reps)
    assertEquals(15.0, firstSet.weightKg)
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
    val accepted = submitPlanner(owner, rawRequest(gymIds = listOf(gym.toString())))
    jobs.runNext()
    val result = jobs.statusV2(owner, UUID.fromString(accepted.requestId))
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
          submitPlanner(owner, rawRequest(gymIds = listOf(gym.toString())))
        }
        .code,
    )
    db.update("UPDATE standard_records SET archived=false WHERE kind='gym' AND id=?", gym)
    db.update("UPDATE catalog_state SET active=false")
    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> {
          submitPlanner(owner, rawRequest(gymIds = listOf(gym.toString())))
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
      listOf(press.toString(), run.toString()).sorted(),
      simple.map { it["exerciseId"].asString() }.sorted(),
    )
    val pressCandidate = simple.single { it["exerciseId"].asString() == press.toString() }
    assertEquals(150, pressCandidate["priority"].asInt())
    assertFalse(pressCandidate.has("coverage"))

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
  fun `legacy succeeded replay survives later revisions while changed body conflicts`() {
    val owner = owner()
    val exercise = exercise(owner)
    val raw = rawRequest()
    val proposal = refinableProposal(owner, exercise)
    val receipt =
      CalendarDraftResponse(
        json.readTree(raw)["requestId"].asString(),
        CalendarDraftContext(17, 9, capturedAt),
        proposal,
      )
    reserveProcessing(owner, raw, capturedAt + 60000)
    db.update(
      "UPDATE calendar_ai_attempts SET state='SUCCEEDED',receipt=?::jsonb",
      json.writeValueAsString(receipt),
    )
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    db.update("UPDATE catalog_state SET revision=10")
    assertEquals(receipt, actions.calendar(owner, raw))
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> { actions.calendar(owner, raw + byteArrayOf(32)) }.code,
    )
    assertEquals(0, provider.calls)
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
  fun `capture admission and atomic publication failures leave no partial proposal`() {
    val owner = owner()
    repeat(65) { offset ->
      workout(owner, UUID.randomUUID(), capturedAt - offset - 1, capturedAt - offset, emptyList())
    }
    assertEquals("ai_context_too_large", assertThrows<ApiException> { submitPlanner(owner) }.code)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_draft_jobs", Int::class.java))
    reset()
    val second = owner()
    exercise(second)
    hooks.failProposalInsert = true
    val job = submitPlanner(second)
    jobs.runNext()
    assertEquals("FAILED", jobs.statusV2(second, UUID.fromString(job.requestId)).state)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(0, provider.calls)
  }

  @Test
  fun `known cardio duration above the slot fails without creating a proposal`() {
    val owner = owner()
    exercise(owner, type = "CARDIO")
    configure("CARDIO", 1800)
    val job = submitPlanner(owner)
    jobs.runNext()
    assertEquals("IMPOSSIBLE", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `soft minimum shortfall publishes complete bounded plan without filler`() {
    val owner = owner()
    exercise(owner)
    val raw = json.readTree(rawRequest()) as ObjectNode
    raw.put("availableDurationMinutes", 60)
    val result = ready(owner, json.writeValueAsBytes(raw))
    val explanation = explanations.readRaw(owner, result.proposal.proposalId)
    assertEquals("CONSTRAINTS", explanation["shortfallReason"].asString())
    assertTrue(explanation["estimatedSeconds"].asLong() < explanation["minimumSeconds"].asLong())
    assertEquals(3, result.proposal.snapshot.draft.exercises.single().plannedSets.size)
  }

  @Test
  fun `typed timed execution accepts declared duration and rejects provider fields`() {
    val owner = owner()
    val timed = exercise(owner, type = "TIMED")
    configure("TIMED", 45)
    val result = ready(owner)
    assertEquals(timed.toString(), result.proposal.snapshot.draft.exercises.single().exerciseId)
    assertTrue(
      result.proposal.snapshot.draft.exercises.single().plannedSets.all {
        it.durationSec == 45 && it.reps == null
      }
    )
    val malformed = v2Raw().toString(Charsets.UTF_8).dropLast(1) + ",\"providerOutput\":{}}"
    assertThrows<ApiException> { jobs.submitV2(owner, malformed.toByteArray()) }
  }

  @Test
  fun `V2 notes opt in is rejected rather than sent to a provider`() {
    val owner = owner()
    exercise(owner)
    val raw = json.readTree(v2Raw()) as ObjectNode
    raw.put("includeNotes", true)
    assertEquals(
      "invalid_request",
      assertThrows<ApiException> { jobs.submitV2(owner, json.writeValueAsBytes(raw)) }.code,
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `request digest conflict processing replay and expired lease never admit a provider`() {
    for (lease in listOf(capturedAt + 60000, capturedAt - 1)) {
      reset()
      val owner = owner()
      val raw = rawRequest()
      reserveProcessing(owner, raw, lease)
      assertEquals(
        "ai_request_conflict",
        assertThrows<ApiException> { actions.calendar(owner, raw + byteArrayOf(32)) }.code,
      )
      assertEquals(
        "ai_invalid_request",
        assertThrows<ApiException> { actions.calendar(owner, raw) }.code,
      )
      assertEquals(
        "FAILED",
        db.queryForObject("SELECT state FROM calendar_ai_attempts", String::class.java),
      )
      assertEquals(0, provider.calls)
    }
  }

  @Test
  fun `cancellation during deterministic work wins before final proposal commit`() {
    lateinit var job: CalendarDraftJobResponse
    lateinit var identity: Identity
    blockedExecution { owner, current ->
      identity = owner
      job = current
      jobs.cancel(owner, UUID.fromString(current.requestId), 2)
    }
    assertEquals("SUPERSEDED", jobs.statusV2(identity, UUID.fromString(job.requestId)).state)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `owner deletion before final guard leaves no attempt or proposal`() {
    blockedExecution { owner, _ -> db.update("DELETE FROM users WHERE id=?", owner.userId) }
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(0, db.queryForObject("SELECT count(*) FROM calendar_draft_jobs", Int::class.java))
  }

  @Test
  fun `owner deletion after immutable capture prevents execution publication`() {
    val owner = owner()
    exercise(owner)
    val job = submitPlanner(owner)
    db.update("DELETE FROM users WHERE id=?", owner.userId)
    jobs.runNext()
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(0, provider.calls)
    assertEquals(
      404,
      assertThrows<ApiException> { jobs.statusV2(owner(), UUID.fromString(job.requestId)) }.status,
    )
  }

  @Test
  fun `concurrent typed refinement identity binds exactly one base proposal`() {
    val owner = owner()
    val target = exercise(owner)
    val first = ready(owner).proposal
    val second = ready(owner).proposal
    val raw = refineBytes(first, target)
    val gate = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val outcomes =
        listOf(first, second).map { p ->
          pool.submit<String> {
            gate.await()
            runCatching { jobs.submitV2Refinement(owner, p.proposalId, raw) }
              .fold({ it.state }, { (it as ApiException).code })
          }
        }
      gate.countDown()
      val actual = outcomes.map { it.get(5, TimeUnit.SECONDS) }
      assertEquals(1, actual.count { it == "QUEUED" })
      assertEquals(1, actual.count { it == "ai_request_conflict" })
    } finally {
      pool.shutdownNow()
    }
    assertEquals(0, provider.calls)
  }

  @Test
  fun `legacy refinement preserves byte binding and terminalizes without a provider`() {
    val owner = owner()
    val target = exercise(owner)
    val proposal = refinableProposal(owner, target)
    val raw = refinementRequest(UUID.randomUUID())
    assertEquals(
      "ai_invalid_request",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, raw) }.code,
    )
    assertEquals(
      "ai_invalid_request",
      assertThrows<ApiException> { actions.refineCalendar(owner, proposal.proposalId, raw) }.code,
    )
    assertEquals(
      "ai_request_conflict",
      assertThrows<ApiException> {
          actions.refineCalendar(owner, proposal.proposalId, raw + byteArrayOf(32))
        }
        .code,
    )
    assertTrue(
      raw.contentEquals(
        db.queryForObject(
          "SELECT raw_request FROM calendar_planner_refinements",
          ByteArray::class.java,
        )
      )
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `expired typed refinement never changes its original proposal`() {
    val owner = owner()
    val target = exercise(owner)
    val proposal = ready(owner).proposal
    val raw = refineBytes(proposal, target)
    val id = UUID.fromString(json.readTree(raw)["requestId"].asString())
    jobs.submitV2Refinement(owner, proposal.proposalId, raw)
    testClock.currentTime = capturedAt + 3_600_001
    jobs.runNext()
    assertEquals("EXPIRED", jobs.statusV2(owner, id).state)
    assertEquals(1, proposals.detail(owner, proposal.proposalId).currentVersion)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `typed refinement publication rejects lost lease then recovers from captured bytes`() {
    val owner = owner()
    val target = exercise(owner)
    val proposal = ready(owner).proposal
    val raw = refineBytes(proposal, target)
    val queued = jobs.submitV2Refinement(owner, proposal.proposalId, raw)
    val gate = Gate()
    hooks.finalLock = gate
    val task = FutureTask { jobs.runNext() }
    Thread.ofVirtual().start(task)
    assertTrue(gate.reached.await(5, TimeUnit.SECONDS))
    db.update(
      "UPDATE calendar_draft_jobs SET lease_until=? WHERE request_id=?",
      Timestamp(capturedAt - 1),
      UUID.fromString(queued.requestId),
    )
    gate.open()
    task.get(5, TimeUnit.SECONDS)
    hooks.finalLock = null
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, UUID.fromString(queued.requestId)).state)
  }

  @Test
  fun `owner NEVER and NORMAL govern deterministic creation and typed refinement`() {
    val owner = owner()
    val first = exercise(owner)
    val second = exercise(owner)
    record(
      owner,
      "planner_exercise_accents",
      UUID.randomUUID(),
      mapOf(
        "preferences" to
          listOf(
            mapOf("exerciseId" to first.toString(), "preference" to "NEVER"),
            mapOf("exerciseId" to second.toString(), "preference" to "NORMAL"),
          )
      ),
    )
    val result = ready(owner)
    assertEquals(second.toString(), result.proposal.snapshot.draft.exercises.single().exerciseId)
    val forbidden = refineBytes(result.proposal, first)
    val job = jobs.submitV2Refinement(owner, result.proposal.proposalId, forbidden)
    jobs.runNext()
    assertEquals("IMPOSSIBLE", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, provider.calls)
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
      mappings.put(
        owner,
        id,
        PlannerExerciseMappingDto(
          id,
          if (type == "STRENGTH") "HORIZONTAL_PUSH" else if (type == "TIMED") "CORE" else "CARDIO",
          listOf("ACCESSORY", "CONDITIONING", "PRIMARY"),
          DeterministicPlannerRuntime.goals.sorted(),
          type,
          equipment.sorted(),
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
    exercise(owner)
    val raw = rawRequest()
    val job = submitPlanner(owner, raw)
    val gate = Gate()
    hooks.finalLock = gate
    val task = FutureTask { jobs.runNext() }
    Thread.ofVirtual().start(task)
    assertTrue(gate.reached.await(5, TimeUnit.SECONDS))
    assertEquals("RUNNING", submitPlanner(owner, raw).state)
    gate.open()
    task.get(5, TimeUnit.SECONDS)
    hooks.finalLock = null
    val result = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("READY", result.state)
    assertEquals(result, submitPlanner(owner, raw))
    jobs.runNext()
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `V2 replay returns exact committed receipt without engine rerun`() {
    val owner = owner()
    exercise(owner)
    val raw = rawRequest()
    val first = ready(owner, raw)
    val stored =
      db.queryForObject("SELECT result::text FROM calendar_draft_jobs", String::class.java)!!
    val replay = submitPlanner(owner, raw).result!!
    assertEquals(first, replay)
    // Compare the wire DTO: tree conversion retains Long nodes, while parsed JSON can use Int.
    assertEquals(first, json.readValue(stored, CalendarDraftResponse::class.java))
    assertEquals(
      stored,
      db.queryForObject("SELECT result::text FROM calendar_draft_jobs", String::class.java),
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `job acceptance survives lost acknowledgement and only worker creates proposal`() {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val raw = rawRequest()
    val first = submitPlanner(owner, raw)
    assertEquals("QUEUED", first.state)
    assertEquals(first, submitPlanner(owner, raw))
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    jobs.runNext()
    val ready = jobs.statusV2(owner, UUID.fromString(first.requestId))
    assertEquals("READY", ready.state)
    assertEquals(first.requestId, ready.result!!.requestId)
    assertEquals(ready, submitPlanner(owner, raw))
    jobs.runNext()
    assertEquals(0, provider.calls)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(
      0,
      db.queryForObject("SELECT count(*) FROM records WHERE kind='calendar_plan'", Int::class.java),
    )
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1])
  fun `identical independent jobs each produce a visible eligible proposal`(approvedIndex: Int) {
    val owner = owner()
    val exercise = exercise(owner)
    provider.handler = { providerResponse(exercise) }
    val firstRaw = jobRequest()
    val secondRequest = json.readTree(firstRaw) as ObjectNode
    secondRequest.put("requestId", UUID.randomUUID().toString())
    val secondRaw = json.writeValueAsBytes(secondRequest)
    val first = submitPlanner(owner, firstRaw)
    testClock.currentTime++
    val second = submitPlanner(owner, secondRaw)

    assertEquals("QUEUED", first.state)
    assertEquals("QUEUED", second.state)
    assertNotEquals(first.requestId, second.requestId)
    assertEquals(first, submitPlanner(owner, firstRaw))
    assertEquals(2, db.queryForObject("SELECT count(*) FROM calendar_draft_jobs", Int::class.java))
    assertEquals(0, provider.calls)

    jobs.runNext()
    val firstReady = jobs.statusV2(owner, UUID.fromString(first.requestId))
    assertEquals("READY", firstReady.state)
    assertEquals("QUEUED", jobs.statusV2(owner, UUID.fromString(second.requestId)).state)
    jobs.runNext()
    val secondReady = jobs.statusV2(owner, UUID.fromString(second.requestId))
    assertEquals("READY", secondReady.state)
    assertEquals(firstReady, submitPlanner(owner, firstRaw))
    assertEquals(secondReady, submitPlanner(owner, secondRaw))
    jobs.runNext()
    assertEquals(0, provider.calls)
    val results = listOf(firstReady.result!!, secondReady.result!!)
    assertEquals(listOf(first.requestId, second.requestId), results.map { it.requestId })
    val proposalIds = results.map { it.proposal.proposalId }.toSet()
    assertEquals(2, proposalIds.size)
    assertEquals(proposalIds, proposals.list(owner, 50, null).items.map { it.proposalId }.toSet())
    results.forEach { assertEquals(it.proposal, proposals.detail(owner, it.proposal.proposalId)) }

    // Each parameter has a fresh owner/context, so neither approval bypasses revision validation.
    val proposal = results[approvedIndex].proposal
    val request =
      tech.valerochkagym.controller.model.ApprovalRequest(
        UUID.randomUUID().toString(),
        proposal.currentVersion,
        proposal.snapshot.draft,
      )
    val approved =
      proposals.approve(
        owner,
        proposal.proposalId,
        json.writeValueAsBytes(request),
        "0".repeat(64),
        request,
      )
    assertEquals(proposal.proposalId, approved.proposalId)
    assertEquals(18, approved.revision)
  }

  @Test
  fun `new ordinary job stays queued while an earlier running job publishes`() {
    lateinit var first: CalendarDraftJobResponse
    lateinit var second: CalendarDraftJobResponse
    lateinit var identity: Identity
    blockedExecution { owner, current ->
      identity = owner
      first = current
      testClock.currentTime++
      second = submitPlanner(owner)
      assertEquals("QUEUED", second.state)
    }
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(first.requestId)).state)
    assertEquals("QUEUED", jobs.statusV2(identity, UUID.fromString(second.requestId)).state)
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(second.requestId)).state)
  }

  @Test
  fun `later job completing first does not overwrite an earlier running result`() {
    lateinit var first: CalendarDraftJobResponse
    lateinit var second: CalendarDraftJobResponse
    lateinit var identity: Identity
    blockedExecution { owner, current ->
      identity = owner
      first = current
      testClock.currentTime++
      second = submitPlanner(owner)
      hooks.finalLock = null
      jobs.runNext()
      assertEquals("READY", jobs.statusV2(owner, UUID.fromString(second.requestId)).state)
    }
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(first.requestId)).state)
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(second.requestId)).state)
    assertEquals(2, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `failed job leaves another independent queued job able to finish`() {
    val owner = owner()
    exercise(owner)
    val first = submitPlanner(owner)
    testClock.currentTime++
    val second = submitPlanner(owner)
    hooks.failProposalInsert = true
    jobs.runNext()
    hooks.failProposalInsert = false
    assertEquals("FAILED", jobs.statusV2(owner, UUID.fromString(first.requestId)).state)
    assertEquals("QUEUED", jobs.statusV2(owner, UUID.fromString(second.requestId)).state)
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, UUID.fromString(second.requestId)).state)
  }

  @Test
  fun `out of order replacement lineage tombstones never restore old requests`() {
    val owner = owner()
    val unrelated = jobs.submit(owner, jobRequest())
    val a = rawRequest()
    val b = rawRequest()
    val aId = json.readTree(a)["requestId"].asString()
    val bId = json.readTree(b)["requestId"].asString()
    val c = json.readTree(rawRequest()) as ObjectNode
    c.putArray("replacesRequestIds").add(aId).add(bId)
    val current = jobs.submit(owner, json.writeValueAsBytes(c))
    assertEquals("SUPERSEDED", jobs.submit(owner, a).state)
    assertEquals("SUPERSEDED", jobs.submit(owner, b).state)
    assertEquals("FAILED", jobs.status(owner, UUID.fromString(current.requestId)).state)
    assertEquals(unrelated, jobs.status(owner, UUID.fromString(unrelated.requestId)))
    assertEquals(
      2,
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
    val job = submitPlanner(owner, rawRequest())
    db.update(
      "UPDATE calendar_draft_jobs SET state='RUNNING',executions=1,lease_token=?,lease_until=?",
      UUID.randomUUID(),
      Timestamp.from(Instant.ofEpochMilli(capturedAt - 1)),
    )
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(
      2,
      db.queryForObject("SELECT executions FROM calendar_draft_jobs", Int::class.java),
    )
    jobs.runNext()
    assertEquals(0, provider.calls)
  }

  @Test
  fun `restart retries are bounded`() {
    val owner = owner()
    val job = submitPlanner(owner, rawRequest())
    db.update(
      "UPDATE calendar_draft_jobs SET state='RUNNING',executions=3,lease_token=?,lease_until=?",
      UUID.randomUUID(),
      Timestamp.from(Instant.ofEpochMilli(capturedAt - 1)),
    )
    jobs.runNext()
    assertEquals("FAILED", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `explicit cancel fences late deterministic publication without touching independent request`() {
    lateinit var identity: Identity
    lateinit var old: CalendarDraftJobResponse
    lateinit var unrelated: CalendarDraftJobResponse
    blockedExecution { owner, current ->
      identity = owner
      old = current
      testClock.currentTime++
      unrelated = submitPlanner(owner)
      jobs.cancel(owner, UUID.fromString(current.requestId), 2)
    }
    assertEquals("SUPERSEDED", jobs.statusV2(identity, UUID.fromString(old.requestId)).state)
    assertEquals("QUEUED", jobs.statusV2(identity, UUID.fromString(unrelated.requestId)).state)
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(unrelated.requestId)).state)
  }

  @Test
  fun `cancelling ready request fences its approval and preserves independent ready proposal`() {
    val owner = owner()
    exercise(owner)
    val first = ready(owner)
    val other = ready(owner)
    jobs.cancel(owner, UUID.fromString(first.requestId), 2)
    val request = ApprovalRequest(UUID.randomUUID().toString(), 1, first.proposal.snapshot.draft)
    assertEquals(
      "proposal_stale",
      assertThrows<ApiException> {
          proposals.approve(
            owner,
            first.proposal.proposalId,
            json.writeValueAsBytes(request),
            "0".repeat(64),
            request,
          )
        }
        .code,
    )
    assertEquals("READY", jobs.statusV2(owner, UUID.fromString(other.requestId)).state)
    assertEquals(first.proposal, proposals.detail(owner, first.proposal.proposalId))
  }

  @Test
  fun `changed history invalidates job before provider and other owner cannot see it`() {
    val owner = owner()
    val other = owner()
    val job = submitPlanner(owner, rawRequest())
    assertEquals(
      404,
      assertThrows<ApiException> { jobs.statusV2(other, UUID.fromString(job.requestId)) }.status,
    )
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    jobs.runNext()
    assertEquals("STALE", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
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
  fun `publication failure persists only fixed code and rolls back explanation`() {
    val owner = owner()
    exercise(owner)
    val job = submitPlanner(owner)
    hooks.failProposalInsert = true
    jobs.runNext()
    val failed = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("FAILED", failed.state)
    assertEquals("ai_invalid_response", failed.errorCode)
    assertNull(failed.result)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM planner_explanations", Int::class.java))
    assertEquals(0, provider.calls)
  }

  @Test
  fun `unavailable provider neither blocks planner nor emits provider diagnostics`() {
    val owner = owner()
    exercise(owner)
    provider.available = false
    val before = diagnostics.snapshot().map { it.id }
    val result = ready(owner)
    assertEquals("RULE_BASED", result.proposal.source)
    assertEquals(0, provider.calls)
    assertEquals(before, diagnostics.snapshot().map { it.id })
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
    val job = submitPlanner(owner, rawRequest())
    jobs.runNext()
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    val stale = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("STALE", stale.state)
    assertNull(stale.result)
  }

  @Test
  fun `revoked session cannot execute a queued job`() {
    val owner = owner()
    val job = submitPlanner(owner, rawRequest())
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
    lateinit var identity: Identity
    lateinit var job: CalendarDraftJobResponse
    blockedExecution { owner, current ->
      identity = owner
      job = current
      hooks.finalLock = null
      db.update(
        "UPDATE calendar_draft_jobs SET lease_until=? WHERE request_id=?",
        Timestamp(capturedAt - 1),
        UUID.fromString(job.requestId),
      )
      jobs.runNext()
    }
    assertEquals("READY", jobs.statusV2(identity, UUID.fromString(job.requestId)).state)
    assertEquals(1, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
    assertEquals(0, provider.calls)
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
    val job = submitPlanner(owner, rawRequest())
    jobs.runNext()
    val ready = jobs.statusV2(owner, UUID.fromString(job.requestId)).result!!
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
  fun `persisted explanation attributes repeated exercise to factual continuity`() {
    val owner = owner()
    val target = exercise(owner)
    workout(owner, UUID.randomUUID(), capturedAt - 100, capturedAt - 1, sets(target, 3))
    val context = providerContext(owner)
    assertTrue(context.has("completedMuscleCoverage"))
    val result = ready(owner)
    val explanation = explanations.readRaw(owner, result.proposal.proposalId)
    assertEquals("RULE_BASED", explanation["selectionReason"].asString())
    assertEquals("CONTINUITY", explanation["repeatReason"].asString())
    assertEquals(0, provider.calls)
  }

  @Test
  fun `creation and refinement freeze factual history without notes or provider calls`() {
    val owner = owner()
    val target = exercise(owner)
    val replacement = exercise(owner)
    workout(
      owner,
      UUID.randomUUID(),
      capturedAt - 100,
      capturedAt - 1,
      sets(target, 3, actual = 50.0, actualReps = 10, note = "private-set-note"),
    )
    val result = ready(owner)
    val firstSnapshot =
      db.queryForObject(
        "SELECT execution_snapshot::text FROM calendar_draft_jobs WHERE request_id=?",
        String::class.java,
        UUID.fromString(result.requestId),
      )!!
    assertTrue(json.readTree(firstSnapshot)["facts"].size() >= 3)
    assertFalse(firstSnapshot.contains("private-set-note"))
    val raw = refineBytes(result.proposal, replacement)
    jobs.submitV2Refinement(owner, result.proposal.proposalId, raw)
    jobs.runNext()
    val refined = jobs.statusV2(owner, UUID.fromString(json.readTree(raw)["requestId"].asString()))
    assertEquals("READY", refined.state)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `new explicit variant produces independent ready proposal without repair model`() {
    val owner = owner()
    exercise(owner)
    val first = ready(owner)
    val raw = json.readTree(v2Raw()) as ObjectNode
    raw.put("variant", 1)
    val job = jobs.submitV2(owner, json.writeValueAsBytes(raw))
    jobs.runNext()
    val alternate = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("READY", alternate.state)
    assertNotEquals(first.proposal.proposalId, alternate.result!!.proposal.proposalId)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `configuration changes do not rewrite an admitted immutable history snapshot`() {
    val owner = owner()
    exercise(owner)
    val raw = rawRequest()
    val job = submitPlanner(owner, raw)
    val before =
      db.queryForObject(
        "SELECT execution_snapshot::text FROM calendar_draft_jobs",
        String::class.java,
      )
    plannerConfiguration.save(plannerConfiguration.snapshot().copy(weightStepKg = 5.0))
    jobs.runNext()
    assertEquals("READY", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(
      before,
      db.queryForObject(
        "SELECT execution_snapshot::text FROM calendar_draft_jobs",
        String::class.java,
      ),
    )
    assertEquals(0, provider.calls)
  }

  private fun configure(type: String = "STRENGTH", seconds: Int = 45) {
    val original = plannerConfiguration.snapshot()
    val slot =
      PlannerPatternSlot(
        "PRIMARY",
        "Explicit fixture",
        type,
        sets = 3,
        repsMin = 8,
        repsMax = 12,
        durationSeconds = if (type == "STRENGTH") 0 else seconds,
        slotId = "slot",
        movementClass =
          if (type == "STRENGTH") "HORIZONTAL_PUSH" else if (type == "TIMED") "CORE" else "CARDIO",
        targetTotalReps = if (type == "STRENGTH") 30 else null,
        targetIntensityBasisPoints = if (type == "STRENGTH") 6949 else null,
      )
    plannerConfiguration.save(
      original.copy(
        collections =
          original.collections.map { c ->
            c.copy(
              patterns =
                listOf(PlannerPattern("${c.id}-fixture", "Fixture", "FULL_BODY", "", listOf(slot)))
            )
          }
      )
    )
  }

  private fun v2Raw(raw: ByteArray = rawRequest()): ByteArray {
    val root = json.readTree(raw) as ObjectNode
    listOf("preferences", "currentState", "replacesRequestId", "replacesRequestIds")
      .forEach(root::remove)
    root.put("includeNotes", false)
    root.put("variant", 0)
    return json.writeValueAsBytes(root)
  }

  private fun submitPlanner(
    owner: Identity,
    raw: ByteArray = rawRequest(),
  ): CalendarDraftJobResponse = jobs.submitV2(owner, v2Raw(raw))

  private fun ready(owner: Identity, raw: ByteArray = rawRequest()): CalendarDraftResponse {
    val job = submitPlanner(owner, raw)
    jobs.runNext()
    val result = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("READY", result.state, result.errorCode)
    assertEquals(0, provider.calls)
    return result.result!!
  }

  private fun refineBytes(
    proposal: ProposalResponse,
    target: UUID,
    requestId: UUID = UUID.randomUUID(),
  ) =
    json.writeValueAsBytes(
      PlannerV2RefinementRequest(
        requestId,
        0,
        17,
        9,
        1,
        proposal.snapshot.draft,
        listOf(PlannerV2Change("REPLACE", "slot", "slot-1", target)),
      )
    )

  private fun blockedExecution(action: (Identity, CalendarDraftJobResponse) -> Unit) {
    val owner = owner()
    exercise(owner)
    configure()
    val job = submitPlanner(owner)
    val gate = Gate()
    hooks.finalLock = gate
    val task = FutureTask { jobs.runNext() }
    Thread.ofVirtual().start(task)
    assertTrue(gate.reached.await(5, TimeUnit.SECONDS))
    try {
      action(owner, job)
    } finally {
      gate.open()
    }
    task.get(5, TimeUnit.SECONDS)
    hooks.finalLock = null
  }

  private fun jobRequest(replacesRequestIds: List<String> = emptyList()): ByteArray {
    val request = json.readTree(rawRequest()) as ObjectNode
    val replacements = request.putArray("replacesRequestIds")
    replacesRequestIds.forEach { replacements.add(it) }
    return json.writeValueAsBytes(request)
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
    val request =
      json.readValue(
        rawRequest(gymIds, excludedEquipment, priority),
        CalendarDraftRequest::class.java,
      )
    val captured = contexts.captureCalendar(owner, 17, 9, request.timeZoneId, false, gymIds)
    val selected =
      CalendarCandidateSelector.eligible(
        captured.candidates,
        captured.gyms,
        request,
        captured.facts,
        captured.profile?.trainingGoal,
      )
    return json.readTree(
      CalendarPlannerContext.serializeAgentic(json, captured, request, selected, selected.size)
    )
  }

  private fun projectedWeight(owner: Identity, exercise: UUID): Double? {
    return PlannerWeightEngine.calculate(
      capture(owner).facts,
      exercise.toString(),
      8,
      capturedAt,
      2.5,
    )
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
