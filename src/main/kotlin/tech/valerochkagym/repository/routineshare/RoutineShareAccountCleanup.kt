package tech.valerochkagym.repository.routineshare

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tech.valerochkagym.repository.data.HeadRepository

/** Revokes an author's capability URLs before their account row is removed. */
@Repository
class RoutineShareAccountCleanup(
  private val heads: HeadRepository,
  private val jdbc: JdbcTemplate,
) {
  /** Acquires the author-head then share-row prefix used by create/revoke operations. */
  fun preflightAccountDeletion(authorId: UUID) {
    heads.writeLockOrNull(authorId)
    jdbc.query(
      "SELECT id FROM routine_shares WHERE author_id=? ORDER BY id FOR UPDATE",
      { _, _ -> Unit },
      authorId,
    )
  }

  /** Requires [preflightAccountDeletion] in the surrounding transaction. */
  fun revokeAuthorLocked(authorId: UUID) {
    jdbc.update(
      "UPDATE routine_shares SET revoked_at=COALESCE(revoked_at,now()) WHERE author_id=?",
      authorId,
    )
  }
}
