package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.ApprovalDraft
import tech.valerochkagym.controller.model.CalendarDraftRequest
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class PlannerExplanation(
  val proposalId: String,
  val version: Int,
  val durationSpec: String,
  val desiredMinutes: Int,
  val estimatedSeconds: Long,
  val minimumSeconds: Long,
  val focusMuscles: List<String>,
  val repeatedExerciseIds: List<String>,
  val lastFinishedAtMillis: Long?,
  val eligibleExerciseCount: Int,
  val selectionReason: String,
  val repeatReason: String,
  val shortfallReason: String,
)

internal object PlannerExplanationFactory {
  fun create(
    draft: ApprovalDraft,
    request: CalendarDraftRequest,
    captured: CalendarCapturedContext,
    candidates: List<Map<String, Any>>,
    eligibleCount: Int,
    output: JsonNode,
  ): PlannerExplanation {
    val selected = draft.exercises.map { it.exerciseId }.toSet()
    val latest = captured.workouts.maxByOrNull { it.finishedAtMillis }
    val repeats =
      (captured.facts + captured.olderFacts)
        .filter { it.workoutId == latest?.id && it.exerciseId in selected }
        .map { it.exerciseId }
        .distinct()
        .sorted()
    val points = sortedMapOf<String, Int>()
    candidates
      .filter { it["exerciseId"] in selected }
      .forEach { candidate ->
        @Suppress("UNCHECKED_CAST")
        (candidate["muscles"] as List<Map<String, Any>>).forEach { muscle ->
          val key = muscle["muscle"] as String
          val sets =
            draft.exercises.single { it.exerciseId == candidate["exerciseId"] }.plannedSets.size
          points[key] = (points[key] ?: 0) + (muscle["contribution"] as Int) * sets
        }
      }
    val rationale = output["result"]["rationale"]
    fun reason(key: String, allowed: Set<String>): String {
      val value = rationale?.get(key)?.asString() ?: throw aiError("ai_invalid_response")
      if (value !in allowed) throw aiError("ai_invalid_response")
      return value
    }
    val selection =
      reason("selection", setOf("CONTINUITY", "PRIORITY", "GOAL_BALANCE", "CONSTRAINTS"))
    val repeat = reason("repeat", setOf("CONTINUITY", "PRIORITY", "LIMITED_OPTIONS", "NONE"))
    val shortfall = reason("shortfall", setOf("VOLUME_LIMIT", "CONSTRAINTS", "NONE"))
    if (
      rationale != null &&
        ((repeats.isEmpty() != (repeat == "NONE")) ||
          (selection == "PRIORITY" && request.priorityMuscles.isEmpty()) ||
          (repeat == "LIMITED_OPTIONS" && eligibleCount > selected.size))
    )
      throw aiError("ai_invalid_response")
    val selectedMuscles = points.filterValues { it > 0 }.keys
    if (
      (selection == "PRIORITY" || repeat == "PRIORITY") &&
        request.priorityMuscles.none { it in selectedMuscles }
    )
      throw aiError("ai_invalid_response")
    val hasConditions =
      request.gymIds.isNotEmpty() ||
        request.excludedExerciseIds.isNotEmpty() ||
        request.excludedEquipmentIds.isNotEmpty() ||
        request.currentState != null ||
        request.preferences != null ||
        !captured.profile?.manualConstraints.isNullOrBlank()
    if ((selection == "CONSTRAINTS" || shortfall == "CONSTRAINTS") && !hasConditions)
      throw aiError("ai_invalid_response")
    val duration = PlannerDuration.seconds(draft.exercises)
    val minimum = PlannerDuration.minimumSeconds(request.availableDurationMinutes)
    if (rationale != null && ((duration < minimum) == (shortfall == "NONE")))
      throw aiError("ai_invalid_response")
    return PlannerExplanation(
      "",
      1,
      "planner-duration-v1",
      request.availableDurationMinutes,
      duration,
      minimum,
      points.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3).map { it.key },
      repeats,
      latest?.finishedAtMillis,
      eligibleCount,
      selection,
      if (repeats.isEmpty()) "NONE" else repeat,
      if (duration >= minimum) "NONE" else shortfall,
    )
  }
}

/** Separate optional resource: legacy strict DTOs and immutable approval bytes stay unchanged. */
@Service
class PlannerExplanationStore(
  private val db: JdbcTemplate,
  private val json: ObjectMapper,
  private val proposals: TrainingProposalService,
  private val entities: jakarta.persistence.EntityManager,
) {
  fun save(proposalId: UUID, version: Int, explanation: PlannerExplanation) {
    entities.flush()
    db.update(
      "INSERT INTO planner_explanations(proposal_id,version,payload) VALUES (?,?,?::jsonb)",
      proposalId,
      version,
      json.writeValueAsString(
        explanation.copy(proposalId = proposalId.toString(), version = version)
      ),
    )
  }

  fun read(identity: Identity, proposalId: UUID): PlannerExplanation {
    val proposal = proposals.detail(identity, proposalId)
    return db
      .query(
        "SELECT payload::text FROM planner_explanations WHERE proposal_id=? AND version=?",
        { rs, _ -> json.readValue(rs.getString(1), PlannerExplanation::class.java) },
        proposalId,
        proposal.currentVersion,
      )
      .singleOrNull() ?: throw ApiException(404, "explanation_unavailable", "Пояснение недоступно")
  }
}
