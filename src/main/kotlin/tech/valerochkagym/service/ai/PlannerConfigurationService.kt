package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.repository.catalog.StandardRepository
import tools.jackson.databind.ObjectMapper

data class PlannerConfiguration(
  val model: String = "",
  val instructions: String =
    "Сохраняй акцентные упражнения для прогресса. Варьируй вспомогательные упражнения осмысленно. Паттерн — редактируемый черновик. Учитывай выполненные тренировки и динамику нагрузки; не компенсируй все пропуски за одно занятие.",
  val historyDays: Int = 28,
  val detailDays: Int = 7,
  val maxRounds: Int = 6,
  val maxToolCalls: Int = 12,
  val timeoutSeconds: Int = 45,
  val weightStepKg: Double = 2.5,
  /** Persisted execution limits are captured by each deterministic run. */
  val deterministicLimits: DeterministicPlannerLimits = DeterministicPlannerLimits(),
  val collections: List<PlannerPatternCollection> = PlannerPatternDefaults.collections(),
  val defaultExerciseAccents: List<PlannerExerciseAccent> = emptyList(),
)

data class PlannerExerciseAccent(val exerciseId: String, val accent: String)

data class PlannerPatternCollection(
  val id: String,
  val goal: String,
  val name: String,
  val patterns: List<PlannerPattern>,
  /** Legacy field retained for existing admin payloads; planner behavior ignores it. */
  val sequence: List<String> = emptyList(),
)

data class PlannerPattern(
  val id: String,
  val name: String,
  val focus: String,
  val description: String,
  val slots: List<PlannerPatternSlot>,
)

data class PlannerPatternSlot(
  val role: String,
  val movement: String,
  val exerciseType: String = "STRENGTH",
  val exerciseCount: Int = 1,
  val sets: Int = 3,
  val repsMin: Int = 6,
  val repsMax: Int = 12,
  val restSeconds: Int = 120,
  val durationSeconds: Int = 0,
  val slotId: String = "",
  val movementClass: String = "",
  val allowedEquipmentIds: List<String> = emptyList(),
  val preferredSetCount: Int = sets,
  val preferredRestSeconds: Int = restSeconds,
  val targetTotalReps: Int? =
    if (exerciseType == "STRENGTH") sets * ((repsMin + repsMax) / 2) else null,
  val targetIntensityBasisPoints: Int? = if (exerciseType == "STRENGTH") 7000 else null,
  val targetActiveSeconds: Int? = if (exerciseType != "STRENGTH") durationSeconds * sets else null,
  val allowedRestSeconds: List<Int> = listOf(restSeconds),
  val allowedActiveSeconds: List<Int> =
    if (durationSeconds > 0) listOf(durationSeconds) else emptyList(),
)

/**
 * A single current server configuration. Published versions and lifecycle states are unnecessary.
 */
@Service
class PlannerConfigurationService(
  private val jdbc: JdbcTemplate,
  private val json: ObjectMapper,
  private val standard: StandardRepository,
) {
  @Transactional
  fun snapshot(): PlannerConfiguration {
    jdbc.update(
      "INSERT INTO planner_configuration (id, payload) VALUES (1, ?) ON CONFLICT (id) DO NOTHING",
      json.writeValueAsString(PlannerConfiguration()),
    )
    val raw =
      jdbc.queryForObject(
        "SELECT payload FROM planner_configuration WHERE id = 1",
        String::class.java,
      )!!
    return json.readValue(raw, PlannerConfiguration::class.java).let { value ->
      // Upgrade only known built-in IDs; never infer custom slot semantics from prose.
      val defaults =
        PlannerPatternDefaults.collections().flatMap { it.patterns }.associateBy { it.id }
      value.copy(
        collections =
          value.collections.map { c ->
            c.copy(
              patterns =
                c.patterns.map { p ->
                  p.copy(
                    slots =
                      p.slots.mapIndexed { i, slot ->
                        val seed = defaults[p.id]?.slots?.getOrNull(i)
                        if (slot.movementClass.isBlank() && seed != null)
                          slot.copy(slotId = seed.slotId, movementClass = seed.movementClass)
                        else slot
                      }
                  )
                }
            )
          }
      )
    }
  }

  @Transactional
  fun save(value: PlannerConfiguration): PlannerConfiguration {
    validateRawAccentList(value.defaultExerciseAccents)
    val canonical = canonical(value)
    val existingAccents = snapshot().defaultExerciseAccents.associate { it.exerciseId to it.accent }
    validate(canonical, existingAccents)
    jdbc.update(
      "INSERT INTO planner_configuration (id, payload) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload",
      json.writeValueAsString(canonical),
    )
    return canonical
  }

  private fun validateRawAccentList(accents: List<PlannerExerciseAccent>) {
    if (accents.size > 1000) bad("Слишком много акцентов")
    val ids =
      accents.map { accent ->
        val id = runCatching { UUID.fromString(accent.exerciseId).toString() }.getOrNull()
        if (id != accent.exerciseId || accent.accent !in setOf("MORE", "NORMAL", "LESS", "NEVER"))
          bad("Некорректный акцент упражнения")
        id
      }
    if (ids.distinct().size != ids.size) bad("Акценты упражнений повторяются")
  }

  private fun canonical(value: PlannerConfiguration): PlannerConfiguration =
    value.copy(
      defaultExerciseAccents =
        value.defaultExerciseAccents.filter { it.accent != "NORMAL" }.sortedBy { it.exerciseId }
    )

  private fun validate(
    value: PlannerConfiguration,
    retainedDefaultAccents: Map<String, String> = emptyMap(),
  ) {
    val liveStandardExercises =
      standard
        .findAllByOrderByKindAscIdAsc()
        .filter { it.kind == "exercise" && !it.archived }
        .mapTo(mutableSetOf()) { it.id.toString() }
    val defaultIds =
      value.defaultExerciseAccents.map { accent ->
        val id = runCatching { UUID.fromString(accent.exerciseId).toString() }.getOrNull()
        if (
          id != accent.exerciseId ||
            accent.accent !in setOf("MORE", "LESS", "NEVER") ||
            (id !in liveStandardExercises && retainedDefaultAccents[id] != accent.accent)
        )
          bad("Акцент требует доступное стандартное упражнение")
        id
      }
    if (defaultIds.distinct().size != defaultIds.size || defaultIds != defaultIds.sorted())
      bad("Акценты должны быть канонически упорядочены")

    fun text(s: String, max: Int, empty: Boolean = false) =
      (empty || s.isNotBlank()) && s.length <= max && '\u0000' !in s
    fun id(s: String) = s.matches(Regex("[a-zA-Z0-9_-]{1,80}"))
    if (
      !text(value.model, 200, true) ||
        value.model.any { it.code < 32 } ||
        !text(value.instructions, 8000, true) ||
        value.historyDays !in 7..28 ||
        value.detailDays !in 1..minOf(7, value.historyDays) ||
        value.maxRounds !in 3..10 ||
        value.maxToolCalls !in 2..24 ||
        value.timeoutSeconds !in 15..120 ||
        !value.weightStepKg.isFinite() ||
        value.weightStepKg !in 0.25..20.0 ||
        value.collections.size !in 1..12
    )
      bad("Проверьте параметры планировщика")
    val goals =
      setOf("STRENGTH", "MUSCLE_GAIN", "FAT_LOSS", "GENERAL_FITNESS", "ENDURANCE", "OTHER")
    if (
      value.collections.map { it.id }.distinct().size != value.collections.size ||
        value.collections.map { it.goal }.distinct().size != value.collections.size ||
        !value.collections.map { it.goal }.containsAll(DeterministicPlannerRuntime.goals)
    )
      bad("Коллекции должны иметь уникальные ID и цели; общая форма обязательна")
    val allPatterns = value.collections.flatMap { it.patterns }.map { it.id }
    if (allPatterns.distinct().size != allPatterns.size) bad("ID паттернов должны быть уникальны")
    value.collections.forEach { collection ->
      if (
        !id(collection.id) ||
          collection.goal !in goals ||
          !text(collection.name, 120) ||
          collection.patterns.size !in 1..20 ||
          collection.sequence.size > 20
      )
        bad("Проверьте коллекцию")
      collection.patterns.forEach { pattern ->
        if (
          !id(pattern.id) ||
            !text(pattern.name, 120) ||
            !text(pattern.description, 2000, true) ||
            pattern.focus !in
              setOf("FULL_BODY", "UPPER", "LOWER", "PUSH", "PULL", "CARDIO", "MIXED") ||
            pattern.slots.size !in 1..12 ||
            pattern.slots.sumOf { it.exerciseCount } > 12 ||
            pattern.slots
              .map { it.slotId }
              .filter { it.isNotEmpty() }
              .let { it.distinct().size != it.size }
        )
          bad("Проверьте описание паттерна")
        pattern.slots.forEach { slot ->
          if (
            !text(slot.role, 80) ||
              slot.movementClass !in
                setOf(
                  "HORIZONTAL_PUSH",
                  "HORIZONTAL_PULL",
                  "VERTICAL_PUSH",
                  "VERTICAL_PULL",
                  "SQUAT",
                  "HIP_HINGE",
                  "LUNGE",
                  "CARRY",
                  "CORE",
                  "CARDIO",
                  "MOBILITY",
                ) ||
              (slot.slotId.isNotEmpty() && !id(slot.slotId)) ||
              slot.allowedEquipmentIds.size > 200 ||
              slot.allowedEquipmentIds.any { it.isBlank() || it.length > 255 } ||
              !text(slot.movement, 300) ||
              slot.exerciseType !in setOf("STRENGTH", "TIMED", "CARDIO") ||
              slot.exerciseCount !in 1..4 ||
              slot.sets !in 1..20 ||
              slot.restSeconds !in 0..600 ||
              slot.repsMin !in 1..30 ||
              slot.repsMax !in slot.repsMin..30 ||
              slot.durationSeconds !in 0..7200 ||
              slot.preferredSetCount !in 1..20 ||
              slot.preferredRestSeconds !in 0..600 ||
              slot.allowedRestSeconds.isEmpty() ||
              slot.allowedRestSeconds.distinct().size != slot.allowedRestSeconds.size ||
              slot.allowedRestSeconds.any { it !in 0..600 } ||
              (slot.exerciseType == "STRENGTH" &&
                (slot.targetTotalReps !in 1..600 ||
                  slot.targetIntensityBasisPoints !in 1..10000)) ||
              (slot.exerciseType != "STRENGTH" &&
                (slot.targetActiveSeconds !in 1..14400 ||
                  slot.allowedActiveSeconds.isEmpty() ||
                  slot.allowedActiveSeconds.any { it !in 1..14400 })) ||
              (slot.exerciseType != "STRENGTH" && slot.durationSeconds < 10)
          )
            bad("Проверьте параметры слота")
        }
      }
    }
    if (json.writeValueAsBytes(value).size > 180_000) bad("Конфигурация слишком большая")
  }
}
