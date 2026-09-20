package tech.valerochkagym.config

import java.net.URI
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tech.valerochkagym.service.ai.*
import tools.jackson.databind.ObjectMapper

// Intentionally not a data class: a diagnostic toString must never expose credentials.
class AiProviderSettings(
  val endpoint: URI,
  val key: String,
  val textModel: String,
  val visionModel: String,
) {
  companion object {
    fun from(values: (String) -> String?): AiProviderSettings? {
      if (values("AI_ENABLED") != "true" || values("AI_PROVIDER") != "openai") return null
      val raw = values("AI_BASE_URL") ?: return null
      val uri =
        try {
          URI(raw)
        } catch (_: Exception) {
          return null
        }
      if (
        uri.scheme != "https" ||
          uri.host.isNullOrBlank() ||
          uri.userInfo != null ||
          uri.query != null ||
          uri.fragment != null ||
          uri.path !in setOf("", "/", "/v1", "/v1/")
      )
        return null
      val secrets =
        listOf("AI_API_KEY", "AI_TEXT_MODEL", "AI_VISION_MODEL").map { values(it).orEmpty() }
      if (secrets.any { it.isBlank() || it.any { c -> c.code < 32 || c.code == 127 } }) return null
      return AiProviderSettings(
        URI("https", null, uri.host, uri.port, "/v1/chat/completions", null, null),
        secrets[0],
        secrets[1],
        secrets[2],
      )
    }
  }
}

@Configuration
class AiConfiguration {
  private val client =
    java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5))
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
      .build()

  @Bean
  fun aiProvider(
    settings: AiSettingsService,
    json: ObjectMapper,
    diagnostics: AiDiagnostics,
  ): AiProvider =
    object : PlannerToolCallingProvider {
      override val available
        get() = settings.current() != null

      override fun generate(input: AiProviderInput) =
        settings.current()?.let {
          HttpOpenAiChatCompletionsProvider(it.provider, json, client, diagnostics = diagnostics)
            .generate(input)
        }
          ?: run {
            diagnostics.recordFailure(AiDiagnosticFailureCategory.PROVIDER_UNCONFIGURED)
            throw aiError("ai_unavailable")
          }

      override fun generatePlannerTurn(input: AiProviderInput) =
        settings.current()?.let {
          HttpOpenAiChatCompletionsProvider(it.provider, json, client, diagnostics = diagnostics)
            .generatePlannerTurn(input)
        }
          ?: run {
            diagnostics.recordFailure(AiDiagnosticFailureCategory.PROVIDER_UNCONFIGURED)
            throw aiError("ai_unavailable")
          }
    }
}

// Not a data class: provider credentials must not be exposed by a generated toString.
class CoachProviderSettings(
  val provider: AiProviderSettings,
  val defaultModel: String,
  val models: List<String>,
) {
  companion object {
    fun from(values: (String) -> String?): CoachProviderSettings? {
      val provider = AiProviderSettings.from(values) ?: return null
      val default = values("AI_COACH_MODEL")?.takeIf { it.isNotBlank() } ?: provider.textModel
      val models =
        (listOf(default) +
            values("AI_COACH_MODELS")
              .orEmpty()
              .split(',')
              .map { it.trim() }
              .filter { it.isNotEmpty() })
          .distinct()
      if (
        models.size > 20 ||
          models.any { it.length > 200 || it.any { char -> char.code < 32 || char.code == 127 } }
      )
        return null
      return CoachProviderSettings(provider, default, models)
    }
  }
}

@Configuration
class CoachAiConfiguration {
  private val client =
    java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5))
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
      .build()

  @Bean
  fun coachTurnProvider(settings: AiSettingsService, json: ObjectMapper): CoachTurnProvider =
    object : CoachTurnProvider {
      override fun catalog() =
        settings.current()?.let { CoachModelCatalog("AVAILABLE", it.defaultModel, it.models) }
          ?: UnconfiguredCoachTurnProvider().catalog()

      override fun complete(input: CoachTurnInput) =
        settings.current()?.let { HttpCoachTurnProvider(it, json, client).complete(input) }
          ?: throw aiError("ai_unavailable")

      override fun stream(input: CoachTurnInput, delta: (String) -> Unit) =
        settings.current()?.let { HttpCoachTurnProvider(it, json, client).stream(input, delta) }
          ?: throw aiError("ai_unavailable")
    }
}
