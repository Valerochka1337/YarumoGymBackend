package tech.valerochkagym.service.data

import java.nio.charset.StandardCharsets
import java.time.*
import java.util.UUID
import org.springframework.stereotype.Component
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.repository.catalog.EquipmentRepository
import tech.valerochkagym.service.model.RecordKey
import tools.jackson.databind.JsonNode

/** The wire format contains domain aggregates with UUID references, never local SQLite IDs. */
@Component
class RecordValidator(
  private val equipmentRows: tech.valerochkagym.repository.catalog.EquipmentRepository,
  private val json: tools.jackson.databind.ObjectMapper,
  private val clock: Clock,
) {
  private fun coverage() =
    equipmentRows.findAll().associate {
      it.id to json.readTree(it.payload)["provides"].toList().map { n -> n.asString() }.toSet()
    }

  companion object {
    val calendarKinds = setOf("calendar_plan", "calendar_rule", "calendar_exception")
    val kinds =
      setOf(
        "exercise",
        "gym",
        "routine",
        "workout",
        "measurement",
        "schedule",
        "exercise_hint",
        "profile",
        "strength_planner_profile",
        "workout_effort",
      ) + calendarKinds
    val measurementFields =
      setOf(
        "measuredAt",
        "weightKg",
        "skeletalMuscleMassKg",
        "bodyFatPercentage",
        "bodyFatMassKg",
        "visceralFatLevel",
        "waistHipRatio",
        "inBodyScore",
        "totalBodyWaterLiters",
        "proteinKg",
        "mineralsKg",
        "bodyMassIndex",
        "fatFreeMassKg",
        "basalMetabolicRateKcal",
        "recommendedCalorieIntakeKcal",
        "leftArmLeanMassKg",
        "leftArmLeanPercentage",
        "rightArmLeanMassKg",
        "rightArmLeanPercentage",
        "trunkLeanMassKg",
        "trunkLeanPercentage",
        "leftLegLeanMassKg",
        "leftLegLeanPercentage",
        "rightLegLeanMassKg",
        "rightLegLeanPercentage",
        "leftArmFatMassKg",
        "leftArmFatPercentage",
        "rightArmFatMassKg",
        "rightArmFatPercentage",
        "trunkFatMassKg",
        "trunkFatPercentage",
        "leftLegFatMassKg",
        "leftLegFatPercentage",
        "rightLegFatMassKg",
        "rightLegFatPercentage",
        "waistCm",
        "chestCm",
        "hipsCm",
        "rightRelaxedArmCm",
        "rightThighCm",
      )
    val setFields = setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct")
    val muscles =
      setOf(
        "UPPER_CHEST",
        "LOWER_CHEST",
        "FRONT_DELTS",
        "SIDE_DELTS",
        "REAR_DELTS",
        "ROTATOR_CUFF",
        "SERRATUS_ANTERIOR",
        "BICEPS",
        "TRICEPS",
        "FOREARMS",
        "ABS",
        "OBLIQUES",
        "HIP_FLEXORS",
        "ADDUCTORS",
        "QUADS",
        "TIBIALIS_ANTERIOR",
        "CALVES",
        "HAMSTRINGS",
        "GLUTES",
        "HIP_ABDUCTORS",
        "LOWER_BACK",
        "LATS",
        "UPPER_BACK",
        "TRAPS",
        "NECK",
      )
  }

  private fun shape(n: JsonNode, fields: Set<String>) {
    if (!n.isObject || n.properties().any { it.key !in fields }) bad("Неизвестные поля объекта")
  }

  private fun text(n: JsonNode, key: String, max: Int = 200, blank: Boolean = false): String {
    val value = n.get(key) ?: bad("Отсутствует $key")
    if (!value.isString || value.asString().length > max || (!blank && value.asString().isBlank()))
      bad("Некорректное поле $key")
    return value.asString()
  }

  private fun annotation(n: JsonNode, key: String, blank: Boolean) {
    val value = n.get(key) ?: bad("Отсутствует $key")
    if (!value.isString) bad("Некорректное поле $key")
    val text = value.asString()
    if (
      text != text.trim() ||
        text.codePointCount(0, text.length) > 2000 ||
        (!blank && text.isBlank())
    )
      bad("Некорректное поле $key")
  }

  private fun enum(n: JsonNode, key: String, values: Set<String>) {
    if (text(n, key) !in values) bad("Неизвестное значение $key")
  }

  private fun bool(n: JsonNode, key: String) {
    if (n.get(key)?.isBoolean != true) bad("Некорректное поле $key")
  }

  private fun number(
    n: JsonNode,
    key: String,
    required: Boolean = false,
    integer: Boolean = false,
    min: Double = 0.0,
    max: Double = 1e15,
  ) {
    val value = n.get(key)
    if (value == null || value.isNull) {
      if (required) bad("Отсутствует $key")
      return
    }
    if (
      !value.isNumber ||
        !value.asDouble().isFinite() ||
        value.asDouble() !in min..max ||
        integer && !value.isIntegralNumber
    )
      bad("Некорректное число $key")
  }

  private fun uuid(value: JsonNode?): UUID {
    if (value?.isString != true) bad("Ожидается UUID")
    val raw = value.asString()
    val id =
      try {
        UUID.fromString(raw)
      } catch (e: IllegalArgumentException) {
        bad("Некорректный UUID")
      }
    if (id.toString() != raw.lowercase()) bad("Некорректный UUID")
    return id
  }

  private fun array(n: JsonNode, key: String, max: Int = 1000): List<JsonNode> {
    val v = n.get(key) ?: bad("Отсутствует $key")
    if (!v.isArray || v.size() > max) bad("Некорректный список $key")
    return v.toList()
  }

  private fun ids(n: JsonNode, key: String) {
    val ids = array(n, key).map(::uuid)
    if (ids.distinct().size != ids.size) bad("Дубли в $key")
  }

  private fun equipment(n: JsonNode) {
    val entries = array(n, "equipmentIds", 200)
    if (
      entries.any { !it.isString || it.asString() !in coverage() } ||
        entries.distinct().size != entries.size
    )
      bad("Некорректное оборудование")
  }

  private val coachSetFields =
    setOf(
      "syncId",
      "originalWeightKg",
      "originalReps",
      "originalDurationSec",
      "originalSpeedKmh",
      "originalInclinePct",
      "targetWeightKg",
      "targetReps",
      "targetDurationSec",
      "targetSpeedKmh",
      "targetInclinePct",
      "actualWeightKg",
      "actualReps",
      "actualDurationSec",
      "actualSpeedKmh",
      "actualInclinePct",
      "setType",
      "reportedFeelingsJson",
      "restSnapshotJson",
      "coachMutationRevision",
      "targetRir",
      "actualRir",
    )

  private fun set(n: JsonNode, completed: Boolean) {
    shape(
      n,
      if (completed)
        setFields + setOf("setIndex", "isCompleted", "completedAt", "note") + coachSetFields
      else setFields,
    )
    setFields.forEach {
      number(
        n,
        it,
        integer = it in setOf("reps", "durationSec"),
        min = if (it == "inclinePct") -100.0 else 0.0,
        max = 1e6,
      )
    }
    if (completed) {
      if (n.has("note")) annotation(n, "note", true)
      n["syncId"]?.let(::uuid)
      coachSetFields
        .filter { it.startsWith("original") || it.startsWith("target") || it.startsWith("actual") }
        .forEach {
          number(
            n,
            it,
            integer = it.endsWith("Reps") || it.endsWith("DurationSec") || it.endsWith("Rir"),
            min = if (it.endsWith("InclinePct")) -100.0 else 0.0,
            max = if (it.endsWith("Rir")) 10.0 else 1e6,
          )
        }
      n["setType"]?.let {
        enum(
          n,
          "setType",
          setOf(
            "WORK",
            "WARMUP",
            "TIMED",
            "CARDIO",
            "COUNTERWEIGHT",
            "BAND",
            "INTERRUPTED",
            "UNKNOWN",
          ),
        )
      }
      number(n, "coachMutationRevision", integer = true)
      n["reportedFeelingsJson"]?.let {
        val value = json.readTree(text(n, "reportedFeelingsJson", 4096))
        if (
          !value.isArray ||
            value.size() > 10 ||
            value.any { v ->
              !v.isString ||
                v.asString() !in setOf("PAIN", "FATIGUE", "TECHNIQUE_BREAKDOWN", "INTERRUPTED")
            }
        )
          bad("Некорректные ощущения")
      }
      n["restSnapshotJson"]
        ?.takeUnless { it.isNull }
        ?.let {
          val value = json.readTree(text(n, "restSnapshotJson", 4096))
          shape(
            value,
            setOf("plannedSeconds", "startedAtMillis", "completedAtMillis", "extraSeconds"),
          )
          listOf("plannedSeconds", "startedAtMillis", "completedAtMillis", "extraSeconds")
            .forEach { field -> number(value, field, integer = true) }
        }
      number(n, "setIndex", true, true, max = 1000.0)
      bool(n, "isCompleted")
      number(n, "completedAt", integer = true)
    }
  }

  private fun calendarUuid(node: JsonNode?): UUID =
    uuid(node).also { if (node!!.asString() != it.toString()) bad("UUID должен быть каноническим") }

  private fun zone(n: JsonNode): ZoneId {
    val value = text(n, "timeZoneId")
    if (value !in ZoneId.getAvailableZoneIds()) bad("Некорректная временная зона")
    return ZoneId.of(value)
  }

  private fun date(value: String): LocalDate {
    val parsed =
      try {
        LocalDate.parse(value)
      } catch (e: DateTimeException) {
        bad("Некорректная дата")
      }
    if (parsed.toString() != value || parsed.year !in 1970..2100) bad("Дата вне диапазона")
    return parsed
  }

  private fun time(value: String): LocalTime {
    if (!value.matches(Regex("[0-9]{2}:[0-9]{2}"))) bad("Некорректное время")
    return try {
      LocalTime.parse(value)
    } catch (e: DateTimeException) {
      bad("Некорректное время")
    }
  }

  private fun instant(n: JsonNode, key: String, zone: ZoneId) {
    number(n, key, true, true, min = -1e15, max = 1e15)
    val year = Instant.ofEpochMilli(n[key].asLong()).atZone(zone).year
    if (year !in 1970..2100) bad("Дата вне диапазона")
  }

  private fun profile(n: JsonNode) {
    val fields =
      setOf(
        "schemaVersion",
        "syncId",
        "updatedAt",
        "trainingGoal",
        "sex",
        "birthDate",
        "experienceLevel",
        "plannedSessionsPerWeek",
        "preferredSessionDurationMinutes",
        "manualConstraints",
        "equipmentIds",
      )
    shape(n, fields)
    if (fields.any { !n.has(it) }) bad("Профиль требует явные nullable поля")
    number(n, "schemaVersion", true, true, min = 1.0, max = 1.0)
    calendarUuid(n["syncId"])
    val updated = n["updatedAt"]
    if (!updated.isIntegralNumber || !updated.canConvertToLong() || updated.asLong() < 0)
      bad("Некорректное время профиля")
    fun optionalEnum(key: String, values: Set<String>) {
      if (!n[key].isNull) enum(n, key, values)
    }
    optionalEnum(
      "trainingGoal",
      setOf("STRENGTH", "MUSCLE_GAIN", "FAT_LOSS", "GENERAL_FITNESS", "ENDURANCE", "OTHER"),
    )
    optionalEnum("sex", setOf("FEMALE", "MALE", "PREFER_NOT_TO_SAY"))
    optionalEnum("experienceLevel", setOf("BEGINNER", "INTERMEDIATE", "ADVANCED"))
    if (!n["birthDate"].isNull) {
      val raw = text(n, "birthDate", 10)
      val day =
        try {
          LocalDate.parse(raw)
        } catch (e: DateTimeException) {
          bad("Некорректная дата рождения")
        }
      if (
        day.toString() != raw ||
          day < LocalDate.of(1900, 1, 1) ||
          day > LocalDate.now(clock.withZone(ZoneOffset.UTC))
      )
        bad("Дата рождения вне диапазона")
    }
    number(n, "plannedSessionsPerWeek", integer = true, min = 1.0, max = 7.0)
    number(n, "preferredSessionDurationMinutes", integer = true, min = 10.0, max = 240.0)
    if (!n["manualConstraints"].isNull) annotation(n, "manualConstraints", false)
    equipment(n)
    val equipment = n["equipmentIds"].toList().map { it.asString() }
    if (equipment != equipment.sorted()) bad("Оборудование должно быть отсортировано")
  }

  private fun strengthPlannerProfile(n: JsonNode) {
    val fields = setOf("schemaVersion", "syncId", "updatedAt", "keyExercises")
    shape(n, fields)
    if (fields.any { !n.has(it) }) bad("Профиль силы требует все поля")
    number(n, "schemaVersion", true, true, min = 1.0, max = 1.0)
    calendarUuid(n["syncId"])
    number(n, "updatedAt", true, true, max = Long.MAX_VALUE.toDouble())
    val keys = array(n, "keyExercises", 5)
    val priorities = mutableListOf<String>()
    val ids = mutableListOf<String>()
    keys.forEach {
      shape(it, setOf("exerciseId", "priority"))
      ids += calendarUuid(it["exerciseId"]).toString()
      val priority = text(it, "priority")
      if (priority !in setOf("HIGH", "NORMAL")) bad("Некорректный приоритет упражнения")
      priorities += priority
    }
    if (ids.distinct().size != ids.size) bad("Повтор ключевого упражнения")
    val canonical =
      keys.sortedWith(
        compareBy<JsonNode> { it["priority"].asString() != "HIGH" }
          .thenBy { it["exerciseId"].asString() }
      )
    if (keys != canonical) bad("Ключевые упражнения должны быть канонически упорядочены")
  }

  private fun workoutEffort(n: JsonNode) {
    val fields = setOf("schemaVersion", "syncId", "workoutId", "updatedAt", "effort")
    shape(n, fields)
    if (fields.any { !n.has(it) }) bad("Оценка тренировки требует все поля")
    number(n, "schemaVersion", true, true, min = 1.0, max = 1.0)
    calendarUuid(n["syncId"])
    calendarUuid(n["workoutId"])
    number(n, "updatedAt", true, true, max = Long.MAX_VALUE.toDouble())
    if (!n["effort"].isNull) enum(n, "effort", setOf("EASY", "MODERATE", "HARD"))
  }

  fun validate(kind: String, n: JsonNode) {
    when (kind) {
      "profile" -> profile(n)
      "strength_planner_profile" -> strengthPlannerProfile(n)
      "workout_effort" -> workoutEffort(n)
      "exercise_hint" -> {
        shape(n, setOf("text", "updatedAt"))
        annotation(n, "text", false)
        number(n, "updatedAt", true, true, max = Long.MAX_VALUE.toDouble())
      }
      "exercise" -> {
        shape(
          n,
          setOf(
            "name",
            "muscleGroup",
            "type",
            "isCustom",
            "updatedAt",
            "needsMuscleMapReview",
            "equipmentRequirementState",
            "muscles",
            "equipmentIds",
          ),
        )
        text(n, "name")
        enum(
          n,
          "muscleGroup",
          setOf("CHEST", "BACK", "LEGS", "SHOULDERS", "ARMS", "CORE", "CARDIO", "FULL_BODY"),
        )
        enum(n, "type", setOf("STRENGTH", "TIMED", "CARDIO"))
        bool(n, "isCustom")
        bool(n, "needsMuscleMapReview")
        number(n, "updatedAt", true, true)
        enum(n, "equipmentRequirementState", setOf("UNKNOWN", "KNOWN"))
        equipment(n)
        val maps = array(n, "muscles", muscles.size)
        maps.forEach {
          shape(it, setOf("muscle", "contribution"))
          enum(it, "muscle", muscles)
          number(it, "contribution", true, true)
          if (it["contribution"].asInt() !in setOf(0, 50, 100)) bad("Некорректная роль мышцы")
        }
        if (maps.map { it["muscle"].asString() }.distinct().size != maps.size) bad("Повтор мышцы")
      }
      "gym" -> {
        shape(n, setOf("name", "updatedAt", "inventoryConfigured", "exerciseIds", "equipmentIds"))
        text(n, "name")
        number(n, "updatedAt", true, true)
        bool(n, "inventoryConfigured")
        ids(n, "exerciseIds")
        equipment(n)
      }
      "routine" -> {
        shape(n, setOf("name", "note", "updatedAt", "exercises", "gymIds"))
        text(n, "name")
        text(n, "note", 10000, true)
        number(n, "updatedAt", true, true)
        ids(n, "gymIds")
        val rows = array(n, "exercises", 200)
        rows.forEach {
          shape(it, setOf("exerciseId", "position", "restSeconds", "plannedSets"))
          uuid(it["exerciseId"])
          number(it, "position", true, true, max = 1000.0)
          number(it, "restSeconds", integer = true, max = 86400.0)
          array(it, "plannedSets", 1000).forEach { s -> set(s, false) }
        }
        if (rows.map { it["position"].asInt() }.distinct().size != rows.size)
          bad("Повтор позиции упражнения")
      }
      "workout" -> {
        shape(
          n,
          setOf(
            "name",
            "note",
            "routineId",
            "startedAt",
            "finishedAt",
            "exercises",
            "gymIds",
            "coachRevision",
          ),
        )
        text(n, "name")
        text(n, "note", 10000, true)
        number(n, "coachRevision", integer = true)
        number(n, "startedAt", true, true)
        number(n, "finishedAt", integer = true)
        ids(n, "gymIds")
        n["routineId"]?.takeUnless { it.isNull }?.let(::uuid)
        n["finishedAt"]
          ?.takeUnless { it.isNull }
          ?.let { if (it.asLong() < n["startedAt"].asLong()) bad("Окончание раньше начала") }
        val rows = array(n, "exercises", 200)
        rows.forEach { row ->
          shape(row, setOf("sectionId", "exerciseId", "position", "sets"))
          uuid(row["sectionId"])
          uuid(row["exerciseId"])
          number(row, "position", true, true, max = 1000.0)
          val sets = array(row, "sets", 1000)
          sets.forEach { set(it, true) }
          val stableIds = sets.mapNotNull { it.get("syncId")?.asString() }
          if (stableIds.distinct().size != stableIds.size) bad("Повтор идентификатора подхода")
          if (sets.map { it["setIndex"].asInt() }.distinct().size != sets.size)
            bad("Повтор подхода")
        }
        val allSetIds =
          rows.flatMap { it["sets"].toList() }.mapNotNull { it.get("syncId")?.asString() }
        if (allSetIds.distinct().size != allSetIds.size)
          bad("Повтор идентификатора подхода в тренировке")
        if (
          rows.map { it["sectionId"].asString() }.distinct().size != rows.size ||
            rows.map { it["position"].asInt() }.distinct().size != rows.size
        )
          bad("Повтор секции тренировки")
      }
      "measurement" -> {
        shape(n, measurementFields)
        measurementFields.forEach {
          number(
            n,
            it,
            required = it == "measuredAt",
            integer =
              it in
                setOf(
                  "measuredAt",
                  "visceralFatLevel",
                  "inBodyScore",
                  "basalMetabolicRateKcal",
                  "recommendedCalorieIntakeKcal",
                ),
          )
        }
        number(n, "bodyFatPercentage", max = 100.0)
      }
      "calendar_plan" -> {
        shape(n, setOf("routineId", "startsAtMillis", "timeZoneId", "legacyScheduleId"))
        calendarUuid(n["routineId"])
        instant(n, "startsAtMillis", zone(n))
        n["legacyScheduleId"]?.takeUnless { it.isNull }?.let(::calendarUuid)
      }
      "calendar_rule" -> {
        shape(
          n,
          setOf("routineId", "isoDay", "localTime", "timeZoneId", "startLocalDate", "legacyRuleKey"),
        )
        calendarUuid(n["routineId"])
        number(n, "isoDay", true, true, min = 1.0, max = 7.0)
        time(text(n, "localTime"))
        zone(n)
        date(text(n, "startLocalDate"))
        n["legacyRuleKey"]?.takeUnless { it.isNull }?.let { text(n, "legacyRuleKey", 1024) }
      }
      "calendar_exception" -> {
        shape(n, setOf("ruleId", "instanceKey", "kind", "movedAtMillis"))
        calendarUuid(n["ruleId"])
        text(n, "instanceKey", 300)
        enum(n, "kind", setOf("CANCELLED", "MOVED"))
        if (
          !n.has("movedAtMillis") ||
            (n["kind"].asString() == "CANCELLED") != n["movedAtMillis"].isNull
        )
          bad("Некорректный перенос")
      }
      "schedule" -> {
        shape(n, setOf("routineId", "dateTimeMillis", "calendarEventId"))
        uuid(n["routineId"])
        number(n, "dateTimeMillis", true, true)
        text(n, "calendarEventId", 1024, true)
      }
      else -> bad("Неизвестный тип объекта")
    }
  }

  fun references(
    records: Map<RecordKey, Record>,
    changed: Set<RecordKey> = records.keys,
    before: Map<RecordKey, Record> = emptyMap(),
  ) {
    val coverage = coverage()
    val configuration =
      changed
        .filter { key ->
          val old = before[key]?.payload
          val next = records[key]?.payload
          when (key.kind) {
            "exercise" ->
              old?.get("equipmentIds") != next?.get("equipmentIds") ||
                old?.get("equipmentRequirementState") != next?.get("equipmentRequirementState")
            "gym" ->
              listOf("equipmentIds", "inventoryConfigured", "exerciseIds").any {
                old?.get(it) != next?.get(it)
              }
            else -> false
          }
        }
        .toSet()

    val sectionIds = mutableSetOf<UUID>()
    val legacyPlans = mutableSetOf<UUID>()
    val legacyRules = mutableSetOf<String>()
    fun ref(kind: String, id: JsonNode) {
      if (records[RecordKey(kind, uuid(id))]?.deleted != false) bad("Ссылка на отсутствующий $kind")
    }
    records.values
      .filter { !it.deleted }
      .forEach { r ->
        val n = r.payload!!
        when (r.kind) {
          "strength_planner_profile" -> {
            val old = before[RecordKey(r.kind, r.id)]?.payload
            val oldKeys =
              old?.get("keyExercises")?.toList()?.map { it["exerciseId"].asString() }.orEmpty()
            n["keyExercises"].forEach { key ->
              val exerciseId = calendarUuid(key["exerciseId"])
              // Existing preferences remain readable and removable after a catalog lifecycle
              // change. Only a newly selected id must be a live strength exercise.
              if (exerciseId.toString() !in oldKeys) {
                val exercise = records[RecordKey("exercise", exerciseId)]
                if (
                  exercise?.deleted != false ||
                    exercise.payload?.get("type")?.asString() != "STRENGTH"
                )
                  bad("Ключевое упражнение требует доступное силовое упражнение")
              }
            }
          }
          "workout_effort" -> {
            if (RecordKey(r.kind, r.id) in changed) {
              val workoutId = calendarUuid(n["workoutId"])
              val workout = records[RecordKey("workout", workoutId)]
              if (workout?.deleted != false || workout.payload?.get("finishedAt")?.isNull != false)
                bad("Оценка требует завершённую тренировку")
            }
          }
          "gym" -> n["exerciseIds"].forEach { ref("exercise", it) }
          "routine",
          "workout" -> {
            n["exercises"].forEach { ref("exercise", it["exerciseId"]) }
            n["gymIds"].forEach { ref("gym", it) }
            // Completed workouts are historical snapshots; changing today's inventory must not
            // invalidate their past sets. Programs and active workouts enforce current coverage.
            val affected =
              RecordKey(r.kind, r.id) in changed || referencesOf(r).any { it in configuration }
            if (
              affected && (r.kind == "routine" || n["finishedAt"] == null || n["finishedAt"].isNull)
            ) {
              n["exercises"].forEach { row ->
                val exercise =
                  records.getValue(RecordKey("exercise", uuid(row["exerciseId"]))).payload!!
                n["gymIds"].forEach { gymId ->
                  val gym = records.getValue(RecordKey("gym", uuid(gymId))).payload!!
                  val available =
                    if (!gym["inventoryConfigured"].asBoolean()) {
                      gym["exerciseIds"].toList().any { uuid(it) == uuid(row["exerciseId"]) }
                    } else {
                      val coverage =
                        gym["equipmentIds"]
                          .toList()
                          .flatMap { coverage[it.asString()].orEmpty() }
                          .toSet()
                      exercise["equipmentRequirementState"].asString() == "KNOWN" &&
                        exercise["equipmentIds"].toList().all { it.asString() in coverage }
                    }
                  if (!available)
                    bad(
                      "Оборудование зала не покрывает упражнение программы или активной тренировки"
                    )
                }
              }
            }
            if (r.kind == "workout")
              n["routineId"]?.takeUnless { it.isNull }?.let { ref("routine", it) }
            if (r.kind == "workout")
              n["exercises"].forEach {
                if (!sectionIds.add(uuid(it["sectionId"])))
                  bad("Секция уже принадлежит другой тренировке")
              }
          }
          "calendar_plan" -> {
            ref("routine", n["routineId"])
            n["legacyScheduleId"]
              ?.takeUnless { it.isNull }
              ?.let { if (!legacyPlans.add(calendarUuid(it))) bad("Повтор источника плана") }
          }
          "calendar_rule" -> {
            ref("routine", n["routineId"])
            n["legacyRuleKey"]
              ?.takeUnless { it.isNull }
              ?.let { if (!legacyRules.add(it.asString())) bad("Повтор источника правила") }
            val old = before[RecordKey(r.kind, r.id)]?.payload
            if (
              old != null &&
                listOf("isoDay", "localTime", "timeZoneId", "startLocalDate").any {
                  old[it] != n[it]
                }
            )
              bad("Изменённое расписание требует нового UUID правила")
          }
          "calendar_exception" -> {
            ref("calendar_rule", n["ruleId"])
            val rule =
              records.getValue(RecordKey("calendar_rule", calendarUuid(n["ruleId"]))).payload!!
            val key = text(n, "instanceKey", 300)
            val suffix = "T${rule["localTime"].asString()}[${rule["timeZoneId"].asString()}]"
            if (!key.endsWith(suffix)) bad("Ключ не соответствует правилу")
            val day = date(key.removeSuffix(suffix))
            if (
              day < date(rule["startLocalDate"].asString()) ||
                day.dayOfWeek.value != rule["isoDay"].asInt()
            )
              bad("Дата не соответствует правилу")
            val expected =
              UUID.nameUUIDFromBytes(
                "ValerochkaGym.calendar-exception:v1:${n["ruleId"].asString()}:$key"
                  .toByteArray(StandardCharsets.UTF_8)
              )
            if (r.id != expected) bad("Некорректный UUID исключения")
            if (n["kind"].asString() == "MOVED") instant(n, "movedAtMillis", zone(rule))
          }
          "schedule" -> ref("routine", n["routineId"])
        }
      }
  }

  fun referencesOf(r: Record): Set<RecordKey> {
    val n = r.payload ?: return emptySet()
    val refs = mutableSetOf<RecordKey>()
    fun add(kind: String, node: JsonNode?) {
      node?.takeUnless { it.isNull }?.let { refs.add(RecordKey(kind, uuid(it))) }
    }
    when (r.kind) {
      "exercise_hint" -> refs.add(RecordKey("exercise", r.id))
      "strength_planner_profile" -> n["keyExercises"].forEach { add("exercise", it["exerciseId"]) }
      "gym" -> n["exerciseIds"].forEach { add("exercise", it) }
      "routine",
      "workout" -> {
        n["exercises"].forEach { add("exercise", it["exerciseId"]) }
        n["gymIds"].forEach { add("gym", it) }
        if (r.kind == "workout") add("routine", n["routineId"])
      }
      "calendar_plan",
      "calendar_rule",
      "schedule" -> add("routine", n["routineId"])
      "calendar_exception" -> add("calendar_rule", n["ruleId"])
    }
    return refs
  }

  fun archivedReferences(
    records: Map<RecordKey, Record>,
    before: Map<RecordKey, Record>,
    archived: Set<RecordKey>,
  ) {
    val archivedEquipment = equipmentRows.findAll().filter { it.archived }.map { it.id }.toSet()
    records.forEach { (key, r) ->
      val old = before[key]
      if ((referencesOf(r) - (old?.let(::referencesOf) ?: emptySet())).any { it in archived })
        bad("Архивный объект недоступен для нового выбора")
      val added =
        (r.payload?.get("equipmentIds")?.toList()?.map { it.asString() } ?: emptyList()).toSet() -
          (old?.payload?.get("equipmentIds")?.toList()?.map { it.asString() } ?: emptyList())
            .toSet()
      if (added.any { it in archivedEquipment })
        bad("Архивное оборудование недоступно для нового выбора")
    }
  }
}
