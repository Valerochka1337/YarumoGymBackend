package tech.valerochkagym.repository.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class CalendarAiAttemptState {
  PROCESSING,
  COMMITTING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  INTERRUPTED,
}

data class CalendarAiAttemptId(var ownerId: UUID = UUID(0, 0), var requestId: UUID = UUID(0, 0)) :
  Serializable

/**
 * Deliberately contains only the binding digest and a normalized terminal receipt. Provider and
 * request payloads must never be persisted here.
 */
@Entity
@Table(name = "calendar_ai_attempts")
@IdClass(CalendarAiAttemptId::class)
class CalendarAiAttemptEntity(
  @Id var ownerId: UUID = UUID(0, 0),
  @Id var requestId: UUID = UUID(0, 0),
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)", length = 64)
  var rawRequestSha256: String = "",
  @Enumerated(EnumType.STRING)
  var state: CalendarAiAttemptState = CalendarAiAttemptState.PROCESSING,
  var admittedAt: Instant = Instant.EPOCH,
  var deadlineAt: Instant = Instant.EPOCH,
  var leaseUntil: Instant = Instant.EPOCH,
  var terminalStatus: Int? = null,
  var terminalCode: String? = null,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var receipt: String? = null,
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var v2Receipt: String? = null,
)
