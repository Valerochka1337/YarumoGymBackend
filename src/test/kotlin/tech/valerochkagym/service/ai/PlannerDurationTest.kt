package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.valerochkagym.controller.model.PlannedExercise
import tech.valerochkagym.controller.model.PlannedSet
import tools.jackson.databind.json.JsonMapper

class PlannerDurationTest {
  @Test
  fun `shared fixtures count work rest transitions and listed warmup once`() {
    val json = JsonMapper.builder().build()
    val root = json.readTree(javaClass.getResourceAsStream("/planner-duration-v1.json"))
    root["cases"].forEach { row ->
      val exercises =
        row["exercises"].toList().map { e ->
          PlannedExercise(
            "fixture",
            e["restSeconds"].takeUnless { it.isNull }?.asInt(),
            e["durations"].toList().map {
              PlannedSet(
                null,
                if (it.isNull) 10 else null,
                it.takeUnless { it.isNull }?.asInt(),
                null,
                null,
              )
            },
          )
        }
      val seconds = PlannerDuration.seconds(exercises)
      val minimum = PlannerDuration.minimumSeconds(row["desiredMinutes"].asInt())
      assertEquals(row["seconds"].asLong(), seconds, row["name"].asString())
      assertEquals(row["minimumSeconds"].asLong(), minimum)
      assertEquals(row["shortfall"].asBoolean(), seconds < minimum)
    }
  }
}
