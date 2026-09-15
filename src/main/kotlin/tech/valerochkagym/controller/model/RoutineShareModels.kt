package tech.valerochkagym.controller.model

import java.util.UUID

data class CreateRoutineShareRequest(
  val operationId: String,
  val routineId: String,
  val expectedRevision: Long,
  val catalogRevision: Long,
)

data class RoutineShareCreated(
  val shareId: UUID,
  val url: String,
  val routineId: UUID,
  val createdAt: Long,
)

data class RoutineShareListItem(
  val shareId: UUID,
  val routineId: UUID,
  val url: String,
  val createdAt: Long,
  val active: Boolean,
)

data class RoutineShareList(val items: List<RoutineShareListItem>)

data class RevokeRoutineShareRequest(val operationId: String)

data class RoutineShareRevoked(val shareId: UUID, val revokedAt: Long)

data class ImportRoutineShareRequest(val operationId: String)

data class RoutineShareImport(
  val routineId: UUID,
  val revision: Long,
  val importedAt: Long,
  val alreadyImported: Boolean,
)

data class RoutineSharePreview(
  val title: String,
  val estimatedDurationSeconds: Long,
  val exercises: List<RoutineSharePreviewExercise>,
)

data class RoutineSharePreviewExercise(
  val exerciseKey: UUID,
  val name: String,
  val type: String,
  val sets: List<RoutineSharePreviewSet>,
  val restSeconds: Int,
)

data class RoutineSharePreviewSet(
  val weightKg: Double?,
  val reps: Int?,
  val durationSec: Int?,
  val speedKmh: Double?,
  val inclinePct: Double?,
)
