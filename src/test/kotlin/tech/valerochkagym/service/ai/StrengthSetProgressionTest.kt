package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StrengthSetProgressionTest {
  @Test
  fun `configured range produces descending two rep working sets`() {
    assertEquals(listOf(12, 10, 8), StrengthSetProgression.descend(listOf(8, 8, 8), 6..12))
  }

  @Test
  fun `narrow range falls back to one rep descent`() {
    assertEquals(listOf(8, 7, 6), StrengthSetProgression.descend(listOf(6, 6, 6), 6..8))
  }

  @Test
  fun `missing range still makes valid descending repetitions`() {
    assertEquals(listOf(3, 2, 1), StrengthSetProgression.descend(listOf(1, 1, 1)))
  }

  @Test
  fun `valid descending ladder remains unchanged outside preferred bounds`() {
    assertEquals(listOf(20, 18, 16), StrengthSetProgression.descend(listOf(20, 18, 16), 6..12))
  }

  @Test
  fun `insufficient configured range retains supplied repetitions`() {
    assertEquals(listOf(8, 8, 8), StrengthSetProgression.descend(listOf(8, 8, 8), 7..8))
  }
}
