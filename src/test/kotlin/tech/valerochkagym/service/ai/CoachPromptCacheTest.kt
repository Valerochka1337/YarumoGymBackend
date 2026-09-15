package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoachPromptCacheTest {
  @Test
  fun `prompt refreshes after five minutes and preserves formatting`() {
    var now = 0L
    var stored = "  First\nsecond  "
    var reads = 0
    val cache =
      CoachPromptCache({ now }) {
        reads++
        stored
      }
    assertEquals(stored, cache.get())
    stored = "Changed"
    now = 299_999_999_999L
    assertEquals("  First\nsecond  ", cache.get())
    assertEquals(1, reads)
    now++
    assertEquals("Changed", cache.get())
    assertEquals(2, reads)
  }

  @Test
  fun `failed load is retried and never cached`() {
    var fail = true
    val cache = CoachPromptCache { if (fail) error("Database unavailable") else "Recovered" }
    assertThrows(IllegalStateException::class.java) { cache.get() }
    fail = false
    assertEquals("Recovered", cache.get())
  }
}
