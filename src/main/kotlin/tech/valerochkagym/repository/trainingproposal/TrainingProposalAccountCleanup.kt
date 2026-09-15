package tech.valerochkagym.repository.trainingproposal

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.data.HeadRepository

/**
 * Deletes only proposal journal rows owned by an already-confirmed recipient deletion. Approval
 * records are ordinary owner sync records and remain untouched here; user deletion removes them
 * through their existing owner FK.
 */
@Repository
class TrainingProposalAccountCleanup(
  private val catalog: CatalogStateRepository,
  private val heads: HeadRepository,
  private val jdbc: JdbcTemplate,
) {
  /** Locks the proposal-mutator prefix without materializing a head for an invalid delete code. */
  fun preflightAccountDeletion(userId: UUID) {
    catalog.readLock()
    heads.writeLockOrNull(userId)
    jdbc.query(
      "SELECT id FROM training_proposals WHERE recipient_id=? ORDER BY id FOR UPDATE",
      { _, _ -> Unit },
      userId,
    )
  }

  /** Requires [preflightAccountDeletion] in the containing transaction. */
  fun removeRecipientLocked(userId: UUID) {
    jdbc.update("DELETE FROM training_proposal_operations WHERE recipient_id=?", userId)
    jdbc.update("DELETE FROM calendar_ai_attempts WHERE owner_id=?", userId)
    jdbc.update(
      "DELETE FROM training_proposal_receipts WHERE proposal_id IN (SELECT id FROM training_proposals WHERE recipient_id=?)",
      userId,
    )
    jdbc.update(
      "DELETE FROM training_proposal_versions WHERE proposal_id IN (SELECT id FROM training_proposals WHERE recipient_id=?)",
      userId,
    )
    jdbc.update("DELETE FROM training_proposals WHERE recipient_id=?", userId)
  }
}
