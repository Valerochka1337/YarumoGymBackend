package tech.valerochkagym.controller.model

import java.util.UUID

data class PlannerV2Capabilities(
  val schemaVersion: Int = 2,
  val protocol: Int = 2,
  val capability: String = "deterministic-workout-planner-v2",
)

data class PlannerV2CreateRequest(
  val requestId: UUID,
  val variant: Int = 0,
  val expectedRevision: Long,
  val expectedCatalogRevision: Long,
  val startsAtMillis: Long,
  val timeZoneId: String,
  val gymIds: List<UUID>,
  val excludedExerciseIds: List<UUID>,
  val excludedEquipmentIds: List<String>,
  val priorityMuscles: List<String>,
  val includeNotes: Boolean,
  val availableDurationMinutes: Int,
)

data class PlannerV2RefinementRequest(
  val requestId: UUID,
  val variant: Int = 0,
  val expectedRevision: Long,
  val expectedCatalogRevision: Long,
  val baseProposalVersion: Int,
  val approvalDraft: ApprovalDraft,
  val changes: List<PlannerV2Change>,
)

data class PlannerV2Change(
  val kind: String,
  val slotId: String? = null,
  val selectionId: String? = null,
  val exerciseId: UUID,
)
