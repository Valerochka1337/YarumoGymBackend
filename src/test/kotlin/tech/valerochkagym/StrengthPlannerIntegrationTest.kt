package tech.valerochkagym

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.ai.AiActionService
import tech.valerochkagym.service.ai.AiProviderInput
import tech.valerochkagym.service.ai.PlannerToolCallingProvider
import tech.valerochkagym.service.ai.PlannerTurn
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Provider-free strength capture, owner facts, eligibility and actual-weight regressions. */
@Testcontainers
@SpringBootTest(
  classes = [Application::class, StrengthPlannerIntegrationTest.Fakes::class],
  properties = ["gym.calendar-jobs.enabled=false"],
)
class StrengthPlannerIntegrationTest {
  companion object {
    private const val capturedAt = 1_805_005_800_000L

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
    override val available = true
    var calls = 0
    var handler: (AiProviderInput) -> JsonNode = { error("test handler absent") }

    override fun generate(input: AiProviderInput): JsonNode {
      calls++
      return try {
        handler(input)
      } catch (error: Exception) {
        throw AssertionError("Synthetic provider fixture failed", error)
      }
    }

    override fun generatePlannerTurn(input: AiProviderInput): PlannerTurn =
      TestPlannerTurns.turn(input, ::generate)
  }

  class FixedClock : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(capturedAt)

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this
  }

  @TestConfiguration
  class Fakes {
    @Bean @Primary fun provider() = FakeProvider()

    @Bean @Primary fun fixedStrengthPlannerClock() = FixedClock()
  }

  @Autowired lateinit var jobs: CalendarDraftJobService
  @Autowired lateinit var configuration: PlannerConfigurationService
  @Autowired lateinit var mappings: PlannerMovementMappingService
  @Autowired lateinit var contexts: tech.valerochkagym.service.ai.AiContextReader
  @Autowired lateinit var actions: AiActionService
  @Autowired lateinit var provider: FakeProvider
  @Autowired lateinit var db: JdbcTemplate
  @Autowired lateinit var json: ObjectMapper

  @BeforeEach
  @AfterEach
  fun reset() {
    db.execute(
      "TRUNCATE sessions,refresh_tokens,email_challenges,google_nonces,rate_limits,users,standard_records CASCADE"
    )
    db.update("UPDATE catalog_state SET revision=9,active=false")
    provider.calls = 0
    db.update("DELETE FROM planner_configuration")
    val value = configuration.snapshot()
    val slot =
      PlannerPatternSlot(
        "PRIMARY",
        "Explicit strength fixture",
        sets = 3,
        repsMin = 8,
        repsMax = 12,
        slotId = "strength",
        movementClass = "HORIZONTAL_PUSH",
        targetTotalReps = 30,
        targetIntensityBasisPoints = 6949,
      )
    configuration.save(
      value.copy(
        collections =
          value.collections.map { c ->
            c.copy(
              patterns =
                listOf(PlannerPattern("${c.id}-test", "Test", "FULL_BODY", "", listOf(slot)))
            )
          }
      )
    )
  }

  @Test
  fun `strength capture retains bounded owner history and computes historical weight without a provider`() {
    val owner = owner()
    val focus = exercise(owner)
    val current = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(focus to "HIGH", current to "NORMAL"))
    repeat(3) { offset ->
      workout(owner, capturedAt - (29L + offset) * day, sets(current, 1, 30.0, 5.0))
    }
    val oldWorkout = UUID.randomUUID()
    workout(owner, capturedAt - 33 * day, sets(focus, 1, 70.0, 5.0), oldWorkout)
    val currentWorkout = UUID.randomUUID()
    workout(owner, capturedAt - 2 * day, sets(current, 2, 30.0, 5.0), currentWorkout)
    effort(owner, currentWorkout, "HARD")
    val captured = contexts.captureCalendar(owner, 17, 9, "UTC", false, emptyList())
    val historical =
      contexts.captureStrengthPlannerFacts(
        owner,
        17,
        9,
        setOf(focus.toString(), current.toString()),
        capturedAt,
        captured.workouts.mapTo(mutableSetOf()) { it.id },
      )
    assertEquals(2, captured.facts.size)
    assertEquals(2, historical.latestFacts.size)
    assertEquals(listOf("HARD"), historical.efforts.map { it.effort })

    val result = ready(owner, request())
    assertEquals(
      3,
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"]
        .size(),
    )
    assertEquals(focus.toString(), result.proposal.snapshot.draft.exercises.single().exerciseId)
    assertTrue(
      result.proposal.snapshot.draft.exercises.single().plannedSets.all { it.weightKg != null }
    )
    assertEquals(0, provider.calls)
  }

  @Test
  fun `strength planning uses eligible alternative when focus is explicitly excluded`() {
    val owner = owner()
    val focus = exercise(owner)
    val alternative = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(focus to "HIGH"))
    val result = ready(owner, request(listOf(focus.toString())))
    assertEquals(0, provider.calls)
    assertEquals(
      alternative.toString(),
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "exerciseId"]
        .asString(),
    )
  }

  @Test
  fun `strength planning with no eligible live candidate fails before provider admission`() {
    val owner = owner()
    val stale = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(stale to "HIGH"))
    db.update(
      "UPDATE records SET deleted=true,payload=null WHERE user_id=? AND kind='exercise' AND id=?",
      owner.userId,
      stale,
    )

    val job = submit(owner, request())
    jobs.runNext()
    val result = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("IMPOSSIBLE", result.state)
    assertEquals("NO_FEASIBLE_PLAN", result.errorCode)
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `null actual and stale strength keys do not fall back to legacy values`() {
    val owner = owner()
    val stale = exercise(owner)
    val live = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(stale to "HIGH", live to "NORMAL"))
    db.update(
      "UPDATE records SET deleted=true,payload=null WHERE user_id=? AND kind='exercise' AND id=?",
      owner.userId,
      stale,
    )
    workout(
      owner,
      capturedAt - day,
      sets(live, 1, null, null, legacyWeight = 91.0, actualPresent = true),
    )
    val result = ready(owner, request())
    assertTrue(
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"][0]["weightKg"]
        .isNull
    )
  }

  @Test
  fun `nonstrength planning omits strength facts and does not carry legacy weight`() {
    val owner = owner()
    val target = exercise(owner)
    profile(owner, "MUSCLE_GAIN")
    workout(
      owner,
      capturedAt - day,
      sets(target, 1, null, null, legacyWeight = 63.5, actualPresent = false),
    )
    val result = ready(owner, request())
    assertTrue(
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"][0]["weightKg"]
        .isNull
    )
  }

  @Test
  fun `live excluded high key cannot appear in strength candidates or focus`() {
    val owner = owner()
    val excluded = exercise(owner)
    val allowed = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(excluded to "HIGH"))
    val result = ready(owner, request(listOf(excluded.toString())))
    assertEquals(allowed.toString(), result.proposal.snapshot.draft.exercises.single().exerciseId)
    assertEquals(0, provider.calls)
  }

  @Test
  fun `strength revision changes after capture prevent deterministic publication`() {
    val owner = owner()
    val focus = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(focus to "HIGH"))
    val job = submit(owner, request())
    db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
    jobs.runNext()
    assertEquals("STALE", jobs.statusV2(owner, UUID.fromString(job.requestId)).state)
    assertEquals(0, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
  }

  @Test
  fun `strength latest lookup ignores other owners invalid times and incompatible set shapes`() {
    val owner = owner()
    val focus = exercise(owner)
    profile(owner, "STRENGTH")
    workout(owner, capturedAt - 40 * day, sets(focus, 1, 45.0, 5.0))
    workout(owner(), capturedAt - day, sets(focus, 1, 999.0, 8.0))
    val invalid = UUID.randomUUID()
    workout(owner, capturedAt - day, sets(focus, 1, 888.0, 8.0), invalid)
    db.update(
      "UPDATE records SET payload=jsonb_set(payload,'{exercises,0,sets,0,completedAt}','0') WHERE user_id=? AND id=?",
      owner.userId,
      invalid,
    )
    val timed = UUID.randomUUID()
    workout(owner, capturedAt - 2 * day, sets(focus, 1, 777.0, 8.0), timed)
    db.update(
      "UPDATE records SET payload=jsonb_set(payload,'{exercises,0,sets,0,setType}','\"CARDIO\"') WHERE user_id=? AND id=?",
      owner.userId,
      timed,
    )
    val captured =
      contexts.captureStrengthPlannerFacts(
        owner,
        17,
        9,
        setOf(focus.toString()),
        capturedAt,
        emptySet(),
      )
    assertEquals(45.0, captured.latestFacts.single().actualWeightKg)
    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> {
          contexts.captureStrengthPlannerFacts(
            owner,
            16,
            9,
            setOf(focus.toString()),
            capturedAt,
            emptySet(),
          )
        }
        .code,
    )
  }

  private fun owner(): Identity {
    val userId = UUID.randomUUID()
    val sessionId = UUID.randomUUID()
    db.update(
      "INSERT INTO users(id,email,email_verified) VALUES (?,?,true)",
      userId,
      "$userId@example.com",
    )
    db.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'test',?,TIMESTAMPTZ '2100-01-01',TIMESTAMPTZ '2100-01-01')",
      sessionId,
      userId,
      sessionId.toString().replace("-", "").repeat(2),
    )
    db.update("INSERT INTO sync_heads(user_id,revision) VALUES (?,17)", userId)
    return Identity(userId, sessionId, "$userId@example.com")
  }

  private fun profile(owner: Identity, goal: String) =
    record(
      owner,
      "profile",
      UUID.randomUUID(),
      mapOf(
        "trainingGoal" to goal,
        "sex" to null,
        "birthDate" to null,
        "experienceLevel" to null,
        "plannedSessionsPerWeek" to null,
        "preferredSessionDurationMinutes" to null,
        "manualConstraints" to null,
        "equipmentIds" to emptyList<String>(),
      ),
    )

  private fun strengthProfile(owner: Identity, keys: List<Pair<UUID, String>>) =
    record(
      owner,
      "strength_planner_profile",
      UUID.randomUUID(),
      mapOf(
        "schemaVersion" to 1,
        "syncId" to UUID.randomUUID().toString(),
        "updatedAt" to capturedAt,
        "keyExercises" to
          keys.map { (id, priority) -> mapOf("exerciseId" to id, "priority" to priority) },
      ),
    )

  private fun effort(owner: Identity, workout: UUID, value: String) =
    record(
      owner,
      "workout_effort",
      UUID.randomUUID(),
      mapOf(
        "schemaVersion" to 1,
        "syncId" to UUID.randomUUID().toString(),
        "workoutId" to workout,
        "updatedAt" to capturedAt,
        "effort" to value,
      ),
    )

  private fun exercise(owner: Identity): UUID =
    UUID.randomUUID().also { id ->
      record(
        owner,
        "exercise",
        id,
        mapOf(
          "name" to id.toString(),
          "type" to "STRENGTH",
          "muscles" to listOf(mapOf("muscle" to "QUADS", "contribution" to 100)),
          "equipmentIds" to emptyList<String>(),
          "equipmentRequirementState" to "KNOWN",
        ),
      )
    }

  private fun workout(
    owner: Identity,
    finishedAt: Long,
    sections: List<Map<String, Any>>,
    id: UUID = UUID.randomUUID(),
  ) =
    record(
      owner,
      "workout",
      id,
      mapOf("startedAt" to finishedAt - 1_000, "finishedAt" to finishedAt, "exercises" to sections),
    )

  private fun sets(
    exercise: UUID,
    count: Int,
    actualWeight: Double?,
    actualReps: Double?,
    legacyWeight: Double? = null,
    actualPresent: Boolean = true,
  ) =
    listOf(
      mapOf(
        "exerciseId" to exercise.toString(),
        "sectionId" to UUID.randomUUID().toString(),
        "sets" to
          List(count) {
            buildMap<String, Any?> {
              put("isCompleted", true)
              put("setType", "WORK")
              if (actualPresent) {
                put("actualWeightKg", actualWeight)
                put("actualReps", actualReps)
              }
              legacyWeight?.let { put("weightKg", it) }
            }
          },
      )
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

  private fun submit(owner: Identity, raw: ByteArray): CalendarDraftJobResponse {
    val captured = contexts.captureCalendar(owner, 17, 9, "UTC", false, emptyList())
    captured.candidates.forEach { row ->
      mappings.put(
        owner,
        UUID.fromString(row.id),
        PlannerExerciseMappingDto(
          UUID.fromString(row.id),
          "HORIZONTAL_PUSH",
          listOf("ACCESSORY", "PRIMARY"),
          DeterministicPlannerRuntime.goals.sorted(),
          "STRENGTH",
          emptyList(),
        ),
      )
    }
    val request = json.readTree(raw) as ObjectNode
    request.remove("currentState")
    request.remove("preferences")
    request.put("variant", 0)
    return jobs.submitV2(owner, json.writeValueAsBytes(request))
  }

  private fun ready(owner: Identity, raw: ByteArray): CalendarDraftResponse {
    val job = submit(owner, raw)
    jobs.runNext()
    val result = jobs.statusV2(owner, UUID.fromString(job.requestId))
    assertEquals("READY", result.state, result.errorCode)
    return result.result!!
  }

  private fun request(excluded: List<String> = emptyList()) =
    json.writeValueAsBytes(
      mapOf(
        "requestId" to UUID.randomUUID().toString(),
        "expectedRevision" to 17,
        "expectedCatalogRevision" to 9,
        "startsAtMillis" to capturedAt + 3_600_000,
        "timeZoneId" to "UTC",
        "gymIds" to emptyList<String>(),
        "excludedExerciseIds" to excluded,
        "excludedEquipmentIds" to emptyList<String>(),
        "priorityMuscles" to emptyList<String>(),
        "includeNotes" to false,
        "availableDurationMinutes" to 45,
        "currentState" to null,
        "preferences" to null,
      )
    )

  private fun response(exercise: UUID) =
    json.readTree(
      """{"result":{"name":"Draft","exercises":[{"exerciseId":"$exercise","restSeconds":190,"plannedSets":[{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null},{"reps":8,"durationSec":null}]}]}}"""
    )

  private val day = 86_400_000L
}
