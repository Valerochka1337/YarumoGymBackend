package tech.valerochkagym.controller.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID
import tech.valerochkagym.controller.advice.bad
import tools.jackson.databind.JsonNode

data class Change(
  val kind: String,
  val id: UUID,
  val baseRevision: Long,
  val deleted: Boolean = false,
  val payload: JsonNode? = null,
) {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  constructor(
    @JsonProperty("kind") kind: String,
    @JsonProperty("id") id: String,
    @JsonProperty("baseRevision") baseRevision: Long,
    @JsonProperty("deleted") deleted: Boolean = false,
    @JsonProperty("payload") payload: JsonNode? = null,
  ) : this(kind, wireId(kind, id), baseRevision, deleted, payload)

  companion object {
    private fun wireId(kind: String, raw: String): UUID {
      val id = UUID.fromString(raw)
      if (
        kind in
          setOf(
            "calendar_plan",
            "calendar_rule",
            "calendar_exception",
            "exercise_hint",
            "profile",
            "strength_planner_profile",
            "workout_effort",
          ) && raw != id.toString()
      )
        bad("UUID объекта должен быть каноническим")
      return id
    }
  }
}

data class PushRequest(
  val operationId: UUID,
  val changes: List<Change>,
  val catalogRevision: Long? = null,
)

data class PushResult(val revision: Long)

data class Record(
  val kind: String,
  val id: UUID,
  val revision: Long,
  val deleted: Boolean,
  val payload: JsonNode?,
)

data class Snapshot(val revision: Long, val records: List<Record>)

data class ChangesPage(val revision: Long, val records: List<Record>, val nextCursor: String?)
