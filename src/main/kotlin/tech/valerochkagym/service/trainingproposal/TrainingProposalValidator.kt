package tech.valerochkagym.service.trainingproposal

import java.time.Instant
import java.time.ZoneId
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class TrainingProposalValidator(private val json: ObjectMapper) {
  private val uuid =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

  fun approval(node: JsonNode): ApprovalRequest {
    shape(node, setOf("operationId", "version", "draft"))
    required(node, "operationId")
    required(node, "version")
    required(node, "draft")
    canonicalUuid(node["operationId"])
    positiveVersion(node["version"])
    draft(node["draft"])
    return try {
      json.treeToValue(node, ApprovalRequest::class.java).let { normalize(it) }
    } catch (_: Exception) {
      bad("Некорректный запрос подтверждения")
    }
  }

  fun reject(node: JsonNode): RejectRequest {
    shape(node, setOf("version", "reason"))
    required(node, "version")
    required(node, "reason")
    positiveVersion(node["version"])
    if (
      !node["reason"].isNull &&
        (!node["reason"].isString ||
          node["reason"].asString().codePointCount(0, node["reason"].asString().length) > 500)
    )
      bad("Некорректная причина")
    return try {
      json.treeToValue(node, RejectRequest::class.java)
    } catch (_: Exception) {
      bad("Некорректный запрос отклонения")
    }
  }

  fun draft(node: JsonNode): ApprovalDraft {
    shape(node, setOf("name", "gymIds", "exercises", "startsAtMillis", "timeZoneId"))
    setOf("name", "gymIds", "exercises", "startsAtMillis", "timeZoneId").forEach {
      required(node, it)
    }
    val name = node["name"]
    if (
      !name.isString ||
        name.asString().length !in 1..200 ||
        name.asString() != name.asString().trim()
    )
      bad("Некорректное имя программы")
    val gyms = array(node["gymIds"], 0, 1000, "gymIds")
    val gymIds = gyms.map(::canonicalUuid)
    if (gymIds.distinct().size != gymIds.size || gymIds != gymIds.sorted()) bad("Некорректные залы")
    val exercises = array(node["exercises"], 1, 30, "exercises")
    val exerciseIds = exercises.map { plannedExercise(it).exerciseId }
    if (exerciseIds.distinct().size != exerciseIds.size) bad("Повтор упражнения")
    val starts = node["startsAtMillis"]
    if (!starts.isIntegralNumber || !starts.canConvertToLong() || starts.asLong() < 0)
      bad("Некорректное время")
    val zone = node["timeZoneId"]
    if (!zone.isString || zone.asString().length !in 1..255) bad("Некорректная временная зона")
    val parsedZone =
      try {
        ZoneId.of(zone.asString())
      } catch (_: Exception) {
        bad("Некорректная временная зона")
      }
    if (parsedZone.id != zone.asString()) bad("Некорректная временная зона")
    val year = Instant.ofEpochMilli(starts.asLong()).atZone(parsedZone).year
    if (year !in 1970..2100) bad("Дата вне диапазона")
    return try {
      json.treeToValue(node, ApprovalDraft::class.java).let(::normalize)
    } catch (_: Exception) {
      bad("Некорректный черновик")
    }
  }

  fun validateDraft(draft: ApprovalDraft, now: Instant) {
    if (draft.startsAtMillis <= now.toEpochMilli()) bad("Начало должно быть в будущем")
    if (draft.name.length !in 1..200 || draft.name != draft.name.trim())
      bad("Некорректное имя программы")
    if (
      draft.gymIds.size > 1000 ||
        draft.gymIds != draft.gymIds.sorted() ||
        draft.gymIds.distinct().size != draft.gymIds.size
    )
      bad("Некорректные залы")
    draft.gymIds.forEach { canonicalUuid(it) }
    if (
      draft.exercises.size !in 1..30 ||
        draft.exercises.map { it.exerciseId }.distinct().size != draft.exercises.size
    )
      bad("Некорректные упражнения")
    draft.exercises.forEach { plannedExercise(it) }
    val zone =
      try {
        ZoneId.of(draft.timeZoneId)
      } catch (_: Exception) {
        bad("Некорректная временная зона")
      }
    if (
      zone.id != draft.timeZoneId ||
        Instant.ofEpochMilli(draft.startsAtMillis).atZone(zone).year !in 1970..2100
    )
      bad("Некорректное время")
  }

  fun normalize(request: ApprovalRequest) = request.copy(draft = normalize(request.draft))

  fun normalize(draft: ApprovalDraft) =
    draft.copy(
      exercises = draft.exercises.map { it.copy(plannedSets = it.plannedSets.map(::normalize)) }
    )

  private fun normalize(set: PlannedSet) =
    set.copy(
      weightKg = set.weightKg?.takeUnless { it == 0.0 } ?: set.weightKg?.let { 0.0 },
      speedKmh = set.speedKmh?.takeUnless { it == 0.0 } ?: set.speedKmh?.let { 0.0 },
      inclinePct = set.inclinePct?.takeUnless { it == 0.0 } ?: set.inclinePct?.let { 0.0 },
    )

  fun canonicalUuid(raw: String): String {
    if (!uuid.matches(raw)) bad("Некорректный UUID")
    return raw
  }

  private fun canonicalUuid(node: JsonNode): String {
    if (!node.isString) bad("Некорректный UUID")
    return canonicalUuid(node.asString())
  }

  private fun plannedExercise(node: JsonNode): PlannedExercise {
    shape(node, setOf("exerciseId", "restSeconds", "plannedSets"))
    setOf("exerciseId", "restSeconds", "plannedSets").forEach { required(node, it) }
    canonicalUuid(node["exerciseId"])
    val rest = node["restSeconds"]
    if (
      !rest.isNull &&
        (!rest.isIntegralNumber || !rest.canConvertToInt() || rest.asInt() !in 0..86400)
    )
      bad("Некорректный отдых")
    val sets = array(node["plannedSets"], 1, 20, "plannedSets")
    sets.forEach(::plannedSet)
    return try {
      json.treeToValue(node, PlannedExercise::class.java).let {
        it.copy(plannedSets = it.plannedSets.map(::normalize))
      }
    } catch (_: Exception) {
      bad("Некорректное упражнение")
    }
  }

  private fun plannedExercise(value: PlannedExercise) {
    canonicalUuid(value.exerciseId)
    if (
      (value.restSeconds != null && value.restSeconds !in 0..86400) ||
        value.plannedSets.size !in 1..20
    )
      bad("Некорректное упражнение")
    value.plannedSets.forEach(::plannedSet)
  }

  fun plannedSet(set: PlannedSet, type: String? = null) {
    listOf(set.weightKg, set.speedKmh, set.inclinePct).forEach {
      if (it != null && !it.isFinite()) bad("Некорректное число")
    }
    fun bounded(value: Double?, min: Double = 0.0) = value == null || value in min..1_000_000.0
    if (
      !bounded(set.weightKg) ||
        !bounded(set.speedKmh) ||
        !bounded(set.inclinePct, -100.0) ||
        (set.reps != null && set.reps !in 1..1_000_000) ||
        (set.durationSec != null && set.durationSec !in 1..1_000_000)
    )
      bad("Некорректный подход")
    when (type) {
      "STRENGTH" ->
        if (
          set.reps == null ||
            set.durationSec != null ||
            set.speedKmh != null ||
            set.inclinePct != null
        )
          bad("Некорректный подход")
      "TIMED" ->
        if (
          set.weightKg != null ||
            set.reps != null ||
            set.durationSec == null ||
            set.speedKmh != null ||
            set.inclinePct != null
        )
          bad("Некорректный подход")
      "CARDIO" ->
        if (set.weightKg != null || set.reps != null || set.durationSec == null)
          bad("Некорректный подход")
    }
  }

  private fun plannedSet(node: JsonNode) {
    shape(node, setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct"))
    setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct").forEach {
      required(node, it)
    }
    fun number(key: String, min: Double) {
      val value = node[key]
      if (
        !value.isNull &&
          (!value.isNumber || !value.asDouble().isFinite() || value.asDouble() !in min..1_000_000.0)
      )
        bad("Некорректный подход")
    }
    number("weightKg", 0.0)
    number("speedKmh", 0.0)
    number("inclinePct", -100.0)
    listOf("reps", "durationSec").forEach { key ->
      val value = node[key]
      if (
        !value.isNull &&
          (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() !in 1..1_000_000)
      )
        bad("Некорректный подход")
    }
  }

  private fun shape(node: JsonNode, fields: Set<String>) {
    if (!node.isObject || node.properties().any { it.key !in fields }) bad("Неизвестные поля")
  }

  private fun required(node: JsonNode, key: String) {
    if (!node.has(key)) bad("Отсутствует $key")
  }

  private fun array(node: JsonNode, min: Int, max: Int, name: String): List<JsonNode> {
    if (!node.isArray || node.size() !in min..max) bad("Некорректный список $name")
    return node.toList()
  }

  private fun positiveVersion(node: JsonNode) {
    if (!node.isIntegralNumber || !node.canConvertToInt() || node.asInt() !in 1..Int.MAX_VALUE)
      bad("Некорректная версия")
  }
}
