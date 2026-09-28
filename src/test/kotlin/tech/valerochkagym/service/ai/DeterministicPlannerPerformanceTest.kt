package tech.valerochkagym.service.ai

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** Reproducible core-only fixture. Wall time includes enumeration/scoring, not Spring or SQL. */
class DeterministicPlannerPerformanceTest {
  @Test
  fun `representative mixed workload reports measured p95 below one second`() {
    val json = JsonMapper.builder().build()
    val fixture =
      javaClass
        .getResourceAsStream("/deterministic-planner-performance-v2.json")!!
        .use(json::readTree)
    val families = fixture["families"].toList().map { it.asString() }
    val candidates =
      families.flatMap { family ->
        (0 until fixture["candidatesPerFamily"].asInt()).map { index ->
          DeterministicCandidate(
            "$family-${index.toString().padStart(3, '0')}",
            type(family),
            family,
            emptySet(),
            if (index % 5 == 0) "MORE" else "NORMAL",
            16 - index,
            index == 0,
            index % 3 == 0,
          )
        }
      }
    val structures =
      (0 until fixture["structures"].asInt()).map { index ->
        DeterministicStructure(
          "structure-${index.toString().padStart(2, '0')}",
          families.map { family ->
            DeterministicSlot(
              "$index-$family",
              "$index-$family-1",
              family,
              setOf(type(family)),
              emptySet(),
              if (family == "CARDIO") 1 else 3,
              if (family == "CARDIO") 1 else 3,
              6,
              12,
              if (family == "CARDIO") listOf(300, 600, 900)
              else if (family == "CORE") listOf(30, 45, 60) else emptyList(),
              listOf(60, 90, 120),
              if (family == "CARDIO") 1 else 3,
              90,
              if (type(family) == PlannerExerciseType.STRENGTH) 27 else null,
              if (type(family) == PlannerExerciseType.STRENGTH) 7000 else null,
              if (family == "CARDIO") 600 else if (family == "CORE") 135 else null,
            )
          },
        )
      }
    val engine = DeterministicPlannerEngine()
    fun execute() = engine.plan(structures, candidates, fixture["maximumDurationSeconds"].asLong())
    repeat(fixture["warmupIterations"].asInt()) { execute() }
    val samples =
      (0 until fixture["measuredIterations"].asInt())
        .map {
          val start = System.nanoTime()
          val result = execute()
          val elapsed = (System.nanoTime() - start) / 1_000_000.0
          assertTrue(
            result is DeterministicPlannerResult.Ready,
            "Representative fixture must produce a complete plan: $result",
          )
          elapsed
        }
        .sorted()
    val p95 = samples[ceil(samples.size * 0.95).toInt() - 1]
    val report =
      mapOf(
        "fixtureVersion" to fixture["fixtureVersion"].asInt(),
        "samplesMillis" to samples,
        "p95Millis" to p95,
        "targetP95Millis" to fixture["targetP95Millis"].asInt(),
        "javaVersion" to System.getProperty("java.version"),
        "osName" to System.getProperty("os.name"),
        "osArch" to System.getProperty("os.arch"),
        "processors" to Runtime.getRuntime().availableProcessors(),
        "scope" to "core enumeration and scoring; no SQL, Spring, provider or network",
      )
    val path = Path.of("build/reports/deterministic-planner-performance.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, json.writeValueAsString(report))
    println("Deterministic planner measured core p95: $p95 ms; fixture=$path")
    assertTrue(p95 < fixture["targetP95Millis"].asDouble(), "Measured p95 is $p95 ms")
  }

  private fun type(family: String) =
    when (family) {
      "CARDIO" -> PlannerExerciseType.CARDIO
      "CORE" -> PlannerExerciseType.TIMED
      else -> PlannerExerciseType.STRENGTH
    }
}
