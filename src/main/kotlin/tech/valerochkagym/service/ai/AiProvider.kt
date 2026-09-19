package tech.valerochkagym.service.ai

import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode

data class AiProviderInput(
  val vision: Boolean,
  val instruction: String,
  val context: String,
  val schema: JsonNode,
  val imageBase64: String? = null,
  val schemaName: String? = null,
  val timeoutMillis: Long? = null,
  /**
   * Private planner conversation only. It is translated to OpenAI tool messages by the provider.
   */
  val plannerTranscript: List<PlannerToolExchange> = emptyList(),
  /** Server-owned model captured when planning starts; never provided by the Android caller. */
  val model: String? = null,
)

interface AiProvider {
  val available: Boolean

  fun generate(input: AiProviderInput): JsonNode
}

/** Optional v2-only private planner transport. It is never exposed as a client or MCP protocol. */
interface PlannerToolCallingProvider : AiProvider {
  fun generatePlannerTurn(input: AiProviderInput): PlannerTurn
}

data class PlannerTurn(
  val calls: List<PlannerToolProtocol.Call> = emptyList(),
  val final: JsonNode? = null,
)

data class PlannerToolExchange(val call: PlannerToolProtocol.Call, val result: ByteArray)

class UnconfiguredAiProvider : AiProvider {
  override val available = false

  override fun generate(input: AiProviderInput): JsonNode = throw aiError("ai_unavailable")
}

fun aiError(code: String): ApiException =
  when (code) {
    "ai_request_conflict" -> ApiException(409, code, "Этот идентификатор запроса уже использован")
    "ai_in_progress" -> ApiException(409, code, "Запрос AI ещё выполняется")
    "ai_interrupted" -> ApiException(409, code, "Предыдущая попытка была прервана")
    "ai_context_stale" -> ApiException(409, code, "Данные изменились. Синхронизируйте и повторите")
    "ai_context_too_large" -> ApiException(409, code, "Каталог слишком большой для обработки")
    "ai_invalid_response" ->
      ApiException(502, code, "Не удалось проверить ответ AI. Попробуйте ещё раз")
    "ai_timeout" -> ApiException(504, code, "AI не ответил вовремя. Попробуйте ещё раз")
    "ai_busy" -> ApiException(503, code, "AI занят. Попробуйте позже")
    else -> ApiException(503, "ai_unavailable", "AI временно недоступен. Можно продолжить вручную")
  }
