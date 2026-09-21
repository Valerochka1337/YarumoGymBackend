package tech.valerochkagym.service.data

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.ChangesPage
import tech.valerochkagym.controller.model.PushRequest
import tech.valerochkagym.controller.model.PushResult
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.controller.model.Snapshot
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.OperationRepository
import tech.valerochkagym.repository.data.PostgresSyncRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.model.CatalogStateEntity
import tech.valerochkagym.repository.model.OperationEntity
import tech.valerochkagym.repository.model.OperationId
import tech.valerochkagym.repository.model.RecordEntity
import tech.valerochkagym.service.model.RecordKey
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

@Service
class SyncService(
  private val catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
  private val standard: tech.valerochkagym.repository.catalog.StandardRepository,
  private val heads: HeadRepository,
  private val recordRows: RecordRepository,
  private val operations: OperationRepository,
  private val postgres: PostgresSyncRepository,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val validator: RecordValidator,
  private val crypto: Crypto,
  private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
  companion object {
    const val strengthPlannerCapability = "strength-planner-personalization"
    const val workoutRirCapability = "workout-rir-v1"
    const val agenticPlannerCapability = "ai-planner-agentic-v1"
    const val plannerDefaultAccentsCapability = "planner-default-accents-v2"
    private const val strengthPlannerProfileKind = "strength_planner_profile"
    private const val plannerExercisePreferencesKind = "planner_exercise_preferences"
    private const val plannerExerciseAccentsKind = "planner_exercise_accents"
    private const val workoutEffortKind = "workout_effort"
  }

  private fun strengthPlannerProfileId(owner: UUID): UUID =
    UUID.nameUUIDFromBytes(
      "ValerochkaGym.strength-planner-profile.v1:$owner".toByteArray(Charsets.UTF_8)
    )

  private fun workoutEffortId(owner: UUID, workout: UUID): UUID =
    UUID.nameUUIDFromBytes(
      "ValerochkaGym.workout-effort.v1:$owner:$workout".toByteArray(Charsets.UTF_8)
    )

  private fun head(user: UUID, exclusive: Boolean): Long {
    postgres.ensureHead(user)
    return (if (exclusive) heads.writeLock(user) else heads.readLock(user)).revision
  }

  private fun plannerExercisePreferencesId(owner: UUID): UUID =
    UUID.nameUUIDFromBytes(
      "ValerochkaGym.planner-exercise-preferences.v1:$owner".toByteArray(Charsets.UTF_8)
    )

  private fun plannerExerciseAccentsId(owner: UUID): UUID =
    UUID.nameUUIDFromBytes(
      "ValerochkaGym.planner-default-accents.v2:$owner".toByteArray(Charsets.UTF_8)
    )

  private fun records(user: UUID): List<Record> =
    recordRows.findByUserIdOrderByKindAscIdAsc(user).map {
      Record(it.kind, it.id, it.revision, it.deleted, it.payload?.let(json::readTree))
    }

  fun snapshot(
    user: UUID,
    version: String? = "2",
    capabilities: Set<String> = emptySet(),
  ): Snapshot =
    tx.execute {
      requireVersion(catalog.readLock(), version)
      val revision = head(user, false)
      requireAccountVersion(user, version)
      Snapshot(
        revision,
        records(user).filter { visible(it.kind, capabilities) }.map { project(it, capabilities) },
      )
    }!!

  fun push(
    user: UUID,
    incoming: PushRequest,
    version: String? = "2",
    capabilities: Set<String> = emptySet(),
  ): PushResult {
    // Profile cannot be deleted, even by callers that have not negotiated the capability.
    if (incoming.changes.any { it.kind == "profile" && it.deleted })
      bad("Очистите поля профиля вместо удаления")
    if ("profile" !in capabilities && incoming.changes.any { it.kind == "profile" })
      throw ApiException(426, "capability_required", "Требуется возможность profile")
    incoming.changes
      .filter { it.kind == "profile" }
      .forEach {
        if (
          it.id != ProfileIdentity.syncId(user.toString()) ||
            it.payload?.get("syncId")?.asString() != it.id.toString()
        )
          bad("Профиль не соответствует владельцу")
        validator.validate("profile", it.payload ?: bad("Отсутствует профиль"))
      }
    if (
      "calendar-plans" !in capabilities &&
        incoming.changes.any { it.kind in RecordValidator.calendarKinds }
    )
      throw ApiException(426, "capability_required", "Требуется возможность calendar-plans")
    if ("exercise-hint" !in capabilities && incoming.changes.any { it.kind == "exercise_hint" })
      throw ApiException(426, "capability_required", "Требуется возможность exercise-hint")
    if (
      strengthPlannerCapability !in capabilities &&
        incoming.changes.any { it.kind in setOf(strengthPlannerProfileKind, workoutEffortKind) }
    )
      throw ApiException(
        426,
        "capability_required",
        "Требуется возможность $strengthPlannerCapability",
      )
    if (
      plannerDefaultAccentsCapability !in capabilities &&
        incoming.changes.any { it.kind == plannerExerciseAccentsKind }
    )
      throw ApiException(
        426,
        "capability_required",
        "Требуется возможность $plannerDefaultAccentsCapability",
      )
    if (incoming.changes.any { it.kind == plannerExerciseAccentsKind && it.deleted })
      bad("Акценты планировщика нельзя удалить; сохраните пустой список")
    if (
      agenticPlannerCapability !in capabilities &&
        incoming.changes.any { it.kind == plannerExercisePreferencesKind }
    )
      throw ApiException(
        426,
        "capability_required",
        "Требуется возможность $agenticPlannerCapability",
      )
    val request =
      incoming.copy(
        changes =
          incoming.changes.map { it.copy(payload = it.payload?.takeUnless { node -> node.isNull }) }
      )
    if (request.changes.isEmpty() || request.changes.size > 1000)
      bad("Отправляйте от 1 до 1000 изменений")
    if (request.changes.map { RecordKey(it.kind, it.id) }.distinct().size != request.changes.size)
      bad("Объект повторяется в пакете")
    val serialized = json.writeValueAsString(request)
    if (serialized.toByteArray().size > 10 * 1024 * 1024)
      throw ApiException(413, "payload_too_large", "Пакет слишком большой")
    val hash = crypto.hash(serialized)
    return tx.execute {
      val catalogHead = catalog.readLock()
      requireVersion(catalogHead, version)
      val previous = head(user, true)
      requireAccountVersion(user, version)
      val operation = operations.findById(OperationId(user, request.operationId)).orElse(null)
      if (operation != null) {
        if (operation.requestHash != hash)
          throw ApiException(
            409,
            "operation_reused",
            "Идентификатор операции уже использован с другими данными",
          )
        return@execute PushResult(operation.revision)
      }
      val existing = records(user).associateBy { RecordKey(it.kind, it.id) }.toMutableMap()
      val commonRows = standard.findAllByOrderByKindAscIdAsc()
      val common =
        commonRows.associate {
          RecordKey(it.kind, it.id) to
            Record(it.kind, it.id, it.revision, false, json.readTree(it.payload))
        }
      val before = existing.toMap() + common
      val dependent =
        request.changes.any { c ->
          c.kind in setOf("exercise", "gym", "routine") ||
            c.payload?.let {
              validator.referencesOf(Record(c.kind, c.id, 0, c.deleted, it)).isNotEmpty()
            } == true
        }
      if (dependent && catalogHead.active && request.catalogRevision != catalogHead.revision)
        throw ApiException(
          409,
          "catalog_stale",
          "Обновите каталог перед сохранением зависимых данных",
        )
      val changes = request.changes.toMutableList()
      request.changes
        .filter { it.kind == "workout" && it.deleted }
        .forEach { parent ->
          val childId = workoutEffortId(user, parent.id)
          val child = existing[RecordKey(workoutEffortKind, childId)]
          if (
            child?.deleted == false &&
              changes.none { it.kind == workoutEffortKind && it.id == childId }
          )
            changes +=
              tech.valerochkagym.controller.model.Change(
                workoutEffortKind,
                childId,
                child.revision,
                true,
                null,
              )
        }
      val revision = previous + 1
      changes.forEach { change ->
        val key = RecordKey(change.kind, change.id)
        if (change.kind !in RecordValidator.kinds || change.baseRevision < 0)
          bad("Некорректный тип или версия объекта")
        if (change.kind != "exercise_hint" && common.keys.any { it.id == change.id })
          throw ApiException(
            403,
            "standard_read_only",
            "Создайте личную копию стандартного объекта",
          )
        if (change.kind == strengthPlannerProfileKind) {
          if (change.deleted) bad("Профиль силы нельзя удалить")
          if (
            change.id != strengthPlannerProfileId(user) ||
              change.payload?.get("syncId")?.asString() != change.id.toString()
          )
            bad("Профиль силы не соответствует владельцу")
        }
        if (change.kind == plannerExercisePreferencesKind) {
          if (change.id != plannerExercisePreferencesId(user))
            bad("Настройки планировщика не соответствуют владельцу")
        }
        if (change.kind == plannerExerciseAccentsKind) {
          if (change.deleted || change.id != plannerExerciseAccentsId(user))
            bad("Акценты планировщика не соответствуют владельцу")
        }
        if (change.kind == workoutEffortKind) {
          if (
            change.deleted &&
              changes.none {
                it.kind == "workout" && it.deleted && workoutEffortId(user, it.id) == change.id
              }
          )
            bad("Удаление оценки требует удаления тренировки в том же запросе")
          if (!change.deleted && change.payload?.get("syncId")?.asString() != change.id.toString())
            bad("Оценка не соответствует владельцу")
          if (!change.deleted) {
            val workoutId =
              runCatching { UUID.fromString(change.payload!!["workoutId"].asString()) }.getOrNull()
                ?: bad("Оценка не соответствует тренировке")
            if (change.id != workoutEffortId(user, workoutId))
              bad("Оценка не соответствует тренировке")
          }
        }
        val old = existing[key]
        if (
          change.kind == "workout" &&
            "annotated-workout-writes" !in capabilities &&
            (hasSetNotes(old?.payload) || hasSetNotes(change.payload))
        )
          throw ApiException(
            409,
            "annotated_workout_requires_capability",
            "Требуется возможность annotated-workout-writes",
          )
        if (
          change.kind == "workout" &&
            workoutRirCapability !in capabilities &&
            (hasWorkoutRir(old?.payload) || hasWorkoutRir(change.payload))
        )
          throw ApiException(
            409,
            "workout_rir_requires_capability",
            "Требуется возможность $workoutRirCapability",
          )
        if (change.kind == "calendar_rule" && !change.deleted && old?.deleted == true)
          bad("Удалённое правило требует нового UUID")
        if (change.kind == "workout" && !change.deleted) {
          val hasCoach =
            change.payload?.get("exercises")?.any { section ->
              section.get("sets")?.any { it.has("syncId") } == true
            } == true
          val hadCoach =
            old?.payload?.get("exercises")?.any { section ->
              section.get("sets")?.any { it.has("syncId") } == true
            } == true
          if (hasCoach && version != "3")
            throw ApiException(426, "client_update_required", "Обновите приложение для Live Coach")
          if (
            hadCoach &&
              change.payload?.get("exercises")?.any { section ->
                section.get("sets")?.any { !it.has("syncId") } == true
              } == true
          )
            throw ApiException(
              409,
              "revision_conflict",
              "Старая очередь не содержит данные Live Coach. Выберите актуальную версию",
            )
          if (hasCoach) heads.writeLock(user).minSyncVersion = 3
        }
        if ((old?.revision ?: 0) != change.baseRevision)
          throw ApiException(
            409,
            "revision_conflict",
            "Объект изменён на другом устройстве. Получите актуальные данные",
          )
        if (change.deleted && change.payload != null || !change.deleted && change.payload == null)
          bad("Некорректное содержимое изменения")
        if (!change.deleted) validator.validate(change.kind, change.payload!!)
        existing[key] = Record(change.kind, change.id, revision, change.deleted, change.payload)
      }
      if (
        existing.size > 20_000 ||
          existing.values.sumOf { it.payload?.toString()?.toByteArray()?.size?.toLong() ?: 0L } >
            16L * 1024 * 1024
      )
        throw ApiException(409, "account_limit", "Превышено количество объектов аккаунта")
      // Hints may outlive their exercise. Only a new or changed live hint selects a reference.
      request.changes
        .filter { it.kind == "exercise_hint" && !it.deleted }
        .forEach { change ->
          val key = RecordKey(change.kind, change.id)
          if (before[key]?.deleted != false || before[key]?.payload != change.payload) {
            val exerciseKey = RecordKey("exercise", change.id)
            if (
              (existing + common)[exerciseKey]?.deleted != false ||
                commonRows.any { it.kind == "exercise" && it.id == change.id && it.archived }
            )
              bad("Подсказка требует доступное упражнение")
          }
        }
      validator.references(
        existing + common,
        changes.map { RecordKey(it.kind, it.id) }.toSet(),
        before,
      )
      validator.archivedReferences(
        existing + common,
        before,
        commonRows.filter { it.archived }.map { RecordKey(it.kind, it.id) }.toSet(),
      )
      changes.forEach { change ->
        recordRows.save(
          RecordEntity(
            user,
            change.kind,
            change.id,
            revision,
            change.deleted,
            change.payload?.let(json::writeValueAsString),
          )
        )
      }
      changes
        .filter { it.kind == "workout" && it.deleted }
        .forEach {
          jdbc.update(
            "UPDATE coach_journal SET payload=NULL,deleted=TRUE WHERE user_id=? AND workout_id=?",
            user,
            it.id,
          )
        }
      heads.writeLock(user).revision = revision
      operations.save(OperationEntity(user, request.operationId, hash, revision))
      PushResult(revision)
    }!!
  }

  fun changes(
    user: UUID,
    after: Long,
    cursor: String?,
    limit: Int,
    version: String? = "2",
    capabilities: Set<String> = emptySet(),
  ): ChangesPage {
    if (after < 0 || limit !in 1..1000) bad("Некорректная пагинация")
    return tx.execute {
      requireVersion(catalog.readLock(), version)
      val high = head(user, false)
      requireAccountVersion(user, version)
      val parts = cursor?.split(":")
      if (parts != null && parts.size != 3) bad("Некорректный курсор")
      val rev = parts?.get(0)?.toLongOrNull() ?: after
      val kind = parts?.get(1) ?: ""
      val id = parts?.get(2)?.let(UUID::fromString) ?: UUID(0, 0)
      if (rev < after || rev > high || (parts != null && kind !in RecordValidator.kinds))
        bad("Некорректный курсор")
      val rows =
        records(user)
          .filter { visible(it.kind, capabilities) }
          .filter {
            it.revision > after &&
              (it.revision > rev ||
                it.revision == rev &&
                  (it.kind > kind || it.kind == kind && it.id.toString() > id.toString()))
          }
          .map { project(it, capabilities) }
          .sortedWith(
            compareBy<Record> { it.revision }.thenBy { it.kind }.thenBy { it.id.toString() }
          )
          .take(limit + 1)
      val page = rows.take(limit)
      ChangesPage(
        high,
        page,
        if (rows.size > limit) page.last().let { "${it.revision}:${it.kind}:${it.id}" } else null,
      )
    }!!
  }

  private fun visible(kind: String, capabilities: Set<String>) =
    ("calendar-plans" in capabilities || kind !in RecordValidator.calendarKinds) &&
      ("exercise-hint" in capabilities || kind != "exercise_hint") &&
      ("profile" in capabilities || kind != "profile") &&
      (strengthPlannerCapability in capabilities ||
        kind !in setOf(strengthPlannerProfileKind, workoutEffortKind)) &&
      (agenticPlannerCapability in capabilities || kind != plannerExercisePreferencesKind) &&
      (plannerDefaultAccentsCapability in capabilities || kind != plannerExerciseAccentsKind)

  private fun hasSetNotes(payload: tools.jackson.databind.JsonNode?): Boolean =
    payload?.get("exercises")?.any { section ->
      section.get("sets")?.any {
        it.get("note")?.let { note -> note.isString && note.asString().isNotBlank() } == true
      } == true
    } == true

  private val workoutRirFields = setOf("targetRir", "actualRir", "actualRirAtLeastFour")

  private fun hasWorkoutRir(payload: tools.jackson.databind.JsonNode?): Boolean =
    payload?.get("exercises")?.any { section ->
      section.get("sets")?.any { set -> workoutRirFields.any { field -> set.has(field) } } == true
    } == true

  private fun project(record: Record, capabilities: Set<String>): Record {
    if (record.kind != "workout" || record.payload == null) return record
    if ("annotated-workout-writes" in capabilities && workoutRirCapability in capabilities)
      return record
    val payload = record.payload.deepCopy()
    payload.get("exercises")?.forEach { section ->
      section.get("sets")?.forEach {
        it as tools.jackson.databind.node.ObjectNode
        if ("annotated-workout-writes" !in capabilities) it.remove("note")
        if (workoutRirCapability !in capabilities) workoutRirFields.forEach(it::remove)
      }
    }
    return record.copy(payload = payload)
  }

  private fun requireAccountVersion(user: UUID, version: String?) {
    if (heads.readLock(user).minSyncVersion >= 3 && version != "3")
      throw ApiException(426, "client_update_required", "Обновите приложение для Live Coach")
  }

  private fun requireVersion(head: CatalogStateEntity, version: String?) {
    if (head.active && version !in setOf("2", "3"))
      throw ApiException(426, "client_update_required", "Обновите приложение для общего каталога")
  }
}
