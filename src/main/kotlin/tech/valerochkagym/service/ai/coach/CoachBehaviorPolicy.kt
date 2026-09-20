package tech.valerochkagym.service.ai.coach

import tools.jackson.databind.JsonNode

enum class WorkoutPhase {
  UNKNOWN,
  READY,
  IN_SET,
  RESTING,
  BETWEEN_EXERCISES,
}

enum class WorkoutLifecycle {
  ACTIVE,
  PAUSED,
  FINISHED,
}

enum class BehaviorAction {
  CONCERN,
  EVALUATE,
  DEFER,
  SILENT,
}

data class BehaviorState(
  val lifecycle: WorkoutLifecycle = WorkoutLifecycle.ACTIVE,
  val phase: WorkoutPhase = WorkoutPhase.UNKNOWN,
  val openConcerns: Set<String> = emptySet(),
  val seenConcerns: Set<String> = emptySet(),
  val online: Boolean = true,
  val policyVersion: String = CoachBehaviorPolicy.VERSION,
)

data class BehaviorEvent(
  val lifecycle: WorkoutLifecycle,
  val phase: WorkoutPhase,
  val concerns: Set<String>,
  val initiativeEnabled: Boolean,
  val pendingInteraction: Boolean,
  val online: Boolean,
  val resolvedConcerns: Set<String> = emptySet(),
)

data class PolicyDecision(
  val state: BehaviorState,
  val action: BehaviorAction,
  val reasonCode: String,
)

/** Pure transition: missing phase never implies IN_SET; silence never resolves a concern. */
object CoachBehaviorPolicy {
  const val VERSION = "behavior-1"
  const val CONCERN_TEXT =
    "Вы сообщили о боли или нарушении техники. Уточните, что произошло, перед планированием продолжения."

  fun reduce(previous: BehaviorState, event: BehaviorEvent): PolicyDecision {
    val fresh = event.concerns - previous.seenConcerns
    val state =
      previous.copy(
        lifecycle = event.lifecycle,
        online = event.online,
        phase = event.phase,
        openConcerns = (previous.openConcerns - event.resolvedConcerns) + fresh,
        seenConcerns = previous.seenConcerns + fresh,
      )
    val outcome =
      when {
        fresh.isNotEmpty() -> BehaviorAction.CONCERN to "reported_safety_issue"
        state.openConcerns.isNotEmpty() -> BehaviorAction.SILENT to "open_concern"
        !event.initiativeEnabled -> BehaviorAction.SILENT to "initiative_disabled"
        event.lifecycle != WorkoutLifecycle.ACTIVE -> BehaviorAction.SILENT to "workout_inactive"
        !event.online -> BehaviorAction.DEFER to "offline"
        event.phase == WorkoutPhase.UNKNOWN -> BehaviorAction.DEFER to "unknown_phase"
        event.phase == WorkoutPhase.IN_SET -> BehaviorAction.DEFER to "in_set"
        event.pendingInteraction -> BehaviorAction.DEFER to "pending_interaction"
        else -> BehaviorAction.EVALUATE to "eligible_window"
      }
    return PolicyDecision(state, outcome.first, outcome.second)
  }

  /** Keys depend on explicit reports, never clocks, revision or remaining sets. */
  fun concerns(snapshot: JsonNode?): Set<String> {
    if (snapshot == null) return emptySet()
    fun reports(node: JsonNode, scope: String) =
      node["reported_feelings"]
        ?.toList()
        .orEmpty()
        .map { it.asString() }
        .filter { it in setOf("PAIN", "TECHNIQUE_BREAKDOWN") }
        .map { "$scope:$it" }
    return (reports(snapshot, "workout") +
        snapshot["exercises"]?.toList().orEmpty().flatMap { e ->
          e["sets"]?.toList().orEmpty().flatMap { reports(it, it["set_id"]?.asString().orEmpty()) }
        })
      .toSet()
  }

  fun event(snapshot: JsonNode, active: Boolean, initiative: Boolean): BehaviorEvent =
    BehaviorEvent(
      lifecycle =
        when {
          snapshot["finished"]?.asBoolean() == true || !active -> WorkoutLifecycle.FINISHED
          snapshot["paused"]?.asBoolean() == true -> WorkoutLifecycle.PAUSED
          else -> WorkoutLifecycle.ACTIVE
        },
      phase =
        WorkoutPhase.entries.firstOrNull { it.name == snapshot["phase"]?.asString() }
          ?: WorkoutPhase.UNKNOWN,
      concerns = concerns(snapshot),
      initiativeEnabled = initiative,
      pendingInteraction = snapshot["pending_interaction"]?.asBoolean() == true,
      online = snapshot["online"]?.asBoolean() != false,
    )
}
