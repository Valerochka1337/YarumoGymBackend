package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlannerWeightEngineTest {
  private val now = 100_000L

  private fun fact(
    weight: Double = 100.0,
    reps: Double = 5.0,
    workout: String = "latest",
    time: Long = now - 1,
  ) =
    CalendarFact(
      "squat",
      time,
      workout,
      "section",
      0,
      true,
      weight,
      null,
      mapOf("reps" to reps),
      emptyList(),
      "WORK",
      workoutFinishedAtMillis = time,
    )

  private fun calculate(rows: List<CalendarFact>, reps: Int = 5, step: Double = 2.5) =
    PlannerWeightEngine.calculate(rows, "squat", reps, now, step)

  @Test
  fun `Streprogen intensity changes reps and rounds down to configured increments`() {
    assertEquals(100.0, calculate(listOf(fact())))
    assertEquals(87.5, calculate(listOf(fact()), reps = 8))
    assertEquals(107.5, calculate(listOf(fact()), reps = 3))
    assertEquals(107.0, calculate(listOf(fact()), reps = 3, step = 1.0))
  }

  @Test
  fun `latest usable workout median is used instead of old personal record`() {
    val rows =
      listOf(
        fact(200.0, workout = "old", time = now - 500),
        fact(100.0),
        fact(120.0).copy(setIndex = 1),
      )
    assertEquals(110.0, calculate(rows))
  }

  @Test
  fun `missing incompatible warmup legacy and future results do not invent weights`() {
    assertNull(calculate(emptyList()))
    val invalid =
      listOf(
        fact().copy(exerciseId = "bench"),
        fact().copy(setType = "WARMUP"),
        fact().copy(actualWeightPresent = false, actualWeightKg = null, legacyWeightKg = 100.0),
        fact().copy(legacyFields = listOf("reps")),
        fact().copy(results = emptyMap()),
        fact(reps = 5.5),
        fact(reps = 0.0),
        fact(reps = Double.NaN),
        fact(weight = 0.0),
        fact(weight = Double.POSITIVE_INFINITY),
        fact(time = now + 1),
        fact().copy(workoutFinishedAtMillis = now + 1),
      )
    invalid.forEach { assertNull(calculate(listOf(it)), it.toString()) }
  }

  @Test
  fun `invalid target reps steps and rounding below first increment return unknown`() {
    for (reps in listOf(0, 31)) assertNull(calculate(listOf(fact()), reps = reps))
    for (step in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(
      calculate(listOf(fact()), step = step)
    )
    assertNull(calculate(listOf(fact(weight = 1.0)), step = 2.5))
  }
}
