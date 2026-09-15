package tech.valerochkagym.service.ai

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class CoachPromptResponse(val prompt: String)

/** Each server instance refreshes the shared database value at most every five minutes. */
@Service
class CoachPromptService(jdbc: JdbcTemplate) {
  private val cache = CoachPromptCache {
    jdbc.queryForObject("SELECT coach_prompt FROM ai_settings WHERE id = 1", String::class.java)!!
  }

  fun get() = CoachPromptResponse(cache.get())
}

internal class CoachPromptCache(
  private val nanoTime: () -> Long = System::nanoTime,
  private val load: () -> String,
) {
  private var value: String? = null
  private var loadedAt = 0L

  @Synchronized
  fun get(): String {
    val now = nanoTime()
    val current = value
    if (current != null && now - loadedAt < 300_000_000_000L) return current
    val fresh = load()
    check(fresh.isNotBlank() && fresh.length <= 16000) { "Invalid coach prompt" }
    value = fresh
    loadedAt = nanoTime()
    return fresh
  }
}
