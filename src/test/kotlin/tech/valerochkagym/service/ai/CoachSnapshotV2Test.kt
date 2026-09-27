package tech.valerochkagym.service.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class CoachSnapshotV2Test {
  private val json = JsonMapper.builder().build()
  private val codec = CoachRunTools(json)
  private val workout = UUID.randomUUID().toString()
  private val section = UUID.randomUUID().toString()
  private val exercise = UUID.randomUUID().toString()
  private val first = UUID.randomUUID().toString()
  private val second = UUID.randomUUID().toString()
  private val next = UUID.randomUUID().toString()

  private fun snapshot() =
    json.valueToTree<tools.jackson.databind.JsonNode>(
      mapOf(
        "workout_id" to workout,
        "revision" to 3,
        "observed_at_millis" to 10_000L,
        "available_time_minutes" to 100,
        "future_rest_seconds" to 120,
        "original_plan" to
          mapOf(
            "complete" to true,
            "exercises" to
              listOf(
                mapOf(
                  "section_id" to section,
                  "exercise_id" to exercise,
                  "type" to "STRENGTH",
                  "sets" to
                    listOf(
                      mapOf("set_id" to first, "index" to 0, "completed" to false, "reps" to 10),
                      mapOf("set_id" to second, "index" to 1, "completed" to false, "reps" to 8),
                      mapOf("set_id" to next, "index" to 2, "completed" to false, "reps" to 8),
                    ),
                )
              ),
          ),
        "exercises" to
          listOf(
            mapOf(
              "section_id" to section,
              "exercise_id" to exercise,
              "name" to "Присед",
              "type" to "STRENGTH",
              "position" to 0,
              "sets" to
                listOf(
                  mapOf(
                    "set_id" to first,
                    "index" to 0,
                    "completed" to true,
                    "completed_at" to 1000L,
                    "set_type" to "WORK",
                    "weight_kg" to 50.0,
                    "reps" to 10,
                    "actual_reps" to 10,
                    "actual_rir" to 2,
                  ),
                  mapOf(
                    "set_id" to second,
                    "index" to 1,
                    "completed" to true,
                    "completed_at" to 2000L,
                    "set_type" to "WORK",
                    "weight_kg" to 50.0,
                    "reps" to 8,
                    "actual_reps" to 8,
                    "actual_rir" to 2,
                    "note" to "Техника ровная",
                  ),
                  mapOf(
                    "set_id" to next,
                    "index" to 2,
                    "completed" to false,
                    "set_type" to "WORK",
                    "weight_kg" to 50.0,
                    "reps" to 8,
                  ),
                ),
            )
          ),
      )
    )

  @Test
  fun `planned reduction keeps the remaining program`() {
    val state = snapshot()
    val decision = codec.assessment(UUID.randomUUID(), state, json.createObjectNode())
    assertEquals("planned_rep_reduction", decision["reason_code"].asString())
    assertTrue(decision["operations"].isEmpty)
  }

  @Test
  fun `time estimate cannot silently delete the end of a complete plan`() {
    val state = snapshot().deepCopy() as tools.jackson.databind.node.ObjectNode
    state.put("available_time_minutes", 0)
    val decision = codec.assessment(UUID.randomUUID(), state, json.createObjectNode())
    assertEquals("time_priorities_unknown", decision["reason_code"].asString())
    assertTrue(decision["operations"].isEmpty)
  }

  @Test
  fun `known short rest keeps plan without asking for a cause`() {
    val state = snapshot().deepCopy() as tools.jackson.databind.node.ObjectNode
    val planned =
      state["original_plan"]["exercises"][0]["sets"][1] as tools.jackson.databind.node.ObjectNode
    planned.put("reps", 10)
    val actual = state["exercises"][0]["sets"][1] as tools.jackson.databind.node.ObjectNode
    actual.put("actual_reps", 6)
    state.set("autoregulation_options", json.valueToTree(mapOf("observed_rest_seconds" to 30)))
    val decision = codec.assessment(UUID.randomUUID(), state, json.createObjectNode())
    assertEquals("short_rest", decision["reason_code"].asString())
    assertEquals("NO_CHANGE", decision["kind"].asString())
  }

  @Test
  fun `source evidence freezes result and set identity`() {
    val source = CoachSourceEvidence.capture(json, snapshot())
    assertEquals(1, source.size())
    assertEquals(workout, source[0]["workoutId"].asString())
    assertEquals(section, source[0]["sectionId"].asString())
    assertEquals(second, source[0]["sourceSetId"].asString())
    assertEquals(8, source[0]["actualReps"].asInt())
    assertEquals(8, source[0]["plannedReps"].asInt())
    assertFalse(source[0].has("note"))
  }

  @Test
  fun `package validation protects noted remaining sets`() {
    val state = snapshot().deepCopy() as tools.jackson.databind.node.ObjectNode
    val remaining = state["exercises"][0]["sets"][2] as tools.jackson.databind.node.ObjectNode
    remaining.put("note", "Сохранить для техники")
    val packet =
      json.valueToTree<tools.jackson.databind.JsonNode>(
        mapOf(
          "base_revision" to 3,
          "operations" to listOf(mapOf("action" to "delete_set", "set_id" to next)),
        )
      )
    assertThrows(IllegalArgumentException::class.java) {
      codec.operations(UUID.randomUUID(), state, packet) { true }
    }
  }
}
