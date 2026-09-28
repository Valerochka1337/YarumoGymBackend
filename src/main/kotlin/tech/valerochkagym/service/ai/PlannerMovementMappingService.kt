package tech.valerochkagym.service.ai

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.repository.planner.PlannerExerciseMappingRepository
import tech.valerochkagym.repository.planner.PlannerExerciseMappingRow
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.ObjectMapper

data class PlannerExerciseMappingDto(
  val exerciseId: UUID,
  val movementClass: String,
  val roles: List<String>,
  val supportedGoals: List<String>,
  val exerciseType: String,
  val equipmentIds: List<String>,
  val revision: Long? = null,
)

data class PlannerExerciseMappingList(val items: List<PlannerExerciseMappingDto>)

@Service
class PlannerMovementMappingService(
  private val mappings: PlannerExerciseMappingRepository,
  private val records: RecordRepository,
  private val standard: StandardRepository,
  private val json: ObjectMapper,
  private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
  private val movements =
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
    )
  private val roles = setOf("PRIMARY", "ACCESSORY", "CONDITIONING")
  private val goals = setOf("STRENGTH", "MUSCLE_GAIN", "FAT_LOSS", "GENERAL_FITNESS", "ENDURANCE")
  private val types = setOf("STRENGTH", "TIMED", "CARDIO")

  fun list(identity: Identity) =
    PlannerExerciseMappingList(
      mappings
        .ownerList(identity.userId)
        .filter { owned(identity.userId, it.exerciseId) }
        .map(::dto)
    )

  fun get(identity: Identity, exerciseId: UUID): PlannerExerciseMappingDto {
    if (!owned(identity.userId, exerciseId)) hidden()
    return mappings.owner(identity.userId, exerciseId)?.let(::dto)
      ?: throw ApiException(404, "not_found", "Классификация упражнения не найдена")
  }

  @Transactional
  fun put(
    identity: Identity,
    exerciseId: UUID,
    body: PlannerExerciseMappingDto,
  ): PlannerExerciseMappingDto {
    jdbc.queryForObject("SELECT revision FROM catalog_state FOR SHARE", Long::class.java)
    jdbc.query(
      "SELECT revision FROM sync_heads WHERE user_id=? FOR UPDATE",
      { rs, _ -> rs.getLong(1) },
      identity.userId,
    )
    if (body.exerciseId != exerciseId || !owned(identity.userId, exerciseId)) hidden()
    val source =
      records
        .findById(
          tech.valerochkagym.repository.model.RecordId(identity.userId, "exercise", exerciseId)
        )
        .orElseThrow()
    validateSource(body, source.payload ?: hidden())
    val normalized = normalize(body.copy(revision = null))
    mappings.upsertOwner(identity.userId, row(normalized))
    return get(identity, exerciseId)
  }

  @Transactional
  fun delete(identity: Identity, exerciseId: UUID) {
    jdbc.query(
      "SELECT revision FROM sync_heads WHERE user_id=? FOR UPDATE",
      { rs, _ -> rs.getLong(1) },
      identity.userId,
    )
    if (!owned(identity.userId, exerciseId)) hidden()
    mappings.deleteOwner(identity.userId, exerciseId)
  }

  fun effective(
    owner: UUID,
    exerciseId: UUID,
    builtInAllowed: Boolean = true,
  ): PlannerExerciseMappingDto? =
    mappings.owner(owner, exerciseId)?.let(::dto)
      ?: if (builtInAllowed) mappings.builtIn(exerciseId)?.let(::dto) else null

  fun listBuiltIn() = PlannerExerciseMappingList(mappings.builtInList().map(::dto))

  @Transactional
  fun putBuiltIn(exerciseId: UUID, body: PlannerExerciseMappingDto): PlannerExerciseMappingDto {
    jdbc.queryForObject("SELECT revision FROM catalog_state FOR UPDATE", Long::class.java)
    if (
      body.exerciseId != exerciseId ||
        standard.findByKindAndIdInAndArchivedFalse("exercise", listOf(exerciseId)).isEmpty()
    )
      hidden()
    validateSource(
      body,
      standard.findByKindAndIdInAndArchivedFalse("exercise", listOf(exerciseId)).single().payload,
    )
    val normalized = normalize(body.copy(revision = null))
    mappings.upsertBuiltIn(row(normalized))
    return mappings.builtIn(exerciseId)?.let(::dto) ?: error("mapping write failed")
  }

  private fun owned(owner: UUID, exercise: UUID) =
    records
      .findById(tech.valerochkagym.repository.model.RecordId(owner, "exercise", exercise))
      .orElse(null)
      ?.deleted == false &&
      standard.findByKindAndIdInAndArchivedFalse("exercise", listOf(exercise)).isEmpty()

  private fun validateSource(value: PlannerExerciseMappingDto, raw: String) {
    val source = json.readTree(raw)
    if (
      source["archived"]?.asBoolean() == true ||
        source["type"]?.asString() != value.exerciseType ||
        source["equipmentIds"]?.toList()?.map { it.asString() }?.toSet().orEmpty() !=
          value.equipmentIds.toSet()
    )
      bad("Тип и оборудование должны соответствовать упражнению")
  }

  private fun normalize(value: PlannerExerciseMappingDto): PlannerExerciseMappingDto {
    if (
      value.movementClass !in movements ||
        value.exerciseType !in types ||
        value.roles.isEmpty() ||
        value.roles.any { it !in roles } ||
        value.roles.distinct().size != value.roles.size ||
        value.supportedGoals.isEmpty() ||
        value.supportedGoals.any { it !in goals } ||
        value.supportedGoals.distinct().size != value.supportedGoals.size ||
        value.equipmentIds.any { it.isBlank() || it.length > 255 } ||
        value.equipmentIds.distinct().size != value.equipmentIds.size
    )
      bad("Некорректная классификация упражнения")
    return value.copy(
      roles = value.roles.sorted(),
      supportedGoals = value.supportedGoals.sorted(),
      equipmentIds = value.equipmentIds.sorted(),
    )
  }

  private fun row(value: PlannerExerciseMappingDto) =
    PlannerExerciseMappingRow(
      value.exerciseId,
      value.movementClass,
      json.writeValueAsString(value.roles),
      json.writeValueAsString(value.supportedGoals),
      value.exerciseType,
      json.writeValueAsString(value.equipmentIds),
      0,
    )

  private fun dto(row: PlannerExerciseMappingRow) =
    PlannerExerciseMappingDto(
      row.exerciseId,
      row.movementClass,
      json.readValue(row.roles, Array<String>::class.java).toList(),
      json.readValue(row.supportedGoals, Array<String>::class.java).toList(),
      row.exerciseType,
      json.readValue(row.equipmentIds, Array<String>::class.java).toList(),
      row.revision,
    )

  private fun hidden(): Nothing = throw ApiException(404, "not_found", "Упражнение не найдено")
}
