package tech.valerochkagym.repository.routineshare

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import tech.valerochkagym.repository.model.*

interface RoutineShareRepository : JpaRepository<RoutineShareEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from RoutineShareEntity s where s.id=:id and s.authorId=:authorId")
  fun authorWriteLock(id: UUID, authorId: UUID): RoutineShareEntity?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from RoutineShareEntity s where s.tokenDigest=:digest")
  fun tokenWriteLock(digest: String): RoutineShareEntity?

  @Query("select s from RoutineShareEntity s where s.tokenDigest=:digest and s.revokedAt is null")
  fun activeByDigest(digest: String): RoutineShareEntity?

  @Query(
    "select count(s) from RoutineShareEntity s where s.authorId=:authorId and s.sourceRoutineId=:routineId and s.revokedAt is null"
  )
  fun activeCount(authorId: UUID, routineId: UUID): Long

  fun findByAuthorIdAndSourceRoutineIdAndRevokedAtIsNullOrderByCreatedAtDesc(
    authorId: UUID,
    routineId: UUID,
    pageable: Pageable,
  ): List<RoutineShareEntity>
}

interface RoutineShareExerciseRepository :
  JpaRepository<RoutineShareExerciseEntity, RoutineShareExerciseId> {
  fun findByShareIdOrderByPositionAsc(shareId: UUID): List<RoutineShareExerciseEntity>
}

interface RoutineShareSetRepository : JpaRepository<RoutineShareSetEntity, RoutineShareSetId> {
  fun findByShareIdOrderByExercisePositionAscSetPositionAsc(
    shareId: UUID
  ): List<RoutineShareSetEntity>
}

interface RoutineShareOperationRepository :
  JpaRepository<RoutineShareOperationEntity, RoutineShareOperationId> {
  fun findByShareId(shareId: UUID): RoutineShareOperationEntity?
}

interface RoutineShareImportReceiptRepository :
  JpaRepository<RoutineShareImportReceiptEntity, RoutineShareImportReceiptId>

interface RoutineShareRevokeOperationRepository :
  JpaRepository<RoutineShareRevokeOperationEntity, RoutineShareRevokeOperationId>
