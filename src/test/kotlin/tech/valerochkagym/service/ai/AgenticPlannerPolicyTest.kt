package tech.valerochkagym.service.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class AgenticPlannerPolicyTest {
  private val json = JsonMapper.builder().build()
  private val standard = StrengthPlannerFacts.compoundSeedIds.first()
  private val custom = UUID(0, 2).toString()
  private val explicit = UUID(0, 3).toString()

  private fun row(id: String) = mapOf<String, Any>("exerciseId" to id, "type" to "STRENGTH")

  @Test
  fun `completed history does not change candidate pool or ordering`() {
    val eligible =
      (1..30).map { index ->
        row(UUID(0, index.toLong()).toString()) +
          ("muscles" to
            listOf(mapOf("muscle" to if (index <= 26) "LATS" else "QUADS", "contribution" to 100)))
      }
    val withoutHistory = AgenticPlannerPolicy.select(eligible, emptyMap(), emptyMap(), emptyList())
    assertEquals(AgenticPlannerPolicy.candidateLimit, withoutHistory.size)
    listOf(eligible.take(2), eligible.takeLast(2)).forEach { completed ->
      val history =
        completed.mapIndexed { index, candidate ->
          CalendarFact(
            candidate.getValue("exerciseId") as String,
            index.toLong(),
            UUID(0, 100L + index).toString(),
            UUID(0, 200L + index).toString(),
            0,
            true,
            null,
            null,
          )
        }
      assertEquals(
        withoutHistory,
        AgenticPlannerPolicy.select(eligible.reversed(), emptyMap(), emptyMap(), history),
      )
    }
  }

  @Test
  fun `only curated canonical IDs are default candidates and never remains absolute`() {
    val sources =
      mapOf(
        standard to CalendarCandidateSource(standard, json.readTree("{}"), curatedCanonical = true),
        custom to CalendarCandidateSource(custom, json.readTree("{\"isCustom\":true}")),
        explicit to
          CalendarCandidateSource(
            explicit,
            json.readTree("{\"movementFamily\":\"PLYOMETRIC\"}"),
            curatedCanonical = true,
          ),
      )
    val selected =
      AgenticPlannerPolicy.select(
        listOf(row(standard), row(custom), row(explicit)),
        emptyMap(),
        mapOf(custom to "MORE", explicit to "NEVER"),
        emptyList(),
        sources,
      )
    assertEquals(listOf(custom, standard), selected.map { it.getValue("exerciseId") })
  }

  @Test
  fun `soft preferences retain custom and explicit alternatives while never remains absolute`() {
    val sources =
      mapOf(
        custom to CalendarCandidateSource(custom, json.readTree("{\"isCustom\":true}")),
        explicit to
          CalendarCandidateSource(
            explicit,
            json.readTree("{\"plannerCategory\":\"EXPLICIT_ONLY\"}"),
            curatedCanonical = true,
          ),
      )
    assertEquals(
      listOf(explicit, custom),
      AgenticPlannerPolicy.select(
          listOf(row(custom), row(explicit)),
          emptyMap(),
          mapOf(custom to "LESS"),
          emptyList(),
          sources,
        )
        .map { it.getValue("exerciseId") },
    )
    val history =
      listOf(
        CalendarFact(
          explicit,
          1,
          UUID(0, 9).toString(),
          UUID(0, 10).toString(),
          0,
          false,
          null,
          null,
        )
      )
    assertEquals(
      listOf(explicit, custom),
      AgenticPlannerPolicy.select(
          listOf(row(custom), row(explicit)),
          emptyMap(),
          mapOf(custom to "LESS"),
          history,
          sources,
        )
        .map { it.getValue("exerciseId") },
    )
  }

  @Test
  fun `less preference is a soft ordering hint rather than a skeleton exclusion`() {
    val fallback = UUID(0, 4).toString()
    val sources =
      mapOf(
        standard to CalendarCandidateSource(standard, json.readTree("{}"), curatedCanonical = true),
        fallback to CalendarCandidateSource(fallback, json.readTree("{\"isCustom\":true}")),
      )
    assertEquals(
      listOf(standard, fallback),
      AgenticPlannerPolicy.select(
          listOf(row(standard), row(fallback)),
          emptyMap(),
          mapOf(fallback to "LESS"),
          listOf(
            CalendarFact(
              fallback,
              1,
              UUID(0, 9).toString(),
              UUID(0, 10).toString(),
              0,
              false,
              null,
              null,
            )
          ),
          sources,
        )
        .map { it.getValue("exerciseId") },
    )
    assertEquals(
      listOf(fallback),
      AgenticPlannerPolicy.select(
          listOf(row(fallback)),
          emptyMap(),
          mapOf(fallback to "LESS"),
          listOf(
            CalendarFact(
              fallback,
              1,
              UUID(0, 9).toString(),
              UUID(0, 10).toString(),
              0,
              false,
              null,
              null,
            )
          ),
          sources,
        )
        .map { it.getValue("exerciseId") },
    )
  }
}
