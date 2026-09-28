package tech.valerochkagym.service.ai

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.model.TrainingProposalSource
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService
import tech.valerochkagym.service.trainingproposal.TrainingProposalValidator
import tools.jackson.databind.ObjectMapper

/** Everything used by execution is durable, including facts, classification and configuration. */
data class PlannerExecutionSnapshot(
  val request: PlannerV2CreateRequest,
  val runtime: PlannerConfiguration,
  val capturedAtMillis: Long,
  val effectiveGoal: String,
  val goalDefaulted: Boolean,
  val structures: List<DeterministicStructure>,
  val candidates: List<DeterministicCandidate>,
  val mappings: List<PlannerExerciseMappingDto>,
  val facts: List<CalendarFact>,
  val lastFinishedAtMillis: Long?,
  val mappingNeeded: List<String>,
  val manualDraft: ApprovalDraft? = null,
  val changedSelections: Map<String, String> = emptyMap(),
  val baseProposalId: UUID? = null,
  val baseProposalVersion: Int? = null,
  val inheritedSelections: List<PlannerSlotSelection> = emptyList(),
  val inheritedSlots: List<DeterministicSlot> = emptyList(),
)

data class PlannerSlotSelection(val slotId: String, val selectionId: String, val exerciseId: String)

@Service
class DeterministicPlannerRuntime(
  private val contexts: AiContextReader,
  private val configuration: PlannerConfigurationService,
  private val mappings: PlannerMovementMappingService,
  private val proposals: TrainingProposalService,
  private val validator: TrainingProposalValidator,
  private val jdbc: JdbcTemplate,
  private val json: ObjectMapper,
  private val clock: Clock,
  private val entities: jakarta.persistence.EntityManager,
) {
  fun legacy(request: PlannerV2CreateRequest) =
    CalendarDraftRequest(
      request.requestId.toString(),
      request.expectedRevision,
      request.expectedCatalogRevision,
      request.startsAtMillis,
      request.timeZoneId,
      request.gymIds.map(UUID::toString),
      request.excludedExerciseIds.map(UUID::toString),
      request.excludedEquipmentIds,
      request.priorityMuscles,
      false,
      request.availableDurationMinutes,
      null,
      null,
    )

  fun capture(identity: Identity, request: PlannerV2CreateRequest): PlannerExecutionSnapshot {
    val runtime = configuration.snapshot()
    val captured =
      contexts.captureCalendar(
        identity,
        request.expectedRevision,
        request.expectedCatalogRevision,
        request.timeZoneId,
        false,
        request.gymIds.map(UUID::toString),
        runtime.historyDays,
        runtime.detailDays,
      )
    val goal = captured.profile?.trainingGoal?.takeIf { it in goals } ?: "GENERAL_FITNESS"
    val eligible =
      CalendarCandidateSelector.eligible(
        captured.candidates,
        captured.gyms,
        legacy(request),
        captured.facts,
        goal,
      )
    val sources = captured.candidates.associateBy { it.id }
    val accents =
      AgenticPlannerPolicy.resolvePreferences(
        captured.strengthPriorities.keys.associateWith { "MORE" } + captured.plannerPreferences,
        captured.plannerAccents,
        runtime.defaultExerciseAccents.associate { it.exerciseId to it.accent },
        sources,
      )
    val history =
      contexts
        .captureStrengthPlannerFacts(
          identity,
          request.expectedRevision,
          request.expectedCatalogRevision,
          eligible.mapTo(mutableSetOf()) { it.getValue("exerciseId") as String },
          captured.capturedAtMillis,
          emptySet(),
        )
        .latestFacts
    val facts =
      (captured.facts + captured.olderFacts + history).distinctBy {
        listOf(it.workoutId, it.sectionId, it.setIndex)
      }
    val latest = captured.workouts.maxByOrNull { it.finishedAtMillis }
    val classified =
      eligible.mapNotNull { row ->
        mappings.effective(
          identity.userId,
          UUID.fromString(row.getValue("exerciseId") as String),
          sources[row["exerciseId"]]?.curatedCanonical == true,
        )
      }
    val byId = classified.associateBy { it.exerciseId.toString() }
    val candidates =
      eligible
        .mapNotNull { row ->
          val id = row.getValue("exerciseId") as String
          val m = byId[id] ?: return@mapNotNull null
          if (goal !in m.supportedGoals || row["type"] != m.exerciseType || accents[id] == "NEVER")
            return@mapNotNull null
          @Suppress("UNCHECKED_CAST") val equipment = (row["equipmentIds"] as List<String>).toSet()
          // Classification cannot claim different equipment/type than the authoritative exercise.
          if (equipment != m.equipmentIds.toSet()) return@mapNotNull null
          DeterministicCandidate(
            id,
            PlannerExerciseType.valueOf(m.exerciseType),
            m.movementClass,
            equipment,
            accents[id] ?: "NORMAL",
            facts.count { it.exerciseId == id },
            captured.strengthPriorities[id] == "HIGH",
            facts.any { it.exerciseId == id && it.workoutId == latest?.id },
            m.roles.toSet(),
          )
        }
        .sortedBy { it.exerciseId }
    val collection = runtime.collections.singleOrNull { it.goal == goal }
    val structures =
      collection?.patterns.orEmpty().map { pattern ->
        DeterministicStructure(
          pattern.id,
          pattern.slots.flatMapIndexed { index, slot ->
            (0 until slot.exerciseCount).map { occurrence ->
              val slotId = slot.slotId.ifBlank { "${pattern.id}-slot-${index + 1}" }
              DeterministicSlot(
                slotId,
                "$slotId-${occurrence + 1}",
                slot.movementClass,
                setOf(PlannerExerciseType.valueOf(slot.exerciseType)),
                slot.allowedEquipmentIds.toSet(),
                slot.sets,
                slot.sets,
                slot.repsMin,
                slot.repsMax,
                slot.allowedActiveSeconds,
                slot.allowedRestSeconds,
                slot.preferredSetCount,
                slot.preferredRestSeconds,
                slot.targetTotalReps,
                slot.targetIntensityBasisPoints,
                slot.targetActiveSeconds,
                90,
                if (slot.role in setOf("PRIMARY", "ACCESSORY")) slot.role else "CONDITIONING",
              )
            }
          },
        )
      }
    return PlannerExecutionSnapshot(
      request,
      runtime,
      captured.capturedAtMillis,
      goal,
      captured.profile?.trainingGoal !in goals,
      structures,
      candidates,
      classified.sortedBy { it.exerciseId },
      facts,
      latest?.finishedAtMillis,
      eligible.map { it.getValue("exerciseId") as String }.filter { it !in byId }.sorted(),
    )
  }

  fun captureRefinement(
    identity: Identity,
    proposalId: UUID,
    request: PlannerV2RefinementRequest,
  ): PlannerExecutionSnapshot {
    val proposal = proposals.detail(identity, proposalId)
    if (proposal.source != "RULE_BASED")
      throw ApiException(404, "proposal_not_found", "Предложение не найдено")
    if (proposal.currentVersion != request.baseProposalVersion || proposal.status != "PENDING")
      throw aiError("ai_context_stale")
    validator.validateDraft(request.approvalDraft, clock.instant())
    val old =
      jdbc
        .query(
          "SELECT execution_snapshot::text FROM calendar_draft_jobs WHERE owner_id=? AND proposal_id=? AND protocol=2",
          { rs, _ -> json.readValue(rs.getString(1), PlannerExecutionSnapshot::class.java) },
          identity.userId,
          proposalId,
        )
        .singleOrNull() ?: throw aiError("ai_context_stale")
    val explanation =
      jdbc.queryForObject(
        "SELECT payload::text FROM planner_explanations WHERE proposal_id=? AND version=?",
        String::class.java,
        proposalId,
        proposal.currentVersion,
      )!!
    val selections =
      json.readTree(explanation)["slotSelections"].toList().map {
        json.treeToValue(it, PlannerSlotSelection::class.java)
      }
    val excluded = request.changes.filter { it.kind == "EXCLUDE" }.map { it.exerciseId }
    val create =
      old.request.copy(
        requestId = request.requestId,
        variant = request.variant,
        expectedRevision = request.expectedRevision,
        expectedCatalogRevision = request.expectedCatalogRevision,
        startsAtMillis = request.approvalDraft.startsAtMillis,
        timeZoneId = request.approvalDraft.timeZoneId,
        gymIds = request.approvalDraft.gymIds.map(UUID::fromString),
        excludedExerciseIds = (old.request.excludedExerciseIds + excluded).distinct(),
      )
    val fresh = capture(identity, create)
    val changed = linkedMapOf<String, String>()
    val slots = mutableListOf<DeterministicSlot>()
    request.changes.forEach { change ->
      val targets =
        if (change.kind == "REPLACE")
          selections.filter { it.slotId == change.slotId && it.selectionId == change.selectionId }
        else selections.filter { it.exerciseId == change.exerciseId.toString() }
      if (targets.isEmpty()) bad("Слот отсутствует в исходном предложении")
      targets.forEach { target ->
        if (changed.put(target.selectionId, target.exerciseId) != null) bad("Слот изменён повторно")
        if (request.approvalDraft.exercises.none { it.exerciseId == target.exerciseId })
          bad("Слот удалён из редактируемого черновика")
        val slot =
          (old.inheritedSlots + old.structures.flatMap { it.slots }).firstOrNull {
            it.slotId == target.slotId && it.selectionId == target.selectionId
          } ?: bad("Слот отсутствует")
        slots +=
          slot.copy(
            requiredExerciseId =
              if (change.kind == "REPLACE") change.exerciseId.toString() else null
          )
      }
    }
    val retained =
      request.approvalDraft.exercises
        .filter { it.exerciseId !in changed.values }
        .map { it.exerciseId }
        .toSet()
    return fresh.copy(
      structures = listOf(DeterministicStructure("refinement", slots.sortedBy { it.selectionId })),
      candidates = fresh.candidates.filter { it.exerciseId !in retained },
      manualDraft = request.approvalDraft,
      changedSelections = changed,
      baseProposalId = proposalId,
      baseProposalVersion = proposal.currentVersion,
      inheritedSelections = selections,
      inheritedSlots =
        (old.inheritedSlots + old.structures.flatMap { it.slots }).distinctBy { it.selectionId },
    )
  }

  fun compute(snapshot: PlannerExecutionSnapshot): DeterministicPlannerResult {
    val retained =
      snapshot.manualDraft?.exercises.orEmpty().filter {
        it.exerciseId !in snapshot.changedSelections.values
      }
    val remaining =
      snapshot.request.availableDurationMinutes * 60L -
        if (retained.isEmpty()) 0 else PlannerDuration.seconds(retained) + 90L
    return DeterministicPlannerEngine()
      .plan(
        snapshot.structures,
        snapshot.candidates,
        remaining,
        snapshot.runtime.deterministicLimits,
        PlannerDuration.minimumSeconds(snapshot.request.availableDurationMinutes),
        snapshot.request.variant,
      )
  }

  fun draft(snapshot: PlannerExecutionSnapshot, plan: DeterministicPlan): ApprovalDraft {
    val rows =
      plan.selections.map { selection ->
        val scheme = selection.scheme
        PlannedExercise(
          selection.exercise.exerciseId,
          scheme.restSeconds,
          if (selection.exercise.type == PlannerExerciseType.STRENGTH)
            scheme.repetitions.map { reps ->
              PlannedSet(
                PlannerWeightEngine.calculate(
                  snapshot.facts,
                  selection.exercise.exerciseId,
                  reps,
                  snapshot.capturedAtMillis,
                  snapshot.runtime.weightStepKg,
                ),
                reps,
                null,
                null,
                null,
              )
            }
          else scheme.activeSeconds.map { PlannedSet(null, null, it, null, null) },
        )
      }
    val manual = snapshot.manualDraft
    val draft =
      if (manual == null)
        ApprovalDraft(
          "Тренировка",
          snapshot.request.gymIds.map(UUID::toString),
          rows,
          snapshot.request.startsAtMillis,
          snapshot.request.timeZoneId,
        )
      else
        manual.copy(
          exercises =
            manual.exercises.map { existing ->
              val replacement =
                plan.selections.indexOfFirst {
                  snapshot.changedSelections[it.slot.selectionId] == existing.exerciseId
                }
              if (replacement < 0) existing else rows[replacement]
            }
        )
    if (PlannerDuration.seconds(draft.exercises) > snapshot.request.availableDurationMinutes * 60L)
      throw ApiException(409, "NO_FEASIBLE_PLAN", "Не удалось уложиться в выбранное время")
    validator.validateDraft(draft, clock.instant())
    return draft
  }

  /** Called under catalog/owner/session/job locks; config is deliberately NOT read again. */
  fun publish(
    identity: Identity,
    snapshot: PlannerExecutionSnapshot,
    plan: DeterministicPlan,
  ): CalendarDraftResponse {
    if (snapshot.mappings.any { mappings.effective(identity.userId, it.exerciseId) != it })
      throw aiError("ai_context_stale")
    snapshot.baseProposalId?.let {
      val current = proposals.detail(identity, it)
      if (current.currentVersion != snapshot.baseProposalVersion || current.status != "PENDING")
        throw aiError("ai_context_stale")
    }
    val draft = draft(snapshot, plan)
    val p =
      proposals.createCalendarInternalAi(
        identity,
        snapshot.request.expectedRevision,
        snapshot.request.expectedCatalogRevision,
        draft,
        TrainingProposalSource.RULE_BASED,
      )
    val estimate = PlannerDuration.seconds(draft.exercises)
    val minimum = PlannerDuration.minimumSeconds(snapshot.request.availableDurationMinutes)
    val explanation =
      linkedMapOf<String, Any?>(
        "proposalId" to p.proposalId.toString(),
        "version" to p.currentVersion,
        "durationSpec" to "planner-duration-v2",
        "desiredMinutes" to snapshot.request.availableDurationMinutes,
        "estimatedSeconds" to estimate,
        "minimumSeconds" to minimum,
        "focusMuscles" to snapshot.request.priorityMuscles,
        "repeatedExerciseIds" to
          plan.selections.filter { it.exercise.repeated }.map { it.exercise.exerciseId },
        "lastFinishedAtMillis" to snapshot.lastFinishedAtMillis,
        "eligibleExerciseCount" to
          (snapshot.candidates.map { it.exerciseId } + draft.exercises.map { it.exerciseId })
            .distinct()
            .size,
        "selectionReason" to "RULE_BASED",
        "repeatReason" to
          if (plan.selections.any { it.exercise.repeated }) "CONTINUITY" else "NONE",
        "shortfallReason" to if (estimate < minimum) "CONSTRAINTS" else "NONE",
        "algorithm" to "deterministic-planner-v2",
        "algorithmVersion" to 2,
        "inputFingerprint" to fingerprint(json.writeValueAsBytes(snapshot)),
        "effectiveGoal" to snapshot.effectiveGoal,
        "terminalCode" to null,
        "optimalityNotGuaranteed" to plan.optimalityNotGuaranteed,
        "nodeCount" to plan.nodes,
        "evaluationCount" to plan.evaluations,
        "ruleIds" to
          listOf(
            "goal-${snapshot.effectiveGoal.lowercase().replace('_', '-')}",
            "equipment-gym",
            "accent-more",
          ),
        "reasons" to
          buildList {
            if (snapshot.goalDefaulted) add("GOAL_DEFAULTED_TO_GENERAL_FITNESS")
            if (snapshot.mappingNeeded.isNotEmpty()) add("EXERCISE_MAPPING_REQUIRED")
            if (estimate < minimum) add("SOFT_MINIMUM_SHORTFALL")
          },
        "slotSelections" to
          (snapshot.inheritedSelections.filter {
            it.selectionId !in snapshot.changedSelections &&
              draft.exercises.any { row -> row.exerciseId == it.exerciseId }
          } +
            plan.selections.map {
              PlannerSlotSelection(it.slot.slotId, it.slot.selectionId, it.exercise.exerciseId)
            }),
      )
    entities.flush()
    jdbc.update(
      "INSERT INTO planner_explanations(proposal_id,version,payload) VALUES (?,?,?::jsonb)",
      p.proposalId,
      p.currentVersion,
      json.writeValueAsString(explanation),
    )
    return CalendarDraftResponse(
      snapshot.request.requestId.toString(),
      CalendarDraftContext(
        snapshot.request.expectedRevision,
        snapshot.request.expectedCatalogRevision,
        snapshot.capturedAtMillis,
      ),
      p,
    )
  }

  companion object {
    val goals = setOf("STRENGTH", "MUSCLE_GAIN", "FAT_LOSS", "GENERAL_FITNESS", "ENDURANCE")

    fun fingerprint(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
