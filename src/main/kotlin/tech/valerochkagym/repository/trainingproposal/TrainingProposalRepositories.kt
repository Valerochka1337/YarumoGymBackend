package tech.valerochkagym.repository.trainingproposal

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import tech.valerochkagym.repository.model.*

interface TrainingProposalRepository : JpaRepository<TrainingProposalEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from TrainingProposalEntity p where p.id = :id")
  fun writeLock(id: UUID): TrainingProposalEntity?

  fun findByRecipientIdAndCreatedSequenceLessThanOrderByCreatedSequenceDesc(
    recipientId: UUID,
    before: Long,
    pageable: Pageable,
  ): List<TrainingProposalEntity>
}

interface TrainingProposalVersionRepository :
  JpaRepository<TrainingProposalVersionEntity, TrainingProposalVersionId>

interface TrainingProposalReceiptRepository :
  JpaRepository<TrainingProposalReceiptEntity, TrainingProposalReceiptId>

interface TrainingProposalOperationRepository :
  JpaRepository<TrainingProposalOperationEntity, TrainingProposalOperationId>

interface CalendarPlannerRefinementRepository :
  JpaRepository<CalendarPlannerRefinementEntity, CalendarPlannerRefinementId> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
    "select r from CalendarPlannerRefinementEntity r where r.ownerId = :ownerId and r.requestId = :requestId"
  )
  fun writeLock(ownerId: UUID, requestId: UUID): CalendarPlannerRefinementEntity?
}
