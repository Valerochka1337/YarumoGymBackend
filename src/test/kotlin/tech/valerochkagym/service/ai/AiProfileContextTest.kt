package tech.valerochkagym.service.ai

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class AiProfileContextTest {
  private val json = ObjectMapper()

  @Test
  fun `age uses full years and UTC day for leap birthdays and preserves unknowns`() {
    val payload =
      json
        .readTree(javaClass.getResourceAsStream("/basic-profile-sync-contract.json"))[
          "emptyPayload"]
        .deepCopy() as ObjectNode
    fun projection(now: String) =
      AiProfileContext.fromSaved(payload, Clock.fixed(Instant.parse(now), ZoneOffset.ofHours(-12)))
    assertNull(projection("2025-03-01T00:00:00Z").ageYears)
    assertNull(projection("2025-03-01T00:00:00Z").sex)
    assertTrue(projection("2025-03-01T00:00:00Z").equipmentIds.isEmpty())
    payload.put("birthDate", "2000-02-29")
    assertEquals(24, projection("2025-02-28T23:59:59Z").ageYears)
    assertEquals(25, projection("2025-03-01T00:00:00Z").ageYears)
    assertEquals(24, projection("2024-02-29T00:00:00Z").ageYears)
  }

  @Test
  fun `sparse saved profile preserves goal and leaves absent optional fields unknown`() {
    val clock = Clock.fixed(Instant.parse("2025-03-01T00:00:00Z"), ZoneOffset.UTC)
    val sparseProfiles =
      listOf(
        """{"trainingGoal":"STRENGTH"}""",
        """{"trainingGoal":"STRENGTH","equipmentIds":null}""",
      )
    for (raw in sparseProfiles) {
      val context = AiProfileContext.fromSaved(json.readTree(raw), clock)
      assertEquals("STRENGTH", context.trainingGoal)
      assertNull(context.sex)
      assertNull(context.ageYears)
      assertNull(context.experienceLevel)
      assertNull(context.plannedSessionsPerWeek)
      assertNull(context.preferredSessionDurationMinutes)
      assertNull(context.manualConstraints)
      assertEquals(emptyList<String>(), context.equipmentIds)
    }
  }
}
