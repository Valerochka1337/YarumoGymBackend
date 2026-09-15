package tech.valerochkagym

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import tech.valerochkagym.service.ai.AiProvider
import tech.valerochkagym.service.ai.AiProviderInput
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Local fake-provider regressions for the compact STRENGTH planner boundary. */
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

  class FakeProvider : AiProvider {
    override val available = true
    var calls = 0
    var handler: (AiProviderInput) -> JsonNode = { error("test handler absent") }

    override fun generate(input: AiProviderInput): JsonNode {
      calls++
      return try {
        plannerFixture(input, handler(input))
      } catch (error: Exception) {
        throw AssertionError("Synthetic provider fixture failed", error)
      }
    }
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
  }

  @Test
  fun `strength context sends compact actual facts chooses high focus and assigns older weight`() {
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

    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertEquals(focus.toString(), context["selection"]["focusExerciseId"].asString())
      assertFalse(context.has("history"))
      assertFalse(context.has("mass"))
      assertFalse(context.toString().contains("lastObservationIds"))
      assertFalse(context.toString().contains("observationId"))
      assertFalse(context.toString().contains(oldWorkout.toString()))
      assertFalse(context.toString().contains(currentWorkout.toString()))
      val facts = context["strengthFacts"]
      assertEquals("strength-compact-v1", facts["version"].asString())
      val latest = facts["latest"].single { it["exerciseId"].asString() == focus.toString() }
      assertEquals("ACTUAL", latest["weightSource"].asString())
      assertEquals(70.0, latest["weightKg"].asDouble())
      assertEquals("ACTUAL", latest["repsSource"].asString())
      assertEquals(5.0, latest["reps"].asDouble())
      val last7 = facts["movementUnits"]["last7Days"].single()
      assertEquals(current.toString(), last7["exerciseId"].asString())
      assertEquals(300.0, last7["actualVolume"].asDouble())
      assertEquals(listOf("HARD"), facts["efforts"].toList().map { it["effort"].asString() })
      assertFalse(facts.toString().contains(oldWorkout.toString()))
      assertFalse(facts.toString().contains(currentWorkout.toString()))
      response(focus)
    }

    val result = actions.calendar(owner, request())
    assertEquals(
      70.0,
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"][0]["weightKg"]
        .asDouble(),
    )
  }

  @Test
  fun `strength focus must be in the provider plan`() {
    val owner = owner()
    val focus = exercise(owner)
    val alternative = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(focus to "HIGH"))
    provider.handler = { response(alternative) }

    assertEquals(
      "ai_invalid_response",
      assertThrows<ApiException> { actions.calendar(owner, request()) }.code,
    )
    assertEquals(1, provider.calls)
    assertEquals(0, db.queryForObject("SELECT count(*) FROM training_proposals", Int::class.java))
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

    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> { actions.calendar(owner, request()) }.code,
    )
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
    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertEquals(live.toString(), context["selection"]["focusExerciseId"].asString())
      assertFalse(context["candidates"].any { it["exerciseId"].asString() == stale.toString() })
      val latest = context["strengthFacts"]["latest"].single()
      assertEquals("ACTUAL", latest["weightSource"].asString())
      assertTrue(latest["weightKg"].isNull)
      assertTrue(latest["reps"].isNull)
      response(live)
    }

    val result = actions.calendar(owner, request())
    assertTrue(
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"][0]["weightKg"]
        .isNull
    )
  }

  @Test
  fun `nonstrength planning omits compact facts and keeps legacy weight carryover`() {
    val owner = owner()
    val target = exercise(owner)
    profile(owner, "MUSCLE_GAIN")
    workout(
      owner,
      capturedAt - day,
      sets(target, 1, null, null, legacyWeight = 63.5, actualPresent = false),
    )
    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertFalse(context.has("strengthFacts"))
      assertFalse(context["selection"].has("focusExerciseId"))
      response(target)
    }

    val result = actions.calendar(owner, request())
    assertEquals(
      63.5,
      json
        .valueToTree<JsonNode>(result)["proposal"]["snapshot"]["draft"]["exercises"][0][
          "plannedSets"][0]["weightKg"]
        .asDouble(),
    )
  }

  @Test
  fun `live excluded high key cannot appear in strength candidates or focus`() {
    val owner = owner()
    val excluded = exercise(owner)
    val allowed = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(excluded to "HIGH"))
    provider.handler = { input ->
      val context = json.readTree(input.context)
      assertEquals(allowed.toString(), context["selection"]["focusExerciseId"].asString())
      assertFalse(context["candidates"].any { it["exerciseId"].asString() == excluded.toString() })
      assertFalse(context["strengthFacts"].toString().contains(excluded.toString()))
      response(allowed)
    }
    actions.calendar(owner, request(listOf(excluded.toString())))
    assertEquals(1, provider.calls)
  }

  @Test
  fun `strength revision changes during provider work prevent proposal persistence`() {
    val owner = owner()
    val focus = exercise(owner)
    profile(owner, "STRENGTH")
    strengthProfile(owner, listOf(focus to "HIGH"))
    provider.handler = {
      db.update("UPDATE sync_heads SET revision=18 WHERE user_id=?", owner.userId)
      response(focus)
    }
    assertEquals(
      "ai_context_stale",
      assertThrows<ApiException> { actions.calendar(owner, request()) }.code,
    )
    assertEquals(1, provider.calls)
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
      """{"result":{"name":"Draft","exercises":[{"exerciseId":"$exercise","restSeconds":0,"plannedSets":[{"reps":8,"durationSec":null}]}]}}"""
    )

  private val day = 86_400_000L
}
