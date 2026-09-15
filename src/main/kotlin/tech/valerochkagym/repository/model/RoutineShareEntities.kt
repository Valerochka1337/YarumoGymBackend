package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "routine_shares")
class RoutineShareEntity(
  @Id var id: UUID = UUID.randomUUID(),
  var authorId: UUID? = null,
  @Column(updatable = false) var sourceRoutineId: UUID = UUID(0, 0),
  @Column(length = 64, updatable = false) var tokenDigest: String = "",
  @Column(length = 200, updatable = false) var title: String = "",
  @Column(updatable = false) var estimatedDurationSeconds: Long = 0,
  @Column(updatable = false) var createdAt: Instant = Instant.EPOCH,
  var revokedAt: Instant? = null,
)

data class RoutineShareExerciseId(var shareId: UUID = UUID(0, 0), var position: Int = 0) :
  Serializable

@Entity
@Table(name = "routine_share_exercises")
@IdClass(RoutineShareExerciseId::class)
class RoutineShareExerciseEntity(
  @Id var shareId: UUID = UUID(0, 0),
  @Id var position: Int = 0,
  @Column(updatable = false) var exerciseKey: UUID = UUID(0, 0),
  @Column(updatable = false) var standardExerciseId: UUID? = null,
  @Column(length = 200, updatable = false) var name: String = "",
  @Column(length = 16, updatable = false) var type: String = "STRENGTH",
  @Column(length = 16, updatable = false) var customMuscleGroup: String? = null,
  @Column(updatable = false) var restSeconds: Int = 90,
)

data class RoutineShareSetId(
  var shareId: UUID = UUID(0, 0),
  var exercisePosition: Int = 0,
  var setPosition: Int = 0,
) : Serializable

@Entity
@Table(name = "routine_share_sets")
@IdClass(RoutineShareSetId::class)
class RoutineShareSetEntity(
  @Id var shareId: UUID = UUID(0, 0),
  @Id var exercisePosition: Int = 0,
  @Id var setPosition: Int = 0,
  @Column(updatable = false) var weightKg: Double? = null,
  @Column(updatable = false) var reps: Int? = null,
  @Column(updatable = false) var durationSec: Int? = null,
  @Column(updatable = false) var speedKmh: Double? = null,
  @Column(updatable = false) var inclinePct: Double? = null,
)

data class RoutineShareOperationId(
  var authorId: UUID = UUID(0, 0),
  var operationId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "routine_share_operations")
@IdClass(RoutineShareOperationId::class)
class RoutineShareOperationEntity(
  @Id var authorId: UUID = UUID(0, 0),
  @Id var operationId: UUID = UUID(0, 0),
  var shareId: UUID = UUID(0, 0),
  @Column(length = 64) var requestSha256: String = "",
  var createdAt: Instant = Instant.EPOCH,
)

data class RoutineShareImportReceiptId(
  var recipientId: UUID = UUID(0, 0),
  var shareId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "routine_share_import_receipts")
@IdClass(RoutineShareImportReceiptId::class)
class RoutineShareImportReceiptEntity(
  @Id var recipientId: UUID = UUID(0, 0),
  @Id var shareId: UUID = UUID(0, 0),
  var routineId: UUID = UUID(0, 0),
  var revision: Long = 0,
  var importedAt: Instant = Instant.EPOCH,
)

data class RoutineShareRevokeOperationId(
  var authorId: UUID = UUID(0, 0),
  var operationId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "routine_share_revoke_operations")
@IdClass(RoutineShareRevokeOperationId::class)
class RoutineShareRevokeOperationEntity(
  @Id var authorId: UUID = UUID(0, 0),
  @Id var operationId: UUID = UUID(0, 0),
  var shareId: UUID = UUID(0, 0),
  var revokedAt: Instant = Instant.EPOCH,
)
