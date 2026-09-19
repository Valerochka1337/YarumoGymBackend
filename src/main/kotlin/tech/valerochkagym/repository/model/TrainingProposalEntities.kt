package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class TrainingProposalSource {
  AI
}

enum class TrainingProposalStatus {
  PENDING,
  APPROVED,
  REJECTED,
  REVOKED,
  STALE,
}

@Entity
@Table(name = "training_proposals")
class TrainingProposalEntity(
  @Id var id: UUID = UUID.randomUUID(),
  var recipientId: UUID = UUID(0, 0),
  @Enumerated(EnumType.STRING) var source: TrainingProposalSource = TrainingProposalSource.AI,
  @Enumerated(EnumType.STRING) var status: TrainingProposalStatus = TrainingProposalStatus.PENDING,
  var currentVersion: Int = 1,
  @Column(insertable = false, updatable = false) var createdSequence: Long = 0,
  var createdAt: Instant = Instant.EPOCH,
  var updatedAt: Instant = Instant.EPOCH,
  var expiresAt: Instant = Instant.EPOCH,
)

data class TrainingProposalVersionId(var proposalId: UUID = UUID(0, 0), var version: Int = 0) :
  Serializable

@Entity
@Table(name = "training_proposal_versions")
@IdClass(TrainingProposalVersionId::class)
class TrainingProposalVersionEntity(
  @Id var proposalId: UUID = UUID(0, 0),
  @Id var version: Int = 1,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var draft: String = "{}",
  var ownerRevision: Long = 0,
  var catalogRevision: Long = 0,
  var createdAt: Instant = Instant.EPOCH,
)

data class TrainingProposalReceiptId(var proposalId: UUID = UUID(0, 0), var version: Int = 0) :
  Serializable

@Entity
@Table(name = "training_proposal_receipts")
@IdClass(TrainingProposalReceiptId::class)
class TrainingProposalReceiptEntity(
  @Id var proposalId: UUID = UUID(0, 0),
  @Id var version: Int = 1,
  var routineId: UUID = UUID(0, 0),
  var calendarPlanId: UUID = UUID(0, 0),
  var revision: Long = 0,
  var approvedAt: Instant = Instant.EPOCH,
)

data class TrainingProposalOperationId(
  var recipientId: UUID = UUID(0, 0),
  var operationId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "training_proposal_operations")
@IdClass(TrainingProposalOperationId::class)
class TrainingProposalOperationEntity(
  @Id var recipientId: UUID = UUID(0, 0),
  @Id var operationId: UUID = UUID(0, 0),
  var proposalId: UUID = UUID(0, 0),
  var version: Int = 1,
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var requestSha256: String = "",
  @JdbcTypeCode(SqlTypes.VARBINARY)
  @Column(columnDefinition = "bytea")
  var rawRequest: ByteArray = byteArrayOf(),
  var createdAt: Instant = Instant.EPOCH,
)

data class CalendarPlannerRefinementId(
  var ownerId: UUID = UUID(0, 0),
  var requestId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "calendar_planner_refinements")
@IdClass(CalendarPlannerRefinementId::class)
class CalendarPlannerRefinementEntity(
  @Id var ownerId: UUID = UUID(0, 0),
  @Id var requestId: UUID = UUID(0, 0),
  var proposalId: UUID = UUID(0, 0),
  var expectedVersion: Int = 0,
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var requestSha256: String = "",
  @JdbcTypeCode(SqlTypes.VARBINARY)
  @Column(columnDefinition = "bytea")
  var rawRequest: ByteArray = byteArrayOf(),
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var receipt: String? = null,
  var leaseUntil: Instant = Instant.EPOCH,
  var createdAt: Instant = Instant.EPOCH,
)
