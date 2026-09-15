package tech.valerochkagym.controller.model

import java.util.UUID

data class ApprovalRequest(val operationId: String, val version: Int, val draft: ApprovalDraft)

data class ApprovalDraft(
  val name: String,
  val gymIds: List<String>,
  val exercises: List<PlannedExercise>,
  val startsAtMillis: Long,
  val timeZoneId: String,
)

data class PlannedExercise(
  val exerciseId: String,
  val restSeconds: Int?,
  val plannedSets: List<PlannedSet>,
)

data class PlannedSet(
  val weightKg: Double?,
  val reps: Int?,
  val durationSec: Int?,
  val speedKmh: Double?,
  val inclinePct: Double?,
)

data class RejectRequest(val version: Int, val reason: String?)

data class ProposalAuthor(val kind: String, val accountId: UUID?)

data class ProposalSnapshot(
  val version: Int,
  val draft: ApprovalDraft,
  val ownerRevision: Long,
  val catalogRevision: Long,
  val createdAt: Long,
)

data class ProposalResponse(
  val proposalId: UUID,
  val author: ProposalAuthor,
  val recipientId: UUID,
  val source: String,
  val status: String,
  val currentVersion: Int,
  val createdAt: Long,
  val updatedAt: Long,
  val expiresAt: Long,
  val snapshot: ProposalSnapshot,
)

data class ProposalListResponse(val items: List<ProposalResponse>, val nextCursor: String?)

data class AcceptedResult(
  val proposalId: UUID,
  val version: Int,
  val routineId: UUID,
  val calendarPlanId: UUID,
  val revision: Long,
  val approvedAt: Long,
)

data class DecisionResponse(
  val proposalId: UUID,
  val version: Int,
  val status: String,
  val updatedAt: Long,
)
