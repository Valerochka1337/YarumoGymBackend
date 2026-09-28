package tech.valerochkagym.repository.planner

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class PlannerExerciseMappingRow(
  val exerciseId: UUID,
  val movementClass: String,
  val roles: String,
  val supportedGoals: String,
  val exerciseType: String,
  val equipmentIds: String,
  val revision: Long,
)

@Repository
class PlannerExerciseMappingRepository(private val jdbc: JdbcTemplate) {
  private fun row(rs: java.sql.ResultSet) =
    PlannerExerciseMappingRow(
      rs.getObject("exercise_id", UUID::class.java),
      rs.getString("movement_class"),
      rs.getString("roles"),
      rs.getString("supported_goals"),
      rs.getString("exercise_type"),
      rs.getString("equipment_ids"),
      rs.getLong("revision"),
    )

  fun owner(owner: UUID, exercise: UUID) =
    jdbc
      .query(
        "SELECT exercise_id,movement_class,roles::text,supported_goals::text,exercise_type,equipment_ids::text,revision FROM planner_owner_exercise_mappings WHERE owner_id=? AND exercise_id=? FOR SHARE",
        { rs, _ -> row(rs) },
        owner,
        exercise,
      )
      .singleOrNull()

  fun ownerList(owner: UUID) =
    jdbc.query(
      "SELECT exercise_id,movement_class,roles::text,supported_goals::text,exercise_type,equipment_ids::text,revision FROM planner_owner_exercise_mappings WHERE owner_id=? ORDER BY exercise_id",
      { rs, _ -> row(rs) },
      owner,
    )

  fun builtIn(exercise: UUID) =
    jdbc
      .query(
        "SELECT exercise_id,movement_class,roles::text,supported_goals::text,exercise_type,equipment_ids::text,revision FROM planner_builtin_exercise_mappings WHERE exercise_id=? FOR SHARE",
        { rs, _ -> row(rs) },
        exercise,
      )
      .singleOrNull()

  fun builtInList() =
    jdbc.query(
      "SELECT exercise_id,movement_class,roles::text,supported_goals::text,exercise_type,equipment_ids::text,revision FROM planner_builtin_exercise_mappings ORDER BY exercise_id",
      { rs, _ -> row(rs) },
    )

  fun upsertOwner(owner: UUID, value: PlannerExerciseMappingRow) {
    jdbc.update(
      "INSERT INTO planner_owner_exercise_mappings(owner_id,exercise_id,movement_class,roles,supported_goals,exercise_type,equipment_ids,revision) VALUES (?,?,?,?::jsonb,?::jsonb,?,?::jsonb,1) ON CONFLICT(owner_id,exercise_id) DO UPDATE SET movement_class=excluded.movement_class,roles=excluded.roles,supported_goals=excluded.supported_goals,exercise_type=excluded.exercise_type,equipment_ids=excluded.equipment_ids,revision=planner_owner_exercise_mappings.revision+1,updated_at=now()",
      owner,
      value.exerciseId,
      value.movementClass,
      value.roles,
      value.supportedGoals,
      value.exerciseType,
      value.equipmentIds,
    )
  }

  fun deleteOwner(owner: UUID, exercise: UUID) =
    jdbc.update(
      "DELETE FROM planner_owner_exercise_mappings WHERE owner_id=? AND exercise_id=?",
      owner,
      exercise,
    )

  fun upsertBuiltIn(value: PlannerExerciseMappingRow) {
    jdbc.update(
      "INSERT INTO planner_builtin_exercise_mappings(exercise_id,movement_class,roles,supported_goals,exercise_type,equipment_ids,revision) VALUES (?,?,?,?::jsonb,?::jsonb,?,?::jsonb,1) ON CONFLICT(exercise_id) DO UPDATE SET movement_class=excluded.movement_class,roles=excluded.roles,supported_goals=excluded.supported_goals,exercise_type=excluded.exercise_type,equipment_ids=excluded.equipment_ids,revision=planner_builtin_exercise_mappings.revision+1,updated_at=now()",
      value.exerciseId,
      value.movementClass,
      value.roles,
      value.supportedGoals,
      value.exerciseType,
      value.equipmentIds,
    )
  }
}
