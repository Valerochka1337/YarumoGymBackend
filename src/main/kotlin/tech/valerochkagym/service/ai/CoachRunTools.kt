package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.core.io.ClassPathResource
import tech.valerochkagym.service.ai.coach.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** JSON boundary for the pinned snapshot. No operation can escape its section/set namespace. */
internal class CoachRunTools(private val json: ObjectMapper) {
  val schemas: JsonNode by lazy {
    ClassPathResource("ai/coach-tools.json").inputStream.use { json.readTree(it) }
  }

  private fun keys(node: JsonNode, allowed: Set<String>, required: Set<String> = emptySet()) {
    require(
      node.isObject &&
        node.properties().map { it.key }.all { it in allowed } &&
        required.all(node::has)
    ) {
      "Неизвестные или пропущенные поля"
    }
  }

  private fun uuid(node: JsonNode?): String {
    require(node?.isString == true) { "Требуется UUID" }
    val raw = node.asString()
    require(UUID.fromString(raw).toString().equals(raw, true)) { "Некорректный UUID" }
    return raw
  }

  private fun int(node: JsonNode?, min: Long = 0, max: Long = 1_000_000): Long {
    require(node?.isIntegralNumber == true && node.asLong() in min..max) {
      "Некорректное целое число"
    }
    return node.asLong()
  }

  private fun ids(node: JsonNode?): List<String> {
    require(node?.isArray == true && node.size() <= 100) { "Требуется список UUID" }
    val values = node.toList().map(::uuid)
    require(values.distinct().size == values.size) { "Повторный UUID" }
    return values
  }

  private fun text(node: JsonNode?, max: Int): String {
    require(node?.isString == true && node.asString().length in 1..max) { "Некорректный текст" }
    return node.asString()
  }

  private fun number(node: JsonNode, min: Double = 0.0, max: Double = 1_000_000.0): Double {
    require(node.isNumber && node.asDouble().isFinite() && node.asDouble() in min..max) {
      "Некорректное число"
    }
    return node.asDouble()
  }

  fun validateRead(name: String, args: JsonNode) {
    when (name) {
      "get_workout_state" -> {
        keys(args, setOf("autoregulation"))
        if (args.has("autoregulation")) options(args["autoregulation"])
      }
      "get_exercise_history" -> {
        keys(args, setOf("exercise_id"), setOf("exercise_id"))
        uuid(args["exercise_id"])
      }
      "find_exercises" -> {
        keys(args, setOf("query", "equipment_ids", "muscle_ids", "muscle_groups", "limit"))
        args["query"]?.let { text(it, 200) }
        args["limit"]?.let { int(it, 1, 20) }
        listOf("equipment_ids", "muscle_ids", "muscle_groups").forEach { key ->
          args[key]?.let {
            require(it.isArray && it.size() <= 100)
            it.forEach { value -> text(value, 120) }
          }
        }
      }
    }
  }

  fun operations(
    owner: UUID,
    snapshot: JsonNode,
    args: JsonNode,
    knownExercise: (String) -> Boolean,
  ): List<JsonNode> {
    keys(args, setOf("base_revision", "operations", "reason"), setOf("base_revision", "operations"))
    require(int(args["base_revision"], max = Long.MAX_VALUE) == snapshot["revision"].asLong()) {
      "Ревизия изменилась: перечитай состояние"
    }
    args["reason"]?.let { text(it, 1200) }
    val raw = args["operations"]
    require(raw?.isArray == true && raw.size() in 1..32) { "Пакет должен содержать 1–32 операции" }
    val expanded =
      raw.toList().flatMap { op ->
        if (op["action"]?.asString() == "autoregulate") {
          keys(
            op,
            setOf(
              "action",
              "reason",
              "goal",
              "exercise_id",
              "available_weights_kg",
              "observed_rest_seconds",
            ),
          )
          calculation(owner, snapshot, options(op, snapshot)).operations.map(::wire)
        } else listOf(op)
      }
    require(expanded.size <= 32) { "Слишком много операций" }
    val sections = snapshot["exercises"].toList().associateBy { it["section_id"].asString() }
    val sets =
      sections.values.flatMap { it["sets"].toList() }.associateBy { it["set_id"].asString() }
    val edited = mutableSetOf<String>()
    fun section(value: JsonNode?): String =
      uuid(value).also { require(it in sections) { "Неизвестная секция" } }
    fun set(value: JsonNode?): String =
      uuid(value).also { require(it in sets) { "Неизвестный подход" } }
    expanded.forEach { op ->
      val action = text(op["action"], 40)
      fun fields(vararg names: String) =
        keys(op, names.toSet() + setOf("action", "reason"), setOf("action"))
      op["reason"]?.let { text(it, 1200) }
      when (action) {
        "add_exercise" -> {
          fields("exercise_id", "position")
          require(knownExercise(uuid(op["exercise_id"]))) { "Упражнение недоступно" }
          op["position"]?.let { int(it, max = sections.size.toLong()) }
        }
        "remove_remaining" -> {
          fields("section_id")
          section(op["section_id"])
        }
        "move_exercise" -> {
          fields("section_id", "position")
          section(op["section_id"])
          int(op["position"], max = (sections.size - 1).toLong())
        }
        "swap_exercises" -> {
          fields("first_section_id", "second_section_id")
          require(section(op["first_section_id"]) != section(op["second_section_id"]))
        }
        "reorder_exercises" -> {
          fields("section_ids")
          require(ids(op["section_ids"]).toSet() == sections.keys) {
            "Передай все секции ровно один раз"
          }
        }
        "replace_remaining" -> {
          fields("section_id", "exercise_id", "remaining_set_ids", "weight_kg")
          val target = section(op["section_id"])
          require(knownExercise(uuid(op["exercise_id"]))) { "Упражнение недоступно" }
          val remaining =
            sections
              .getValue(target)["sets"]
              .toList()
              .filter { it["completed"]?.asBoolean() != true }
              .map { it["set_id"].asString() }
              .toSet()
          require(ids(op["remaining_set_ids"]).toSet() == remaining && remaining.isNotEmpty()) {
            "Нужно передать все оставшиеся подходы секции"
          }
          op["weight_kg"]?.let { number(it) }
        }
        "add_set" -> {
          fields("section_id")
          section(op["section_id"])
        }
        "delete_set" -> {
          fields("set_id")
          val id = set(op["set_id"])
          require(sets.getValue(id)["completed"]?.asBoolean() != true) {
            "Выполненный подход нельзя удалить"
          }
          require(edited.add(id)) { "Конфликт операций подхода" }
        }
        "edit_set",
        "record_result" -> {
          fields("set_id", "values")
          val id = set(op["set_id"])
          require(edited.add(id)) { "Конфликт операций подхода" }
          if (action == "edit_set")
            require(sets.getValue(id)["completed"]?.asBoolean() != true) {
              "Для факта используй record_result"
            }
          val values = op["values"]
          require(values != null && !values.isEmpty) { "Нет значений" }
          keys(
            values,
            setOf(
              "weight_kg",
              "reps",
              "duration_sec",
              "speed_kmh",
              "incline_pct",
              "actual_rir",
              "set_type",
            ),
          )
          values.properties().forEach { (key, value) ->
            if (!value.isNull)
              when (key) {
                "set_type" ->
                  require(text(value, 20) in setOf("WORK", "WARMUP", "UNKNOWN", "DROP", "AMRAP"))
                "actual_rir" -> int(value, max = 10)
                "reps",
                "duration_sec" -> int(value)
                else -> number(value, if (key == "incline_pct") -100.0 else 0.0)
              }
          }
        }
        "set_completed" -> {
          fields("set_id", "completed")
          set(op["set_id"])
          require(op["completed"]?.isBoolean == true)
        }
        "start_rest",
        "future_rest_duration" -> {
          fields("seconds")
          int(op["seconds"], 1, 86400)
        }
        "extend_rest",
        "skip_rest" -> {
          fields(
            *if (action == "extend_rest") arrayOf("seconds", "rest_start_id")
            else arrayOf("rest_start_id")
          )
          require(text(op["rest_start_id"], 200) == snapshot["rest"]?.get("start_id")?.asString()) {
            "Отдых изменился"
          }
          if (action == "extend_rest") int(op["seconds"], 1, 86400)
        }
        "available_time" -> {
          fields("minutes")
          int(op["minutes"], 0, 1440)
        }
        "excluded_exercises" -> {
          fields("exercise_ids")
          ids(op["exercise_ids"]).forEach { require(knownExercise(it)) { "Упражнение недоступно" } }
        }
        "report_feelings" -> {
          fields("set_id", "feelings")
          set(op["set_id"])
          val feelings = op["feelings"]
          require(feelings?.isArray == true && feelings.size() <= 6)
          feelings.forEach {
            require(
              it.asString() in
                setOf(
                  "PAIN",
                  "FATIGUE",
                  "TECHNIQUE_BREAKDOWN",
                  "INTERRUPTED",
                  "PLANNED_EFFORT",
                  "HARDER_THAN_EXPECTED",
                )
            )
          }
        }
        "undo_last" -> {
          fields()
          require(expanded.size == 1) { "Отмена должна быть отдельной" }
        }
        else -> throw IllegalArgumentException("Неизвестная операция")
      }
    }
    return expanded
  }

  private fun options(node: JsonNode, snapshot: JsonNode? = null): AutoregulationOptions {
    keys(
      node,
      setOf(
        "action",
        "reason",
        "goal",
        "exercise_id",
        "available_weights_kg",
        "observed_rest_seconds",
      ),
    )
    val goal =
      node["goal"]?.let { TrainingGoal.valueOf(text(it, 30)) }
        ?: snapshot
          ?.get("autoregulation_options")
          ?.get("goal")
          ?.asString()
          ?.let(TrainingGoal::valueOf)
        ?: TrainingGoal.PRESERVE_PLAN
    val weights =
      node["available_weights_kg"]?.let { values ->
        val id = uuid(node["exercise_id"])
        require(values.isArray && values.size() in 1..100)
        mapOf(id to values.toList().map { number(it, 0.001, 1000.0) }.distinct().sorted())
      }
        ?: snapshot
          ?.get("autoregulation_options")
          ?.get("available_weights_kg")
          ?.properties()
          ?.associate { (id, values) -> id to values.toList().map { number(it, 0.001, 1000.0) } }
        ?: emptyMap()
    require(!node.has("exercise_id") || weights.isNotEmpty())
    return AutoregulationOptions(
      goal,
      weights,
      node["observed_rest_seconds"]?.let { int(it, 0, 86400).toInt() }
        ?: snapshot?.get("autoregulation_options")?.get("observed_rest_seconds")?.let {
          int(it, 0, 86400).toInt()
        },
    )
  }

  private fun wire(op: WorkoutChangeSet.Operation): JsonNode =
    json.valueToTree<JsonNode>(
      when (op) {
        is WorkoutChangeSet.Operation.EditSet ->
          mapOf(
            "action" to "edit_set",
            "set_id" to op.setSyncId,
            "values" to
              listOfNotNull(op.weightKg?.let { "weight_kg" to it }, op.reps?.let { "reps" to it })
                .toMap(),
          )
        is WorkoutChangeSet.Operation.DeleteSet ->
          mapOf("action" to "delete_set", "set_id" to op.setSyncId)
        is WorkoutChangeSet.Operation.Rest ->
          mapOf("action" to "future_rest_duration", "seconds" to op.seconds)
      }
    )

  fun assessment(owner: UUID, snapshot: JsonNode, args: JsonNode): JsonNode {
    val result = calculation(owner, snapshot, options(args, snapshot))
    return json.valueToTree<JsonNode>(
      mapOf(
        "kind" to result.kind.name,
        "observation" to result.observation,
        "reason" to result.reason,
        "expected_effect" to result.expectedEffect,
        "missing_data" to result.missingData.map { it.name },
        "operations" to result.operations.map(::wire),
        "rules_version" to result.rulesVersion,
      )
    )
  }

  private fun calculation(owner: UUID, node: JsonNode, options: AutoregulationOptions) =
    AutoregulationEngine.calculate(snapshot(owner, node), options)

  private fun snapshot(owner: UUID, node: JsonNode): WorkoutSnapshot {
    fun JsonNode.num(key: String) = get(key)?.takeIf { it.isNumber }?.asDouble()
    fun JsonNode.integer(key: String) = get(key)?.takeIf { it.isIntegralNumber }?.asInt()
    fun JsonNode.strings(key: String) = get(key)?.toList().orEmpty().map { it.asString() }.toSet()
    val profile = node["profile"]
    return WorkoutSnapshot(
      accountId = owner.toString(),
      workoutId = node["workout_id"].asString(),
      revision = node["revision"].asLong(),
      exercises =
        node["exercises"].toList().map { e ->
          SnapshotExercise(
            sectionId = e["section_id"].asString(),
            exerciseId = e["exercise_id"].asString(),
            exerciseSyncId = e["exercise_id"].asString(),
            name = e["name"]?.asString().orEmpty(),
            type = e["type"]?.asString(),
            sets =
              e["sets"].toList().map { s ->
                SnapshotSet(
                  syncId = s["set_id"].asString(),
                  setIndex = s["index"].asInt(),
                  completed = s["completed"].asBoolean(),
                  weightKg = s.num("weight_kg"),
                  reps = s.integer("reps"),
                  durationSec = s.integer("duration_sec"),
                  completedAt = s["completed_at"]?.asLong(),
                  setType = s["set_type"]?.asString() ?: "UNKNOWN",
                  actualWeightKg = s.num("actual_weight_kg"),
                  actualReps = s.integer("actual_reps"),
                  actualRir = s.integer("actual_rir"),
                  actualRirAtLeastFour = s["actual_rir_at_least_four"]?.asBoolean() ?: false,
                  reportedFeelings = s.strings("reported_feelings"),
                )
              },
            history =
              e["history"]
                ?.toList()
                ?.map { h ->
                  SnapshotHistory(
                    completedAt = h["completed_at"]?.asLong() ?: 0,
                    setIndex = h["set_index"]?.asInt() ?: 0,
                    weightKg = h.num("weight_kg"),
                    reps = h.integer("reps"),
                    durationSec = h.integer("duration_sec"),
                    setType = h["set_type"]?.asString() ?: "UNKNOWN",
                    workoutId = h["workout_id"]?.asString().orEmpty(),
                    setSyncId = h["set_id"]?.asString().orEmpty(),
                    interrupted = h["interrupted"]?.asBoolean() ?: false,
                  )
                }
                .orEmpty(),
          )
        },
      availableTimeMinutes = node.integer("available_time_minutes"),
      futureRestSeconds = node.integer("future_rest_seconds"),
      excludedExerciseIds = node.strings("excluded_exercise_ids"),
      observedAtMillis = node["observed_at_millis"]?.asLong() ?: System.currentTimeMillis(),
      rest =
        node["rest"]?.let {
          SnapshotRest(
            it["start_id"].asString(),
            it.integer("planned_seconds"),
            it.integer("remaining_seconds"),
            0,
          )
        },
      profile =
        CoachProfile(
          trainingGoal = profile?.get("training_goal")?.asString(),
          preferredRepMin = profile?.integer("preferred_rep_min"),
          preferredRepMax = profile?.integer("preferred_rep_max"),
        ),
    )
  }

  fun initiative(current: JsonNode, previous: JsonNode?, memory: JsonNode?): String? {
    if (
      current["initiative_enabled"]?.asBoolean() == false ||
        current["pending_interaction"]?.asBoolean() == true ||
        current["finished"]?.asBoolean() == true
    )
      return null
    val exercises = current["exercises"]?.toList().orEmpty()
    val completed =
      exercises.flatMap { e ->
        e["sets"]
          ?.toList()
          .orEmpty()
          .filter { it["completed"]?.asBoolean() == true }
          .map { e to it }
      }
    val latest = completed.maxByOrNull { it.second["completed_at"]?.asLong() ?: 0 }
    val remaining =
      exercises
        .flatMap { it["sets"]?.toList().orEmpty() }
        .filter { it["completed"]?.asBoolean() != true }
    if (remaining.isEmpty()) return null
    if (latest != null) {
      val (section, set) = latest
      val feelings = set["reported_feelings"]?.toList()?.map { it.asString() }.orEmpty().toSet()
      val decisions = (memory?.toList().orEmpty() + current["decisions"]?.toList().orEmpty())
      val decision =
        decisions.lastOrNull { d ->
          d["sectionIds"]?.any { it.asString() == section["section_id"].asString() } == true
        }
      if (
        decision?.get("status")?.asString() == "REJECTED" &&
          feelings.none { it in setOf("PAIN", "TECHNIQUE_BREAKDOWN", "INTERRUPTED") }
      ) {
        if (decision["reason"]?.asString() == "KEEP_EXERCISE") return null
        val evidence =
          decision["evidence"]?.firstOrNull {
            it["sectionId"]?.asString() == section["section_id"].asString()
          }
        if (evidence != null) {
          val weight = (set["actual_weight_kg"] ?: set["weight_kg"])?.asDouble()
          val reps = (set["actual_reps"] ?: set["reps"])?.asInt()
          val oldWeight = evidence["weightKg"]?.takeUnless { it.isNull }?.asDouble()
          val oldReps = evidence["reps"]?.takeUnless { it.isNull }?.asInt()
          val newFeeling =
            "HARDER_THAN_EXPECTED" in feelings &&
              evidence["feelings"]?.any { it.asString() == "HARDER_THAN_EXPECTED" } != true
          val worseRir =
            set["actual_rir"]?.takeUnless { it.isNull }?.asInt() == 0 &&
              (evidence["rir"]?.takeUnless { it.isNull }?.asInt() ?: 0) > 1
          if (
            weight == oldWeight &&
              !newFeeling &&
              !worseRir &&
              (reps == null || oldReps == null || kotlin.math.abs(reps - oldReps) < 3)
          )
            return null
        }
      }
    }
    // Compare facts, not revision: receipts/profile refreshes alone must not create infinite runs.
    val oldSets =
      previous
        ?.get("exercises")
        ?.toList()
        ?.flatMap { it["sets"]?.toList().orEmpty() }
        .orEmpty()
        .associateBy { it["set_id"]?.asString() }
    val changed = latest?.second?.let { it != oldSets[it["set_id"]?.asString()] } ?: false
    val minutes = current["available_time_minutes"]?.takeIf { it.isNumber }?.asInt()
    val oldMinutes = previous?.get("available_time_minutes")?.takeIf { it.isNumber }?.asInt()
    val timeChanged = minutes != null && minutes <= 5 && minutes != oldMinutes
    if (!changed && !timeChanged) return null
    val assessment = calculation(UUID(0, 0), current, options(json.createObjectNode(), current))
    if (assessment.missingData.any { it == MissingData.SET_TYPE || it == MissingData.ACTUAL_RIR })
      return null
    return if (assessment.kind == RecommendationKind.NO_CHANGE) null else assessment.explanation()
  }
}
