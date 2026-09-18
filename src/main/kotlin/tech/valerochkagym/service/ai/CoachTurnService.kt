package tech.valerochkagym.service.ai

import java.util.UUID
import java.util.concurrent.Semaphore
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.bad
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class CoachModelCatalog(
  val availability: String,
  val defaultModel: String?,
  val models: List<String>,
)

data class CoachTurnInput(
  val requestId: String,
  val model: String,
  val messages: JsonNode,
  val tools: JsonNode,
)

data class CoachTurnResponse(val requestId: String, val model: String, val completion: JsonNode)

interface CoachTurnProvider {
  fun catalog(): CoachModelCatalog

  fun complete(input: CoachTurnInput): JsonNode

  fun stream(input: CoachTurnInput, delta: (String) -> Unit): JsonNode = complete(input)
}

class UnconfiguredCoachTurnProvider : CoachTurnProvider {
  override fun catalog() = CoachModelCatalog("UNCONFIGURED", null, emptyList())

  override fun complete(input: CoachTurnInput): JsonNode = throw aiError("ai_unavailable")
}

/** A stateless authenticated model exchange. It cannot read or mutate workout/account records. */
@Service
class CoachTurnService(private val provider: CoachTurnProvider, private val json: ObjectMapper) {
  private val permits = Semaphore(2)

  fun catalog() = provider.catalog()

  fun turn(raw: ByteArray): CoachTurnResponse {
    val input = parse(raw)
    if (!permits.tryAcquire()) throw aiError("ai_busy")
    try {
      val completion = CoachCompletionSanitizer.sanitize(provider.complete(input), json)
      if (Thread.currentThread().isInterrupted) throw aiError("ai_timeout")
      return CoachTurnResponse(input.requestId, input.model, completion)
    } finally {
      permits.release()
    }
  }

  fun prepareStream(raw: ByteArray): StreamTurn {
    val input = parse(raw)
    if (!permits.tryAcquire()) throw aiError("ai_busy")
    return StreamTurn(input)
  }

  inner class StreamTurn(val input: CoachTurnInput) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    fun run(delta: (String) -> Unit): CoachTurnResponse =
      CoachTurnResponse(
        input.requestId,
        input.model,
        CoachCompletionSanitizer.sanitize(provider.stream(input, delta), json),
      )

    override fun close() {
      if (closed.compareAndSet(false, true)) permits.release()
    }
  }

  internal fun parse(raw: ByteArray): CoachTurnInput {
    if (raw.size > MAX_REQUEST_BYTES)
      throw tech.valerochkagym.controller.advice.ApiException(
        413,
        "payload_too_large",
        "Запрос тренера слишком большой",
      )
    val root =
      try {
        json
          .tokenStreamFactory()
          .rebuild()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build()
          .createParser(raw)
          .use { parser ->
            json.readTree(parser).also {
              if (parser.nextToken() != null) bad("Некорректный запрос тренера")
            }
          }
      } catch (_: Exception) {
        bad("Некорректный запрос тренера")
      }
    keys(
      root,
      setOf("requestId", "model", "messages", "tools"),
      setOf("requestId", "messages", "tools"),
    )
    val id = string(root["requestId"], 36)
    if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not())
      bad("Некорректный идентификатор запроса")
    val catalog = provider.catalog()
    if (catalog.availability != "AVAILABLE") throw aiError("ai_unavailable")
    val model =
      root["model"]?.takeUnless { it.isNull }?.let { string(it, 200) }
        ?: catalog.defaultModel
        ?: throw aiError("ai_unavailable")
    if (model !in catalog.models) bad("Выберите доступную модель тренера")
    val tools = root["tools"]
    if (tools?.isArray != true || tools.size() != TOOL_NAMES.size)
      bad("Некорректные инструменты тренера")
    val names =
      (0 until tools.size()).map { index ->
        val tool = tools[index]
        keys(tool, setOf("type", "function"))
        if (string(tool["type"], 20) != "function") bad("Некорректный инструмент")
        val function = tool["function"]
        keys(function, setOf("name", "description", "parameters"))
        string(function["description"], 4000)
        if (
          function["parameters"]?.isObject != true ||
            function["parameters"].toString().length > 32000
        )
          bad("Некорректная схема инструмента")
        string(function["name"], 100)
      }
    if (names.toSet() != TOOL_NAMES || names.distinct().size != names.size)
      bad("Некорректные инструменты тренера")
    val messages = root["messages"]
    if (messages?.isArray != true || messages.size() !in 1..80)
      bad("Некорректные сообщения тренера")
    val pending = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    messages.forEach { message ->
      keys(message, setOf("role", "content", "tool_calls", "tool_call_id"), setOf("role"))
      val role = string(message["role"], 20)
      if (role !in setOf("system", "user", "assistant", "tool")) bad("Некорректная роль сообщения")
      if (role != "tool" && pending.isNotEmpty()) bad("Нет результата инструмента")
      val content = message["content"]?.takeUnless { it.isNull }
      if (content != null)
        string(
          content,
          if (role == "tool") 96000 else if (role == "user") 4000 else 16000,
          allowEmpty = true,
        )
      val calls = message["tool_calls"]?.takeUnless { it.isNull }
      val callId = message["tool_call_id"]?.takeUnless { it.isNull }
      if (role == "tool") {
        if (calls != null || content == null || !pending.remove(string(callId, 200)))
          bad("Некорректный результат инструмента")
      } else if (callId != null) bad("Некорректная ссылка на инструмент")
      if (calls != null) {
        if (role != "assistant" || !calls.isArray || calls.size() !in 1..16)
          bad("Некорректные вызовы инструментов")
        calls.forEach { call ->
          validateCall(call)
          val callKey = string(call["id"], 200)
          if (!seen.add(callKey) || seen.size > 16) bad("Повторный вызов инструмента")
          pending.add(callKey)
        }
      } else if (content == null) bad("Пустое сообщение")
    }
    if (pending.isNotEmpty() || messages.last()["role"].asString() !in setOf("user", "tool"))
      bad("Незавершённый диалог инструментов")
    return CoachTurnInput(id, model, messages, tools)
  }

  companion object {
    const val MAX_REQUEST_BYTES = 512 * 1024
    val TOOL_NAMES =
      setOf("get_workout_state", "find_exercises", "get_exercise_history", "submit_workout_changes")

    internal fun keys(value: JsonNode?, allowed: Set<String>, required: Set<String> = allowed) {
      if (value?.isObject != true) bad("Некорректный запрос тренера")
      val actual = value.properties().map { it.key }.toSet()
      if (!allowed.containsAll(actual) || !actual.containsAll(required))
        bad("Неизвестные поля запроса тренера")
    }

    internal fun string(value: JsonNode?, max: Int, allowEmpty: Boolean = false): String {
      if (value?.isString != true) bad("Некорректное текстовое поле")
      val result = value.asString()
      if (result.length > max || (!allowEmpty && result.isBlank())) bad("Некорректная длина поля")
      return result
    }

    internal fun validateCall(call: JsonNode) {
      keys(call, setOf("id", "type", "function"))
      string(call["id"], 200)
      if (string(call["type"], 20) != "function") bad("Некорректный тип инструмента")
      keys(call["function"], setOf("name", "arguments"))
      if (string(call["function"]["name"], 100) !in TOOL_NAMES) bad("Неизвестный инструмент")
      string(call["function"]["arguments"], 32000)
    }
  }
}
