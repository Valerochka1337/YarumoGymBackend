package tech.valerochkagym.service.ai

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.valerochkagym.config.CoachProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad

@Component
class AiKeyEncryption(env: Environment) {
  private val key =
    env
      .getProperty("AI_SETTINGS_ENCRYPTION_KEY")
      ?.takeIf { it.isNotBlank() }
      ?.let {
        val bytes =
          try {
            Base64.getDecoder().decode(it)
          } catch (_: Exception) {
            error("AI_SETTINGS_ENCRYPTION_KEY must be base64 of 32 random bytes")
          }
        require(bytes.size == 32) { "AI_SETTINGS_ENCRYPTION_KEY must be base64 of 32 random bytes" }
        SecretKeySpec(bytes, "AES")
      }
  val available
    get() = key != null

  private fun cipher(mode: Int, nonce: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(
        mode,
        key
          ?: throw ApiException(
            503,
            "ai_encryption_unavailable",
            "На сервере не задан ключ шифрования ИИ",
          ),
        GCMParameterSpec(128, nonce),
      )
      updateAAD("yarumo/ai-settings/api-key/v1".toByteArray())
    }

  fun encrypt(value: String): String {
    val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
    return "v1:" +
      Base64.getEncoder()
        .encodeToString(
          nonce + cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(value.toByteArray(Charsets.UTF_8))
        )
  }

  fun decrypt(value: String): String =
    try {
      require(value.startsWith("v1:"))
      val bytes = Base64.getDecoder().decode(value.removePrefix("v1:"))
      require(bytes.size >= 28)
      cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, 12))
        .doFinal(bytes.copyOfRange(12, bytes.size))
        .toString(Charsets.UTF_8)
    } catch (_: Exception) {
      throw aiError("ai_unavailable")
    }
}

// No generated toString: the write-only credential must never appear in diagnostics.
class AiSettingsEdit(
  val revision: Long,
  val enabled: Boolean,
  val baseUrl: String,
  val textModel: String,
  val visionModel: String,
  val coachModel: String,
  val coachModels: List<String>,
  val apiKey: String? = null,
  val clearApiKey: Boolean = false,
  val coachPrompt: String? = null,
)

data class AiSettingsView(
  val revision: Long,
  val enabled: Boolean,
  val baseUrl: String,
  val textModel: String,
  val visionModel: String,
  val coachModel: String,
  val coachModels: List<String>,
  val hasApiKey: Boolean,
  val encryptionAvailable: Boolean,
  val coachPrompt: String,
)

@Service
class AiSettingsService(private val jdbc: JdbcTemplate, private val encryption: AiKeyEncryption) {
  private fun row() = jdbc.queryForMap("SELECT * FROM ai_settings WHERE id = 1")

  private fun view(r: Map<String, Any?>) =
    AiSettingsView(
      (r["revision"] as Number).toLong(),
      r["enabled"] as Boolean,
      r["base_url"] as String,
      r["text_model"] as String,
      r["vision_model"] as String,
      r["coach_model"] as String,
      (r["coach_models"] as String).split(',').filter(String::isNotBlank),
      r["encrypted_api_key"] != null,
      encryption.available,
      r["coach_prompt"] as String,
    )

  fun get() = view(row())

  @Transactional
  fun save(body: AiSettingsEdit, actor: UUID?): AiSettingsView {
    val r = row()
    if (
      body.coachPrompt != null &&
        (body.coachPrompt.isBlank() ||
          body.coachPrompt.length > 16000 ||
          '\u0000' in body.coachPrompt)
    )
      bad("Промпт тренера должен содержать от 1 до 16000 символов")
    val key = body.apiKey?.takeIf { it.isNotEmpty() }
    if (key != null && body.clearApiKey) bad("Нельзя одновременно заменить и удалить ключ")
    fun valid(value: String, max: Int) =
      value.length <= max && value.none { it.code < 32 || it.code == 127 }
    if (
      !valid(body.baseUrl, 2048) ||
        !valid(body.textModel, 200) ||
        !valid(body.visionModel, 200) ||
        !valid(body.coachModel, 200) ||
        body.coachModels.size > 20 ||
        body.coachModels.any { !valid(it, 200) || it.isBlank() || ',' in it } ||
        (key != null && (!valid(key, 16384) || key.isBlank()))
    )
      bad("Проверьте URL, ключ и названия моделей")
    // Validate the endpoint even when disabled, using placeholders only for missing models/key.
    val values =
      mapOf(
        "AI_ENABLED" to "true",
        "AI_PROVIDER" to "openai",
        "AI_BASE_URL" to body.baseUrl,
        "AI_API_KEY" to "validation",
        "AI_TEXT_MODEL" to body.textModel.ifBlank { "validation" },
        "AI_VISION_MODEL" to body.visionModel.ifBlank { "validation" },
        "AI_COACH_MODEL" to body.coachModel,
        "AI_COACH_MODELS" to body.coachModels.joinToString(","),
      )
    if (CoachProviderSettings.from(values::get) == null)
      bad("Нужен HTTPS URL провайдера и не более 20 моделей тренера")
    val encrypted =
      when {
        body.clearApiKey -> null
        key != null -> encryption.encrypt(key)
        else -> r["encrypted_api_key"] as String?
      }
    if (
      body.enabled && (encrypted == null || body.textModel.isBlank() || body.visionModel.isBlank())
    )
      bad("Для включения ИИ укажите API-ключ и модели текста и изображений")
    if (body.enabled) encryption.decrypt(encrypted!!)
    val changed =
      jdbc.update(
        """UPDATE ai_settings SET revision = revision + 1, enabled = ?, base_url = ?, encrypted_api_key = ?,
      text_model = ?, vision_model = ?, coach_model = ?, coach_models = ?, coach_prompt = ?, updated_at = now(), updated_by = ? WHERE id = 1 AND revision = ?""",
        body.enabled,
        body.baseUrl,
        encrypted,
        body.textModel,
        body.visionModel,
        body.coachModel,
        body.coachModels.distinct().joinToString(","),
        body.coachPrompt ?: r["coach_prompt"] as String,
        actor,
        body.revision,
      )
    if (changed != 1)
      throw ApiException(
        409,
        "ai_settings_conflict",
        "Настройки уже изменены. Обновите страницу и повторите изменения",
      )
    return get()
  }

  fun current(): CoachProviderSettings? {
    val r = row()
    if (r["enabled"] != true) return null
    val encrypted = r["encrypted_api_key"] as String? ?: return null
    val key =
      try {
        encryption.decrypt(encrypted)
      } catch (_: ApiException) {
        return null
      }
    val values =
      mapOf(
        "AI_ENABLED" to "true",
        "AI_PROVIDER" to "openai",
        "AI_BASE_URL" to r["base_url"] as String,
        "AI_API_KEY" to key,
        "AI_TEXT_MODEL" to r["text_model"] as String,
        "AI_VISION_MODEL" to r["vision_model"] as String,
        "AI_COACH_MODEL" to r["coach_model"] as String,
        "AI_COACH_MODELS" to r["coach_models"] as String,
      )
    return CoachProviderSettings.from(values::get)
  }
}

/** One-time migration; once edited, database settings always take precedence. */
@Component
class LegacyAiSettingsImport(
  private val env: Environment,
  private val settings: AiSettingsService,
  private val encryption: AiKeyEncryption,
) : org.springframework.boot.ApplicationRunner {
  override fun run(args: org.springframework.boot.ApplicationArguments) {
    if (settings.get().revision != 0L) return
    val legacy = CoachProviderSettings.from(env::getProperty) ?: return
    check(encryption.available) {
      "Set AI_SETTINGS_ENCRYPTION_KEY to migrate existing AI credentials into the database"
    }
    try {
      settings.save(
        AiSettingsEdit(
          0,
          true,
          env.getRequiredProperty("AI_BASE_URL"),
          legacy.provider.textModel,
          legacy.provider.visionModel,
          legacy.defaultModel,
          legacy.models,
          legacy.provider.key,
        ),
        null,
      )
    } catch (e: ApiException) {
      // Another application instance may have completed the same migration.
      if (e.code != "ai_settings_conflict") throw e
    }
  }
}
