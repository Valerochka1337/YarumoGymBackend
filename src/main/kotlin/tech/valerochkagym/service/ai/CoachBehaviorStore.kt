package tech.valerochkagym.service.ai

import java.time.Clock
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tech.valerochkagym.service.ai.coach.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** All mutations run inside the caller's transaction and account lock. */
@Service
class CoachBehaviorStore(
  private val jdbc: JdbcTemplate,
  private val json: ObjectMapper,
  private val dialogue: CoachDialogueService,
  private val clock: Clock,
  @Value("\${gym.coach-behavior.mode:ENFORCE}") private val mode: String,
) {
  init {
    require(mode in setOf("SHADOW", "ENFORCE"))
  }

  fun record(
    owner: UUID,
    workout: UUID,
    eventId: UUID,
    snapshot: JsonNode,
    active: Boolean,
    initiative: Boolean,
    resolved: Set<String> = emptySet(),
  ): PolicyDecision {
    val row =
      jdbc.queryForMap(
        "SELECT behavior_state::text, state_version, behavior_mode FROM coach_sessions WHERE owner_id=? AND workout_id=? FOR UPDATE",
        owner,
        workout,
      )
    val stored = json.readTree(row["behavior_state"] as String)
    val previous =
      if (stored.isEmpty) BehaviorState() else json.treeToValue(stored, BehaviorState::class.java)
    if (!previous.seenConcerns.containsAll(resolved))
      tech.valerochkagym.controller.advice.bad("Неизвестная жалоба")
    val input =
      CoachBehaviorPolicy.event(snapshot, active, initiative).copy(resolvedConcerns = resolved)
    val decision = CoachBehaviorPolicy.reduce(previous, input)
    val version = (row["state_version"] as Number).toLong() + 1
    val pinnedMode = if (version == 1L) mode else row["behavior_mode"] as String
    jdbc.update(
      "UPDATE coach_sessions SET behavior_state=?::jsonb,state_version=?,behavior_mode=? WHERE owner_id=? AND workout_id=?",
      json.writeValueAsString(decision.state),
      version,
      pinnedMode,
      owner,
      workout,
    )
    jdbc.update(
      "INSERT INTO coach_behavior_events(owner_id,workout_id,event_id,state_version,input,decision) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
      owner,
      workout,
      eventId,
      version,
      json.writeValueAsString(input),
      json.writeValueAsString(decision),
    )
    if (decision.action == BehaviorAction.CONCERN) {
      val payload =
        mapOf(
          "type" to "concern",
          "source" to "BEHAVIOR",
          "schemaVersion" to 1,
          "eventId" to eventId,
          "stateVersion" to version,
          "text" to CoachBehaviorPolicy.CONCERN_TEXT,
          "decision" to decision,
        )
      dialogue.enqueue(owner, workout, "concern", json.valueToTree(payload))
    }
    return decision
  }

  fun resolve(owner: UUID, workout: UUID, eventId: UUID, key: String) {
    val view = state(owner, workout) ?: throw IllegalArgumentException("Неизвестная тренировка")
    val previous = json.treeToValue(view["state"], BehaviorState::class.java)
    require(key in previous.openConcerns) { "Уточни, о какой текущей жалобе говорит пользователь" }
    val next = previous.copy(openConcerns = previous.openConcerns - key)
    jdbc.update(
      "UPDATE coach_sessions SET behavior_state=?::jsonb,state_version=state_version+1 WHERE owner_id=? AND workout_id=?",
      json.writeValueAsString(next),
      owner,
      workout,
    )
    jdbc.update(
      "INSERT INTO coach_behavior_events(owner_id,workout_id,event_id,state_version,input,decision) SELECT owner_id,workout_id,?,state_version,?::jsonb,?::jsonb FROM coach_sessions WHERE owner_id=? AND workout_id=?",
      eventId,
      json.writeValueAsString(mapOf("resolvedConcernKeys" to listOf(key))),
      json.writeValueAsString(mapOf("state" to next, "reasonCode" to "user_resolved_concern")),
      owner,
      workout,
    )
    dialogue.emit(
      owner,
      workout,
      "concern_resolved",
      json.valueToTree(mapOf("resolvedConcernKeys" to listOf(key))),
    )
  }

  fun state(owner: UUID, workout: UUID): JsonNode? =
    jdbc
      .query(
        "SELECT behavior_state::text,state_version,behavior_mode FROM coach_sessions WHERE owner_id=? AND workout_id=?",
        { r, _ ->
          json.valueToTree<JsonNode>(
            mapOf(
              "state" to json.readTree(r.getString(1)),
              "stateVersion" to r.getLong(2),
              "mode" to r.getString(3),
            )
          )
        },
        owner,
        workout,
      )
      .firstOrNull()

  fun timeline(owner: UUID, workout: UUID): List<JsonNode> =
    jdbc
      .query(
        "SELECT event_id,state_version,input::text,decision::text FROM coach_behavior_events WHERE owner_id=? AND workout_id=? ORDER BY state_version DESC LIMIT 50",
        { r, _ ->
          json.valueToTree<JsonNode>(
            mapOf(
              "eventId" to r.getObject(1).toString(),
              "stateVersion" to r.getLong(2),
              "input" to json.readTree(r.getString(3)),
              "decision" to json.readTree(r.getString(4)),
            )
          )
        },
        owner,
        workout,
      )
      .reversed()

  fun allowsAutomatic(owner: UUID, workout: UUID): Boolean {
    val view = state(owner, workout) ?: return true
    val state = json.treeToValue(view["state"], BehaviorState::class.java)
    if (state.openConcerns.isNotEmpty()) return false
    if (state.phase == WorkoutPhase.RESTING) {
      val snapshot =
        jdbc
          .queryForObject(
            "SELECT snapshot::text FROM coach_sessions WHERE owner_id=? AND workout_id=?",
            String::class.java,
            owner,
            workout,
          )
          ?.let(json::readTree)
      val rest = snapshot?.get("rest")
      val ends = rest?.get("ends_at_millis")?.takeUnless { it.isNull }?.asLong()
      if (ends != null && ends <= clock.millis()) return false
    }
    return state.online &&
      state.lifecycle == WorkoutLifecycle.ACTIVE &&
      state.phase != WorkoutPhase.IN_SET &&
      (state.phase != WorkoutPhase.UNKNOWN || view["mode"].asString() != "ENFORCE")
  }

  /** Derived planning view; original reports remain in the stored workout snapshot. */
  fun planningSnapshot(owner: UUID, workout: UUID, snapshot: JsonNode): JsonNode {
    val view = state(owner, workout) ?: return snapshot
    val state = json.treeToValue(view["state"], BehaviorState::class.java)
    val resolved = state.seenConcerns - state.openConcerns
    if (resolved.isEmpty()) return snapshot
    val copy = snapshot.deepCopy() as tools.jackson.databind.node.ObjectNode
    fun filter(node: JsonNode, scope: String) {
      val feelings = node["reported_feelings"] ?: return
      (node as? tools.jackson.databind.node.ObjectNode)?.set(
        "reported_feelings",
        json.valueToTree<JsonNode>(feelings.filter { "$scope:${it.asString()}" !in resolved }),
      )
    }
    filter(copy, "workout")
    copy["exercises"]?.forEach { e ->
      e["sets"]?.forEach { filter(it, it["set_id"]?.asString().orEmpty()) }
    }
    return copy
  }

  fun enforce(owner: UUID, workout: UUID): Boolean =
    state(owner, workout)?.get("mode")?.asString() == "ENFORCE"
}
