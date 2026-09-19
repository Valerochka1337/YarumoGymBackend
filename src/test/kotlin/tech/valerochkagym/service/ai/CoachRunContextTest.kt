package tech.valerochkagym.service.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper

@Testcontainers
class CoachRunContextTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
        "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
      )
  }

  private val json = JsonMapper.builder().build()
  private lateinit var db: JdbcTemplate
  private lateinit var context: CoachRunContext
  private val owner = UUID.randomUUID()
  private val foreign = UUID.randomUUID()
  private val exercise = UUID.randomUUID()

  @BeforeEach
  fun setup() {
    db =
      JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    db.execute("DROP TABLE IF EXISTS records,standard_records,catalog_state")
    db.execute(
      "CREATE TABLE records(user_id uuid,kind text,id uuid,deleted boolean default false,payload jsonb)"
    )
    db.execute(
      "CREATE TABLE standard_records(kind text,id uuid,archived boolean default false,payload jsonb)"
    )
    db.execute("CREATE TABLE catalog_state(id int,active boolean)")
    db.update("INSERT INTO catalog_state VALUES(1,true)")
    context = CoachRunContext(db, json)
    record(
      owner,
      "exercise",
      exercise,
      mapOf(
        "name" to "Присед",
        "type" to "STRENGTH",
        "equipmentIds" to listOf("barbell"),
        "muscles" to listOf(mapOf("muscle" to "QUADS", "contribution" to 1)),
      ),
    )
  }

  private fun record(user: UUID, kind: String, id: UUID, payload: Any) {
    db.update(
      "INSERT INTO records(user_id,kind,id,payload) VALUES(?,?,?,?::jsonb)",
      user,
      kind,
      id,
      json.writeValueAsString(payload),
    )
  }

  private fun workout(
    user: UUID,
    finish: Long?,
    weight: Double,
    id: UUID = UUID.randomUUID(),
    exerciseId: UUID = exercise,
  ): UUID {
    record(
      user,
      "workout",
      id,
      mapOf(
        "finishedAt" to finish,
        "exercises" to
          listOf(
            mapOf(
              "exerciseId" to exerciseId.toString(),
              "sectionId" to UUID.randomUUID().toString(),
              "sets" to
                listOf(
                  mapOf(
                    "isCompleted" to true,
                    "weightKg" to weight,
                    "reps" to 8,
                    "actualWeightKg" to null,
                    "actualReps" to null,
                  )
                ),
            )
          ),
      ),
    )
    return id
  }

  @Test
  fun `history never reads foreign or unfinished workouts and retains explicit null legacy values`() {
    val id = workout(owner, 100L, 60.0)
    workout(foreign, 300L, 999.0)
    workout(owner, null, 888.0)
    val history = context.history(owner, exercise.toString())["history"]
    assertEquals(1, history.size())
    assertEquals(60.0, history[0]["weight_kg"].asDouble())
    assertEquals(8, history[0]["reps"].asInt())
    assertEquals(id.toString(), history[0]["workout_id"].asString())
    val privateExercise = UUID.randomUUID()
    record(foreign, "exercise", privateExercise, mapOf("name" to "Private"))
    assertNull(context.exercise(owner, privateExercise.toString()))
    assertThrows(IllegalArgumentException::class.java) {
      context.history(owner, privateExercise.toString())
    }
  }

  @Test
  fun `search retains old exercise history beyond two hundred newer workouts`() {
    workout(owner, 1L, 70.0)
    val other = UUID.randomUUID()
    repeat(201) { workout(owner, it.toLong() + 2, 20.0, exerciseId = other) }
    val result =
      context.find(owner, json.readTree("{\"exercises\":[]}"), json.readTree("{}"))["exercises"]
    assertEquals(1, result.size())
    assertEquals(1, result[0]["completed_workout_count"].asInt())
    assertEquals(70.0, result[0]["last_workout_sets"][0]["weight_kg"].asDouble())
  }

  @Test
  fun `equipment filter matches requirements and excludes excluded and foreign exercises`() {
    val privateExercise = UUID.randomUUID()
    record(foreign, "exercise", privateExercise, mapOf("name" to "Private"))
    val empty =
      context
        .find(owner, json.readTree("{\"exercises\":[]}"), json.readTree("{\"equipment_ids\":[]}"))[
          "exercises"]
    assertEquals(1, empty.size())
    val matching =
      context
        .find(
          owner,
          json.readTree("{\"exercises\":[]}"),
          json.readTree("{\"equipment_ids\":[\"barbell\"]}"),
        )["exercises"]
    assertEquals(1, matching.size())
    assertEquals(exercise.toString(), matching[0]["exercise_id"].asString())
    val excluded =
      context
        .find(
          owner,
          json.readTree("{\"exercises\":[],\"excluded_exercise_ids\":[\"$exercise\"]}"),
          json.readTree("{}"),
        )["exercises"]
    assertTrue(excluded.isEmpty)
  }
}
