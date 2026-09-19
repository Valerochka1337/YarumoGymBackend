package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Every personal query carries its authenticated owner; standard exercises are read-only. */
@Service
class CoachRunContext(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
  fun exercise(owner: UUID, id: String): JsonNode? =
    jdbc
      .query(
        "SELECT payload::text FROM records WHERE user_id=? AND kind='exercise' AND id=? AND NOT deleted UNION ALL SELECT payload::text FROM standard_records WHERE kind='exercise' AND id=? AND NOT archived AND EXISTS (SELECT 1 FROM catalog_state WHERE id=1 AND active) LIMIT 1",
        { rs, _ -> json.readTree(rs.getString(1)) },
        owner,
        UUID.fromString(id),
        UUID.fromString(id),
      )
      .firstOrNull()

  fun find(owner: UUID, snapshot: JsonNode, args: JsonNode): JsonNode {
    val query = args["query"]?.asString()?.trim()?.lowercase().orEmpty()
    val rows =
      jdbc.query(
        "SELECT id,payload::text FROM records WHERE user_id=? AND kind='exercise' AND NOT deleted UNION ALL SELECT id,payload::text FROM standard_records WHERE kind='exercise' AND NOT archived AND EXISTS (SELECT 1 FROM catalog_state WHERE id=1 AND active) LIMIT 2001",
        { rs, _ -> rs.getObject(1, UUID::class.java).toString() to json.readTree(rs.getString(2)) },
        owner,
      )
    if (rows.size > 2000) throw aiError("ai_context_too_large")
    fun strings(value: JsonNode?) = value?.toList().orEmpty().map { it.asString() }.toSet()
    val candidates =
      rows
        .distinctBy { it.first }
        .filter { (id, row) ->
          val muscleIds =
            row["muscles"]?.toList().orEmpty().mapNotNull { it["muscle"]?.asString() }.toSet()
          id !in strings(snapshot["excluded_exercise_ids"]) &&
            row["archived"]?.asBoolean() != true &&
            query in row["name"].asString().lowercase() &&
            strings(row["equipmentIds"]).containsAll(strings(args["equipment_ids"])) &&
            muscleIds.containsAll(strings(args["muscle_ids"])) &&
            (strings(args["muscle_groups"]).isEmpty() ||
              row["muscleGroup"]?.asString() in strings(args["muscle_groups"]))
        }
    data class Usage(val at: Long, val count: Long)
    val usage =
      jdbc
        .query(
          "SELECT e->>'exerciseId' AS exercise_id,MAX((w.payload->>'finishedAt')::bigint) AS last_used,COUNT(DISTINCT w.id) AS uses FROM records w CROSS JOIN LATERAL jsonb_array_elements(w.payload->'exercises') e WHERE w.user_id=? AND w.kind='workout' AND NOT w.deleted AND jsonb_typeof(w.payload->'finishedAt')='number' AND EXISTS (SELECT 1 FROM jsonb_array_elements(e->'sets') s WHERE s->>'isCompleted'='true') GROUP BY e->>'exerciseId' LIMIT 2001",
          { rs, _ ->
            rs.getString("exercise_id") to Usage(rs.getLong("last_used"), rs.getLong("uses"))
          },
          owner,
        )
        .toMap()
    if (usage.size > 2000) throw aiError("ai_context_too_large")
    val ranked =
      candidates
        .sortedWith(
          compareByDescending<Pair<String, JsonNode>> { usage[it.first]?.at ?: 0 }
            .thenByDescending { usage[it.first]?.count ?: 0 }
            .thenBy { it.second["name"].asString() }
        )
        .take(args["limit"]?.asInt() ?: 10)
    return json.valueToTree<JsonNode>(
      mapOf(
        "exercises" to
          ranked.map { (id, row) ->
            mapOf(
              "exercise_id" to id,
              "name" to row["name"].asString(),
              "muscles" to
                row["muscles"]?.toList().orEmpty().mapNotNull { it["muscle"]?.asString() },
              "equipment" to strings(row["equipmentIds"]),
              "muscle_group" to row["muscleGroup"]?.asString(),
              "type" to row["type"]?.asString(),
              "last_used_at" to usage[id]?.at,
              "completed_workout_count" to (usage[id]?.count ?: 0),
              "current_section_ids" to
                snapshot["exercises"]
                  ?.toList()
                  .orEmpty()
                  .filter { it["exercise_id"]?.asString() == id }
                  .map { it["section_id"].asString() },
              "last_workout_sets" to parents(owner, id, 1).flatMap { sets(it, id) },
            )
          }
      )
    )
  }

  fun history(owner: UUID, exerciseId: String): JsonNode {
    require(exercise(owner, exerciseId) != null) { "Упражнение недоступно" }
    val parents = parents(owner, exerciseId, 3)
    return json.valueToTree<JsonNode>(mapOf("history" to parents.flatMap { sets(it, exerciseId) }))
  }

  private fun parents(owner: UUID, exerciseId: String, limit: Int): List<JsonNode> =
    jdbc.query(
      "SELECT (payload || jsonb_build_object('id',id::text))::text FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number' AND EXISTS (SELECT 1 FROM jsonb_array_elements(payload->'exercises') e WHERE e->>'exerciseId'=? AND EXISTS (SELECT 1 FROM jsonb_array_elements(e->'sets') s WHERE s->>'isCompleted'='true')) ORDER BY (payload->>'finishedAt')::numeric DESC,id LIMIT ?",
      { rs, _ -> json.readTree(rs.getString(1)) },
      owner,
      exerciseId,
      limit,
    )

  private fun sets(parent: JsonNode, id: String): List<Map<String, Any?>> =
    parent["exercises"]
      ?.toList()
      .orEmpty()
      .filter { it["exerciseId"]?.asString() == id }
      .flatMap { section ->
        section["sets"]?.toList().orEmpty().mapIndexedNotNull { index, set ->
          if (set["isCompleted"]?.asBoolean() != true) null
          else {
            val out =
              mutableMapOf<String, Any?>(
                "workout_id" to parent["id"]?.asString(),
                "workout_finished_at" to parent["finishedAt"]?.asLong(),
                "section_history_id" to section["sectionId"]?.asString(),
                "set_index" to index,
                "completed_at" to set["completedAt"]?.asLong(),
                "set_type" to (set["setType"]?.asString() ?: "UNKNOWN"),
                "actual_rir" to set["actualRir"]?.takeUnless { it.isNull }?.asInt(),
                "actual_rir_at_least_four" to (set["actualRirAtLeastFour"]?.asBoolean() ?: false),
              )
            listOf(
                "weightKg" to "weight_kg",
                "reps" to "reps",
                "durationSec" to "duration_sec",
                "speedKmh" to "speed_kmh",
                "inclinePct" to "incline_pct",
              )
              .forEach { (key, wire) ->
                val actual = "actual" + key.replaceFirstChar { it.uppercase() }
                out[wire] =
                  (set[actual]?.takeUnless { it.isNull } ?: set[key])
                    ?.takeIf { it.isNumber }
                    ?.asDouble()
              }
            out
          }
        }
      }
}
