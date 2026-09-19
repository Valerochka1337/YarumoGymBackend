package tech.valerochkagym.service.routineshare

import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.*
import tech.valerochkagym.repository.routineshare.*
import tech.valerochkagym.service.ai.PlannerDuration
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.model.RecordKey
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory

@Service
class RoutineShareService(
  private val catalog: CatalogStateRepository,
  private val standard: StandardRepository,
  private val heads: HeadRepository,
  private val postgres: PostgresSyncRepository,
  private val records: RecordRepository,
  private val sessions: SessionRepository,
  private val shares: RoutineShareRepository,
  private val exercises: RoutineShareExerciseRepository,
  private val sets: RoutineShareSetRepository,
  private val operations: RoutineShareOperationRepository,
  private val receipts: RoutineShareImportReceiptRepository,
  private val trialReceipts: RoutineShareTrialReceiptRepository,
  private val revocations: RoutineShareRevokeOperationRepository,
  private val validator: RecordValidator,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val crypto: Crypto,
  private val clock: Clock,
) {
  private fun now(): Instant = clock.instant()

  private fun token(author: UUID, operation: UUID) = crypto.routineShareToken(author, operation)

  private fun url(author: UUID, operation: UUID) = "$publicOrigin/r/${token(author, operation)}"

  fun create(identity: Identity, request: CreateRoutineShareRequest): RoutineShareCreated {
    val operation = uuid(request.operationId)
    val routineId = uuid(request.routineId)
    if (request.expectedRevision < 0 || request.catalogRevision < 0) bad("Некорректная версия")
    val requestHash =
      crypto.sha256("$operation:$routineId:${request.expectedRevision}:${request.catalogRevision}")
    return tx.execute {
      val catalogHead = catalog.readLock()
      postgres.ensureHead(identity.userId)
      val head = heads.writeLock(identity.userId)
      requireSession(identity)
      val previous =
        operations.findById(RoutineShareOperationId(identity.userId, operation)).orElse(null)
      if (previous != null) {
        if (previous.requestSha256 != requestHash)
          conflict("operation_reused", "Операция уже использована")
        val share = shares.findById(previous.shareId).orElseThrow { unavailableShare() }
        return@execute created(share, operation)
      }
      if (head.revision != request.expectedRevision)
        conflict("routine_share_stale", "Программа или аккаунт изменились")
      if (catalogHead.active && catalogHead.revision != request.catalogRevision)
        conflict("catalog_stale", "Обновите каталог перед публикацией")
      val routine =
        records.findById(RecordId(identity.userId, "routine", routineId)).orElse(null)
          ?: throw ApiException(404, "routine_not_found", "Программа не найдена")
      if (routine.deleted || routine.payload == null)
        throw ApiException(404, "routine_not_found", "Программа не найдена")
      if (shares.activeCount(identity.userId, routineId) >= maxActiveShares)
        conflict("share_limit", "Достигнут лимит активных ссылок")
      val projection = project(identity.userId, requireNotNull(routine.payload))
      val share =
        RoutineShareEntity(
          authorId = identity.userId,
          sourceRoutineId = routineId,
          tokenDigest = crypto.sha256(token(identity.userId, operation)),
          title = projection.title,
          estimatedDurationSeconds = projection.duration,
          createdAt = now(),
        )
      shares.save(share)
      projection.exercises.forEach { snapshot ->
        exercises.save(
          RoutineShareExerciseEntity(
            shareId = share.id,
            position = snapshot.position,
            exerciseKey = snapshot.exerciseKey,
            standardExerciseId = snapshot.standardId,
            name = snapshot.name,
            type = snapshot.type,
            customMuscleGroup = snapshot.customMuscleGroup,
            restSeconds = snapshot.restSeconds,
          )
        )
        snapshot.sets.forEachIndexed { position, set ->
          sets.save(
            RoutineShareSetEntity(
              shareId = share.id,
              exercisePosition = snapshot.position,
              setPosition = position,
              weightKg = set.weightKg,
              reps = set.reps,
              durationSec = set.durationSec,
              speedKmh = set.speedKmh,
              inclinePct = set.inclinePct,
            )
          )
        }
      }
      operations.save(
        RoutineShareOperationEntity(
          identity.userId,
          operation,
          share.id,
          requestHash,
          share.createdAt,
        )
      )
      created(share, operation)
    }!!
  }

  fun list(identity: Identity, routine: String?, limit: Int): RoutineShareList {
    val routineId = routine?.let(::uuid) ?: bad("Требуется программа")
    if (limit !in 1..maxActiveShares) bad("Некорректный лимит")
    return tx.execute {
      postgres.ensureHead(identity.userId)
      heads.readLock(identity.userId)
      requireSession(identity)
      RoutineShareList(
        shares
          .findByAuthorIdAndSourceRoutineIdAndRevokedAtIsNullOrderByCreatedAtDesc(
            identity.userId,
            routineId,
            PageRequest.of(0, limit),
          )
          .map { share ->
            val operation =
              operations.findByShareId(share.id)
                ?: throw IllegalStateException("routine share has no operation")
            RoutineShareListItem(
              share.id,
              routineId,
              url(identity.userId, operation.operationId),
              share.createdAt.toEpochMilli(),
              true,
            )
          }
      )
    }!!
  }

  fun revoke(
    identity: Identity,
    shareId: UUID,
    request: RevokeRoutineShareRequest,
  ): RoutineShareRevoked {
    val operation = uuid(request.operationId)
    return tx.execute {
      postgres.ensureHead(identity.userId)
      heads.writeLock(identity.userId)
      val share = shares.authorWriteLock(shareId, identity.userId) ?: unavailableShare()
      requireSession(identity)
      val prior =
        revocations.findById(RoutineShareRevokeOperationId(identity.userId, operation)).orElse(null)
      if (prior != null) {
        if (prior.shareId != shareId) conflict("operation_reused", "Операция уже использована")
        return@execute RoutineShareRevoked(shareId, prior.revokedAt.toEpochMilli())
      }
      val revokedAt = share.revokedAt ?: now().also { share.revokedAt = it }
      revocations.save(
        RoutineShareRevokeOperationEntity(identity.userId, operation, shareId, revokedAt)
      )
      RoutineShareRevoked(shareId, revokedAt.toEpochMilli())
    }!!
  }

  fun preview(token: String): RoutineSharePreview? {
    if (!token.matches(tokenPattern)) return null
    val share = shares.activeByDigest(crypto.sha256(token)) ?: return null
    return preview(share)
  }

  fun import(
    identity: Identity,
    rawToken: String,
    request: ImportRoutineShareRequest,
  ): RoutineShareImport {
    val operation = uuid(request.operationId)
    if (!rawToken.matches(tokenPattern)) unavailableShare()
    return tx.execute {
      catalog.readLock()
      postgres.ensureHead(identity.userId)
      val head = heads.writeLock(identity.userId)
      val share = shares.tokenWriteLock(crypto.sha256(rawToken)) ?: unavailableShare()
      requireSession(identity)
      if (share.revokedAt != null) unavailableShare()
      val receipt =
        receipts.findById(RoutineShareImportReceiptId(identity.userId, share.id)).orElse(null)
      if (receipt != null)
        return@execute RoutineShareImport(
          receipt.routineId,
          receipt.revision,
          receipt.importedAt.toEpochMilli(),
          true,
        )
      val rows = exercises.findByShareIdOrderByPositionAsc(share.id)
      val snapshotSets = sets.findByShareIdOrderByExercisePositionAscSetPositionAsc(share.id)
      validateStandards(rows)
      val importedAt = now()
      val revision = head.revision + 1
      val custom = rows.filter { it.standardExerciseId == null }.distinctBy { it.exerciseKey }
      custom.forEach { exercise ->
        val payload = customExercisePayload(exercise, importedAt)
        validator.validate("exercise", payload)
        records.save(
          RecordEntity(
            identity.userId,
            "exercise",
            exercise.exerciseKey,
            revision,
            false,
            json.writeValueAsString(payload),
          )
        )
      }
      val routineId = UUID.randomUUID()
      val routine = importedRoutinePayload(share, rows, snapshotSets, importedAt)
      validator.validate("routine", routine)
      records.save(
        RecordEntity(
          identity.userId,
          "routine",
          routineId,
          revision,
          false,
          json.writeValueAsString(routine),
        )
      )
      head.revision = revision
      receipts.save(
        RoutineShareImportReceiptEntity(identity.userId, share.id, routineId, revision, importedAt)
      )
      RoutineShareImport(routineId, revision, importedAt.toEpochMilli(), false)
    }!!
  }

  /**
   * Commits only server-reconstructed records. The browser supplies facts and snapshot array
   * indices; it never supplies an exercise/routine/workout identity or a mutable plan.
   */
  fun saveTrial(
    identity: Identity,
    rawToken: String,
    request: SaveRoutineShareTrialRequest,
  ): RoutineShareTrialSaved {
    val operation = uuid(request.operationId)
    if (!rawToken.matches(tokenPattern)) unavailableShare()
    return tx.execute {
      catalog.readLock()
      postgres.ensureHead(identity.userId)
      val head = heads.writeLock(identity.userId)
      val share = shares.tokenWriteLock(crypto.sha256(rawToken)) ?: unavailableShare()
      requireSession(identity)
      // This deliberately precedes receipt lookup: a revoked share cannot disclose a receipt.
      if (share.revokedAt != null) unavailableShare()
      val requestHash = crypto.sha256("${share.id}:${canonicalTrial(operation, request)}")
      val prior =
        trialReceipts.findById(RoutineShareTrialReceiptId(identity.userId, operation)).orElse(null)
      if (prior != null) {
        if (prior.shareId != share.id || prior.requestSha256 != requestHash)
          conflict("operation_reused", "Операция уже использована")
        return@execute prior.saved(true)
      }
      validateTrialEnvelope(request)
      val rows = exercises.findByShareIdOrderByPositionAsc(share.id)
      val snapshotSets = sets.findByShareIdOrderByExercisePositionAscSetPositionAsc(share.id)
      validateStandards(rows)
      val completed = validateTrialFacts(request, rows, snapshotSets)

      val savedAt = now()
      val revision = head.revision + 1
      val customIds =
        rows
          .filter { it.standardExerciseId == null }
          .distinctBy { it.exerciseKey }
          .associate { it.exerciseKey to UUID.randomUUID() }
      val customRecords =
        rows
          .filter { it.standardExerciseId == null }
          .distinctBy { it.exerciseKey }
          .map { row ->
            val payload = customExercisePayload(row, savedAt)
            validator.validate("exercise", payload)
            Record("exercise", customIds.getValue(row.exerciseKey), revision, false, payload)
          }
      val routineId = UUID.randomUUID()
      val workoutId = UUID.randomUUID()
      val routine = trialRoutinePayload(share, rows, snapshotSets, customIds, savedAt)
      val workout =
        trialWorkoutPayload(share, rows, snapshotSets, completed, customIds, routineId, request)
      validator.validate("routine", routine)
      validator.validate("workout", workout)
      val generated =
        customRecords +
          listOf(
            Record("routine", routineId, revision, false, routine),
            Record("workout", workoutId, revision, false, workout),
          )
      val existing =
        records.findByUserIdOrderByKindAscIdAsc(identity.userId).associate {
          RecordKey(it.kind, it.id) to
            Record(it.kind, it.id, it.revision, it.deleted, it.payload?.let(json::readTree))
        }
      val commonRows = standard.findAllByOrderByKindAscIdAsc()
      val common =
        commonRows.associate {
          RecordKey(it.kind, it.id) to
            Record(it.kind, it.id, it.revision, false, json.readTree(it.payload))
        }
      val next = existing + generated.associateBy { RecordKey(it.kind, it.id) }
      if (
        next.size > maxAccountRecords ||
          next.values.sumOf { it.payload?.toString()?.toByteArray()?.size?.toLong() ?: 0L } >
            maxAccountBytes
      )
        conflict("account_limit", "Превышен лимит аккаунта")
      val changed = generated.map { RecordKey(it.kind, it.id) }.toSet()
      validator.references(next + common, changed, existing)
      validator.archivedReferences(
        next + common,
        existing,
        commonRows.filter { it.archived }.map { RecordKey(it.kind, it.id) }.toSet(),
      )
      generated.forEach {
        records.save(
          RecordEntity(
            identity.userId,
            it.kind,
            it.id,
            revision,
            false,
            json.writeValueAsString(it.payload),
          )
        )
      }
      head.revision = revision
      // Trial sets always receive syncId, which requires a v3-capable regular sync client.
      head.minSyncVersion = maxOf(head.minSyncVersion, 3)
      val receipt =
        RoutineShareTrialReceiptEntity(
          identity.userId,
          operation,
          share.id,
          requestHash,
          routineId,
          workoutId,
          revision,
          savedAt,
        )
      trialReceipts.save(receipt)
      receipt.saved(false)
    }!!
  }

  private fun created(share: RoutineShareEntity, operation: UUID) =
    RoutineShareCreated(
      share.id,
      url(requireNotNull(share.authorId), operation),
      share.sourceRoutineId,
      share.createdAt.toEpochMilli(),
    )

  private fun preview(share: RoutineShareEntity): RoutineSharePreview {
    val rows = exercises.findByShareIdOrderByPositionAsc(share.id)
    val allSets =
      sets.findByShareIdOrderByExercisePositionAscSetPositionAsc(share.id).groupBy {
        it.exercisePosition
      }
    return RoutineSharePreview(
      share.title,
      share.estimatedDurationSeconds,
      rows.map { row ->
        RoutineSharePreviewExercise(
          row.exerciseKey,
          row.name,
          row.type,
          allSets[row.position].orEmpty().map { it.preview() },
          row.restSeconds,
        )
      },
    )
  }

  private fun project(author: UUID, rawRoutine: String): SnapshotProjection {
    val routine = json.readTree(rawRoutine)
    val rows =
      routine["exercises"]?.toList()?.sortedBy { it["position"].asInt() }
        ?: bad("Некорректная программа")
    val sourceIds = rows.map { uuidNode(it["exerciseId"]) }
    val personal =
      records.findByUserIdAndKindAndIdInAndDeletedFalse(author, "exercise", sourceIds).associateBy {
        it.id
      }
    val standardRows =
      standard.findByKindAndIdInAndArchivedFalse("exercise", sourceIds).associateBy { it.id }
    val customKeys = mutableMapOf<UUID, UUID>()
    val projected =
      rows.mapIndexed { position, row ->
        val sourceId = uuidNode(row["exerciseId"])
        val standardId = standardRows[sourceId]?.id
        val sourcePayload =
          standardRows[sourceId]?.payload
            ?: personal[sourceId]?.payload
            ?: throw ApiException(409, "exercise_unavailable", "Упражнение недоступно")
        val payload = json.readTree(sourcePayload)
        val muscle = if (standardId == null) payload["muscleGroup"]?.asString() else null
        if (standardId == null && muscle !in muscleGroups) bad("Некорректное упражнение")
        SnapshotExercise(
          position,
          if (standardId == null) customKeys.getOrPut(sourceId, ::randomUuid) else standardId,
          standardId,
          payload["name"]?.asString() ?: bad("Некорректное упражнение"),
          payload["type"]?.asString() ?: bad("Некорректное упражнение"),
          muscle,
          row["restSeconds"]?.takeUnless { it.isNull }?.asInt() ?: 90,
          row["plannedSets"]
            ?.toList()
            ?.map { set ->
              RoutineSharePreviewSet(
                nullableDouble(set["weightKg"]),
                nullableInt(set["reps"]),
                nullableInt(set["durationSec"]),
                nullableDouble(set["speedKmh"]),
                nullableDouble(set["inclinePct"]),
              )
            }
            .orEmpty(),
        )
      }
    val durationExercises =
      projected.map {
        PlannedExercise(
          it.exerciseKey.toString(),
          it.restSeconds,
          it.sets.map { set ->
            PlannedSet(set.weightKg, set.reps, set.durationSec, set.speedKmh, set.inclinePct)
          },
        )
      }
    return SnapshotProjection(
      routine["name"]?.asString() ?: bad("Некорректная программа"),
      PlannerDuration.seconds(durationExercises),
      projected,
    )
  }

  private fun validateStandards(rows: List<RoutineShareExerciseEntity>) {
    val standards = rows.mapNotNull { it.standardExerciseId }.toSet()
    val current =
      standard.findByKindAndIdInAndArchivedFalse("exercise", standards).associateBy { it.id }
    if (current.size != standards.size) standardUnavailable()
    rows
      .filter { it.standardExerciseId != null }
      .forEach { snapshot ->
        val type =
          json
            .readTree(current.getValue(requireNotNull(snapshot.standardExerciseId)).payload)["type"]
            ?.asString()
        if (type != snapshot.type) standardUnavailable()
      }
  }

  private fun importedRoutinePayload(
    share: RoutineShareEntity,
    rows: List<RoutineShareExerciseEntity>,
    allSets: List<RoutineShareSetEntity>,
    importedAt: Instant,
  ): JsonNode {
    val factory = JsonNodeFactory.instance
    val root = factory.objectNode()
    root.put("name", share.title)
    root.put("note", "")
    root.put("updatedAt", importedAt.toEpochMilli())
    root.putArray("gymIds")
    val target = root.putArray("exercises")
    val grouped = allSets.groupBy { it.exercisePosition }
    rows.forEach { row ->
      val node = target.addObject()
      node.put("exerciseId", (row.standardExerciseId ?: row.exerciseKey).toString())
      node.put("position", row.position)
      node.put("restSeconds", row.restSeconds)
      val setNodes = node.putArray("plannedSets")
      grouped[row.position].orEmpty().forEach { set ->
        setNodes.addObject().apply {
          nullable("weightKg", set.weightKg)
          nullable("reps", set.reps)
          nullable("durationSec", set.durationSec)
          nullable("speedKmh", set.speedKmh)
          nullable("inclinePct", set.inclinePct)
        }
      }
    }
    return root
  }

  private fun trialRoutinePayload(
    share: RoutineShareEntity,
    rows: List<RoutineShareExerciseEntity>,
    allSets: List<RoutineShareSetEntity>,
    customIds: Map<UUID, UUID>,
    savedAt: Instant,
  ): JsonNode {
    val root = JsonNodeFactory.instance.objectNode()
    root.put("name", share.title)
    root.put("note", "")
    root.put("updatedAt", savedAt.toEpochMilli())
    root.putArray("gymIds")
    val grouped = allSets.groupBy { it.exercisePosition }
    val target = root.putArray("exercises")
    rows.forEach { row ->
      target.addObject().apply {
        put(
          "exerciseId",
          (row.standardExerciseId ?: customIds.getValue(row.exerciseKey)).toString(),
        )
        put("position", row.position)
        put("restSeconds", row.restSeconds)
        val plans = putArray("plannedSets")
        grouped[row.position].orEmpty().forEach { plan ->
          plans.addObject().apply {
            nullable("weightKg", plan.weightKg)
            nullable("reps", plan.reps)
            nullable("durationSec", plan.durationSec)
            nullable("speedKmh", plan.speedKmh)
            nullable("inclinePct", plan.inclinePct)
          }
        }
      }
    }
    return root
  }

  private fun trialWorkoutPayload(
    share: RoutineShareEntity,
    rows: List<RoutineShareExerciseEntity>,
    allSets: List<RoutineShareSetEntity>,
    completed: Map<Pair<Int, Int>, RoutineShareTrialSet>,
    customIds: Map<UUID, UUID>,
    routineId: UUID,
    request: SaveRoutineShareTrialRequest,
  ): JsonNode {
    val root = JsonNodeFactory.instance.objectNode()
    root.put("name", share.title)
    root.put("note", "")
    root.put("routineId", routineId.toString())
    root.put("startedAt", request.startedAt)
    root.put("finishedAt", request.finishedAt)
    root.putArray("gymIds")
    root.put("coachRevision", 0)
    val sections = root.putArray("exercises")
    rows.forEach { row ->
      val facts =
        allSets
          .filter { it.exercisePosition == row.position }
          .mapNotNull { plan -> completed[row.position to plan.setPosition]?.let { plan to it } }
      if (facts.isNotEmpty()) {
        sections.addObject().apply {
          put("sectionId", UUID.randomUUID().toString())
          put(
            "exerciseId",
            (row.standardExerciseId ?: customIds.getValue(row.exerciseKey)).toString(),
          )
          put("position", row.position)
          val setNodes = putArray("sets")
          facts.forEach { (plan, fact) ->
            setNodes.addObject().apply {
              nullable("weightKg", fact.weightKg)
              nullable("reps", fact.reps)
              nullable("durationSec", fact.durationSec)
              nullable("speedKmh", fact.speedKmh)
              nullable("inclinePct", fact.inclinePct)
              put("setIndex", plan.setPosition)
              put("isCompleted", true)
              put("completedAt", fact.completedAt)
              put("note", "")
              put("syncId", UUID.randomUUID().toString())
              nullable("originalWeightKg", plan.weightKg)
              nullable("originalReps", plan.reps)
              nullable("originalDurationSec", plan.durationSec)
              nullable("originalSpeedKmh", plan.speedKmh)
              nullable("originalInclinePct", plan.inclinePct)
              nullable("targetWeightKg", plan.weightKg)
              nullable("targetReps", plan.reps)
              nullable("targetDurationSec", plan.durationSec)
              nullable("targetSpeedKmh", plan.speedKmh)
              nullable("targetInclinePct", plan.inclinePct)
              nullable("actualWeightKg", fact.weightKg)
              nullable("actualReps", fact.reps)
              nullable("actualDurationSec", fact.durationSec)
              nullable("actualSpeedKmh", fact.speedKmh)
              nullable("actualInclinePct", fact.inclinePct)
              put(
                "setType",
                when (row.type) {
                  "STRENGTH" -> "WORK"
                  else -> row.type
                },
              )
              put("reportedFeelingsJson", "[]")
              putNull("restSnapshotJson")
              put("coachMutationRevision", 0)
            }
          }
        }
      }
    }
    return root
  }

  private fun validateTrialEnvelope(request: SaveRoutineShareTrialRequest) {
    if (request.startedAt < 0 || request.finishedAt < request.startedAt) bad("Некорректное время")
    if (request.completedSets.size !in 1..maxTrialSets) bad("Некорректные результаты")
  }

  private fun validateTrialFacts(
    request: SaveRoutineShareTrialRequest,
    rows: List<RoutineShareExerciseEntity>,
    snapshotSets: List<RoutineShareSetEntity>,
  ): Map<Pair<Int, Int>, RoutineShareTrialSet> {
    val rowByPosition = rows.associateBy { it.position }
    val plans = snapshotSets.associateBy { it.exercisePosition to it.setPosition }
    if (rows.map { it.position } != rows.indices.toList()) unavailableShare()
    val result = linkedMapOf<Pair<Int, Int>, RoutineShareTrialSet>()
    request.completedSets.forEach { fact ->
      val key = fact.exerciseIndex to fact.setIndex
      val row = rowByPosition[fact.exerciseIndex] ?: bad("Подход отсутствует в программе")
      if (plans[key] == null || result.put(key, fact) != null) bad("Некорректный подход")
      if (fact.completedAt !in request.startedAt..request.finishedAt) bad("Некорректное время")
      validateTrialNumbers(row.type, fact)
    }
    return result
  }

  private fun validateTrialNumbers(type: String, fact: RoutineShareTrialSet) {
    fun valid(value: Double?, min: Double = 0.0) =
      value != null && value.isFinite() && value in min..1_000_000.0
    fun integer(value: Int?) = value != null && value in 0..1_000_000
    val validType =
      when (type) {
        "STRENGTH" ->
          valid(fact.weightKg) &&
            integer(fact.reps) &&
            fact.durationSec == null &&
            fact.speedKmh == null &&
            fact.inclinePct == null
        "TIMED" ->
          integer(fact.durationSec) &&
            fact.weightKg == null &&
            fact.reps == null &&
            fact.speedKmh == null &&
            fact.inclinePct == null
        "CARDIO" ->
          integer(fact.durationSec) &&
            fact.weightKg == null &&
            fact.reps == null &&
            (fact.speedKmh == null || valid(fact.speedKmh)) &&
            (fact.inclinePct == null || valid(fact.inclinePct, -100.0))
        else -> false
      }
    if (!validType) bad("Некорректные фактические значения")
  }

  private fun canonicalTrial(operation: UUID, request: SaveRoutineShareTrialRequest): String =
    json.writeValueAsString(
      linkedMapOf(
        "operationId" to operation.toString(),
        "startedAt" to request.startedAt,
        "finishedAt" to request.finishedAt,
        "completedSets" to
          request.completedSets
            .sortedWith(compareBy<RoutineShareTrialSet> { it.exerciseIndex }.thenBy { it.setIndex })
            .map {
              linkedMapOf(
                "exerciseIndex" to it.exerciseIndex,
                "setIndex" to it.setIndex,
                "completedAt" to it.completedAt,
                "weightKg" to it.weightKg,
                "reps" to it.reps,
                "durationSec" to it.durationSec,
                "speedKmh" to it.speedKmh,
                "inclinePct" to it.inclinePct,
              )
            },
      )
    )

  private fun RoutineShareTrialReceiptEntity.saved(alreadySaved: Boolean) =
    RoutineShareTrialSaved(routineId, workoutId, revision, savedAt.toEpochMilli(), alreadySaved)

  private fun customExercisePayload(
    row: RoutineShareExerciseEntity,
    importedAt: Instant,
  ): JsonNode =
    JsonNodeFactory.instance.objectNode().apply {
      put("name", row.name)
      put("muscleGroup", row.customMuscleGroup)
      put("type", row.type)
      put("isCustom", true)
      put("updatedAt", importedAt.toEpochMilli())
      put("needsMuscleMapReview", false)
      put("equipmentRequirementState", "KNOWN")
      putArray("muscles")
      putArray("equipmentIds")
    }

  private fun tools.jackson.databind.node.ObjectNode.nullable(name: String, value: Double?) {
    if (value == null) putNull(name) else put(name, value)
  }

  private fun tools.jackson.databind.node.ObjectNode.nullable(name: String, value: Int?) {
    if (value == null) putNull(name) else put(name, value)
  }

  private fun RoutineShareSetEntity.preview() =
    RoutineSharePreviewSet(weightKg, reps, durationSec, speedKmh, inclinePct)

  private fun nullableDouble(node: JsonNode?): Double? = node?.takeUnless { it.isNull }?.asDouble()

  private fun nullableInt(node: JsonNode?): Int? = node?.takeUnless { it.isNull }?.asInt()

  private fun uuidNode(node: JsonNode?): UUID =
    node?.asString()?.let(::uuid) ?: bad("Некорректный UUID")

  private fun uuid(raw: String): UUID =
    try {
      UUID.fromString(raw).also { if (it.toString() != raw) bad("UUID должен быть каноническим") }
    } catch (_: IllegalArgumentException) {
      bad("Некорректный UUID")
    }

  private fun randomUuid(): UUID = UUID.randomUUID()

  private fun requireSession(identity: Identity) {
    val session = sessions.lock(identity.sessionId) ?: unavailableSession()
    if (
      session.userId != identity.userId ||
        session.revokedAt != null ||
        !session.accessExpiresAt.isAfter(now()) ||
        !session.refreshExpiresAt.isAfter(now())
    )
      unavailableSession()
  }

  private fun unavailableShare(): Nothing =
    throw ApiException(404, "share_unavailable", "Ссылка недоступна")

  private fun standardUnavailable(): Nothing =
    throw ApiException(409, "standard_exercise_unavailable", "Стандартное упражнение недоступно")

  private fun unavailableSession(): Nothing =
    throw ApiException(401, "unauthorized", "Войдите в аккаунт заново")

  private fun conflict(code: String, message: String): Nothing =
    throw ApiException(409, code, message)

  private data class SnapshotProjection(
    val title: String,
    val duration: Long,
    val exercises: List<SnapshotExercise>,
  )

  private data class SnapshotExercise(
    val position: Int,
    val exerciseKey: UUID,
    val standardId: UUID?,
    val name: String,
    val type: String,
    val customMuscleGroup: String?,
    val restSeconds: Int,
    val sets: List<RoutineSharePreviewSet>,
  )

  private companion object {
    const val maxActiveShares = 50
    const val maxTrialSets = 200
    const val maxAccountRecords = 20_000
    const val maxAccountBytes = 16L * 1024 * 1024
    const val publicOrigin = "https://api.valerochkagym.tech"
    val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    val muscleGroups =
      setOf("CHEST", "BACK", "LEGS", "SHOULDERS", "ARMS", "CORE", "CARDIO", "FULL_BODY")
  }
}
