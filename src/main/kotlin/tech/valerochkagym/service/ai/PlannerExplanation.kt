package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.ApprovalDraft
import tech.valerochkagym.controller.model.CalendarDraftRequest
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService
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
    val duration = PlannerDuration.seconds(draft.exercises)
    val minimum = PlannerDuration.minimumSeconds(request.availableDurationMinutes)
    // Android 1.3.62 accepts these legacy fields. Intent cannot be inferred from a plan:
    // NONE denotes the absence of a repeat/shortfall; UNSPECIFIED makes no causal claim.
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
      "UNSPECIFIED",
      if (repeats.isEmpty()) "NONE" else "UNSPECIFIED",
      if (duration >= minimum) "NONE" else "UNSPECIFIED",
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
