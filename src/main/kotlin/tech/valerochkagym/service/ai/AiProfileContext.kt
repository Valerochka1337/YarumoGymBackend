package tech.valerochkagym.service.ai

import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import tools.jackson.databind.JsonNode

/** Only saved training context crosses the provider boundary; identity and exact DOB never do. */
data class AiProfileContext(
  val trainingGoal: String?,
  val sex: String?,
  val ageYears: Int?,
  val experienceLevel: String?,
  val plannedSessionsPerWeek: Int?,
  val preferredSessionDurationMinutes: Int?,
  val manualConstraints: String?,
  val equipmentIds: List<String>,
) {
  companion object {
    fun fromSaved(payload: JsonNode, clock: Clock): AiProfileContext {
      fun text(key: String) = payload[key]?.takeUnless { it.isNull }?.asString()
      fun number(key: String) = payload[key]?.takeUnless { it.isNull }?.asInt()
      return AiProfileContext(
        text("trainingGoal"),
        text("sex"),
        text("birthDate")?.let {
          Period.between(LocalDate.parse(it), LocalDate.now(clock.withZone(ZoneOffset.UTC))).years
        },
        text("experienceLevel"),
        number("plannedSessionsPerWeek"),
        number("preferredSessionDurationMinutes"),
        text("manualConstraints"),
        payload["equipmentIds"]?.takeUnless { it.isNull }?.toList()?.map { it.asString() }
          ?: emptyList(),
      )
    }
  }
}
