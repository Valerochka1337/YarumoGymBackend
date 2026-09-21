package tech.valerochkagym.service.ai

import java.io.ByteArrayOutputStream
import java.net.http.*
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.*
import tech.valerochkagym.config.AiProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class HttpOpenAiChatCompletionsProvider(
  private val settings: AiProviderSettings,
  private val json: ObjectMapper,
  private val client: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build(),
  private val deadlineMillis: Long = 45000,
  private val diagnostics: AiDiagnostics = AiDiagnostics(),
) : PlannerToolCallingProvider {
  override val available = true

  override fun generate(input: AiProviderInput): JsonNode =
    diagnostics.observe(AiDiagnosticStage.PROVIDER_HTTP, input.model ?: settings.textModel) {
      generateHttp(input)
    }

  private fun generateHttp(input: AiProviderInput): JsonNode {
    val requestDeadlineMillis = minOf(deadlineMillis, input.timeoutMillis ?: deadlineMillis)
    if (requestDeadlineMillis <= 0) throw aiError("ai_timeout")
    var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
    try {
      val parts = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to input.context))
      input.imageBase64?.let {
        parts.add(
          mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$it"))
        )
      }
      val body =
        mapOf(
          "model" to
            (input.model ?: if (input.vision) settings.visionModel else settings.textModel),
          "store" to false,
          "stream" to false,
          "n" to 1,
          "max_completion_tokens" to 2048,
          "messages" to
            listOf(
              mapOf(
                "role" to "system",
                "content" to listOf(mapOf("type" to "text", "text" to input.instruction)),
              ),
              mapOf("role" to "user", "content" to parts),
            ),
          "response_format" to
            mapOf(
              "type" to "json_schema",
              "json_schema" to
                mapOf(
                  "name" to
                    (input.schemaName ?: if (input.vision) "inbody_draft" else "exercise_draft"),
                  "strict" to true,
                  "schema" to input.schema,
                ),
            ),
        )
      val request =
        HttpRequest.newBuilder(settings.endpoint)
          .timeout(Duration.ofMillis(requestDeadlineMillis))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer ${settings.key}")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build()
      pending = client.sendAsync(request) { BoundedAiBodySubscriber(256 * 1024) }
      val response = pending.get(requestDeadlineMillis, TimeUnit.MILLISECONDS)
      if (response.statusCode() != 200) {
        recordNonSuccess(response.statusCode(), response.body())
        throw aiError("ai_unavailable")
      }
      val root = json.readTree(response.body())
      val choices = root["choices"]
      if (choices?.isArray != true || choices.size() != 1) throw aiError("ai_invalid_response")
      val choice = choices[0]
      val message = choice["message"]
      if (
        choice["finish_reason"]?.asString() != "stop" ||
          message?.get("role")?.asString() != "assistant" ||
          message["refusal"]?.let { !it.isNull } == true ||
          message["tool_calls"]?.let { !it.isNull } == true ||
          message["content"]?.isString != true
      )
        throw aiError("ai_invalid_response")
      return json.readTree(message["content"].asString())
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw aiError("ai_timeout")
    } catch (e: TimeoutException) {
      throw aiError("ai_timeout")
    } catch (e: ApiException) {
      throw e
    } catch (e: Exception) {
      val cause = e.cause ?: e
      if (cause is HttpTimeoutException) throw aiError("ai_timeout")
      if (cause is ApiException) throw cause
      throw aiError(
        if (e is ExecutionException || e is java.io.IOException) "ai_unavailable"
        else "ai_invalid_response"
      )
    } finally {
      pending?.takeUnless { it.isDone }?.cancel(true)
    }
  }

  /**
   * Native OpenAI-compatible tool framing for the private planner only. The transcript contains the
   * already bounded/redacted candidate/history context; no account, credential, health or
   * unconsented note data is introduced by these messages.
   */
  override fun generatePlannerTurn(input: AiProviderInput): PlannerTurn =
    diagnostics.observe(AiDiagnosticStage.PROVIDER_HTTP, input.model ?: settings.textModel) {
      generatePlannerTurnHttp(input)
    }

  private fun generatePlannerTurnHttp(input: AiProviderInput): PlannerTurn {
    val requestDeadlineMillis = minOf(deadlineMillis, input.timeoutMillis ?: deadlineMillis)
    if (requestDeadlineMillis <= 0) throw aiError("ai_timeout")
    var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
    try {
      val messages =
        mutableListOf<Map<String, Any>>(
          mapOf("role" to "system", "content" to input.instruction),
          mapOf("role" to "user", "content" to input.context),
        )
      input.plannerTranscript.forEach { exchange ->
        messages +=
          mapOf(
            "role" to "assistant",
            "content" to "",
            "tool_calls" to
              listOf(
                mapOf(
                  "id" to exchange.call.id,
                  "type" to "function",
                  "function" to
                    mapOf(
                      "name" to exchange.call.name,
                      "arguments" to exchange.call.bytes.toString(Charsets.UTF_8),
                    ),
                )
              ),
          )
        messages +=
          mapOf(
            "role" to "tool",
            "tool_call_id" to exchange.call.id,
            "content" to exchange.result.toString(Charsets.UTF_8),
          )
      }
      val body =
        mapOf(
          "model" to (input.model ?: settings.textModel),
          "store" to false,
          "stream" to false,
          "n" to 1,
          "max_completion_tokens" to 2048,
          "messages" to messages,
          "tools" to plannerTools(input.schema),
          "tool_choice" to "auto",
          // A model may choose tools on intermediate turns, but its stop turn is still constrained
          // to the exact calendar draft schema before server-side projection validation.
          "response_format" to
            mapOf(
              "type" to "json_schema",
              "json_schema" to
                mapOf(
                  "name" to (input.schemaName ?: "calendar_draft_v2"),
                  "strict" to true,
                  "schema" to input.schema,
                ),
            ),
        )
      val request =
        HttpRequest.newBuilder(settings.endpoint)
          .timeout(Duration.ofMillis(requestDeadlineMillis))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer ${settings.key}")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build()
      pending = client.sendAsync(request) { BoundedAiBodySubscriber(256 * 1024) }
      val response = pending.get(requestDeadlineMillis, TimeUnit.MILLISECONDS)
      if (response.statusCode() != 200) {
        recordNonSuccess(response.statusCode(), response.body())
        throw aiError("ai_unavailable")
      }
      return parsePlannerTurn(json.readTree(response.body()))
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw aiError("ai_timeout")
    } catch (_: TimeoutException) {
      throw aiError("ai_timeout")
    } catch (e: ApiException) {
      throw e
    } catch (e: Exception) {
      val cause = e.cause ?: e
      if (cause is HttpTimeoutException) throw aiError("ai_timeout")
      if (cause is ApiException) throw cause
      throw aiError(
        if (e is ExecutionException || e is java.io.IOException) "ai_unavailable"
        else "ai_invalid_response"
      )
    } finally {
      pending?.takeUnless { it.isDone }?.cancel(true)
    }
  }

  private fun plannerTools(planSchema: JsonNode): List<Map<String, Any>> =
    listOf(
      tool(
        "get_strength_skeleton",
        mapOf(
          "type" to "object",
          "additionalProperties" to false,
          "required" to listOf("patternId"),
          "properties" to mapOf("patternId" to mapOf("type" to "string")),
        ),
      ),
      tool("get_candidate_details_and_history", candidateToolParameters()),
      tool(
        "validate_and_finalize_plan",
        mapOf(
          "type" to "object",
          "additionalProperties" to false,
          "required" to listOf("plan"),
          "properties" to mapOf("plan" to planSchema),
          // References in planSchema are rooted at #/$defs. Once the schema is embedded as a
          // tool argument, its definitions must therefore be available at the tool root too.
          "\u0024defs" to planSchema["\u0024defs"],
        ),
      ),
    )

  private fun recordNonSuccess(status: Int, body: ByteArray) {
    try {
      val error = json.readTree(body)?.get("error")
      diagnostics.recordHttp(
        status,
        error?.get("code")?.asText(),
        error?.get("type")?.asText(),
        error?.get("param")?.asText(),
      )
    } catch (_: Exception) {
      diagnostics.recordHttp(status)
    }
  }

  private fun candidateToolParameters(): Map<String, Any> =
    mapOf(
      "type" to "object",
      "additionalProperties" to false,
      "required" to listOf("candidateIds"),
      "properties" to
        mapOf(
          "candidateIds" to
            mapOf(
              "type" to "array",
              "maxItems" to PlannerToolProtocol.maxCandidateIds,
              "items" to mapOf("type" to "string", "format" to "uuid"),
            )
        ),
    )

  private fun tool(name: String, parameters: Map<String, Any>) =
    mapOf(
      "type" to "function",
      "function" to mapOf("name" to name, "strict" to true, "parameters" to parameters),
    )

  private fun parsePlannerTurn(root: JsonNode): PlannerTurn {
    try {
      if (root["error"]?.let { !it.isNull } == true) throw aiError("ai_invalid_response")
      val choices = root["choices"]
      if (choices?.isArray != true || choices.size() != 1) throw aiError("ai_invalid_response")
      val choice = choices[0]
      val message = choice["message"] ?: throw aiError("ai_invalid_response")
      if (
        message["role"]?.asString() != "assistant" || message["refusal"]?.let { !it.isNull } == true
      )
        throw aiError("ai_invalid_response")
      val reason = choice["finish_reason"]?.asString()
      val calls = message["tool_calls"]?.takeUnless { it.isNull }
      if (reason == "stop") {
        if (
          calls != null ||
            message["content"]?.isString != true ||
            message["content"].asString().isBlank()
        )
          throw aiError("ai_invalid_response")
        return PlannerTurn(final = json.readTree(message["content"].asString()))
      }
      if (
        reason != "tool_calls" ||
          calls?.isArray != true ||
          calls.size() !in 1..PlannerToolProtocol.maxCalls
      )
        throw aiError("ai_invalid_response")
      if (
        message["content"]
          ?.takeUnless { it.isNull }
          ?.let { !it.isString || it.asString().isNotEmpty() } == true
      )
        throw aiError("ai_invalid_response")
      val parsed = calls.toList().map { node -> parsePlannerCall(node) }
      if (parsed.map { it.id }.distinct().size != parsed.size) throw aiError("ai_invalid_response")
      return PlannerTurn(calls = parsed)
    } catch (e: ApiException) {
      diagnostics.event(
        AiDiagnosticSite.PROVIDER_RESPONSE,
        AiDiagnosticReason.INVALID_PROVIDER_RESPONSE,
      )
      throw e
    } catch (_: Exception) {
      diagnostics.event(
        AiDiagnosticSite.PROVIDER_RESPONSE,
        AiDiagnosticReason.INVALID_PROVIDER_RESPONSE,
      )
      throw aiError("ai_invalid_response")
    }
  }

  private fun parsePlannerCall(node: JsonNode): PlannerToolProtocol.Call {
    if (
      !node.isObject || node.properties().map { it.key }.toSet() != setOf("id", "type", "function")
    )
      throw aiError("ai_invalid_response")
    val id = node["id"]?.takeIf { it.isString }?.asString() ?: throw aiError("ai_invalid_response")
    if (node["type"]?.asString() != "function") throw aiError("ai_invalid_response")
    val function = node["function"] ?: throw aiError("ai_invalid_response")
    if (
      !function.isObject ||
        function.properties().map { it.key }.toSet() != setOf("name", "arguments")
    )
      throw aiError("ai_invalid_response")
    val name =
      function["name"]?.takeIf { it.isString }?.asString() ?: throw aiError("ai_invalid_response")
    val arguments =
      function["arguments"]?.takeIf { it.isString }?.asString()
        ?: throw aiError("ai_invalid_response")
    val bytes = arguments.toByteArray(Charsets.UTF_8)
    if (bytes.size !in 1..16_384) throw aiError("ai_invalid_response")
    val args = json.readTree(arguments)
    if (!args.isObject) throw aiError("ai_invalid_response")
    val ids =
      when (name) {
        "get_strength_skeleton" -> {
          if (
            args.properties().map { it.key }.toSet() != setOf("patternId") ||
              args["patternId"]?.isString != true ||
              !args["patternId"].asString().matches(Regex("[a-zA-Z0-9_-]{1,80}"))
          )
            throw aiError("ai_invalid_response")
          emptyList<String>()
        }
        "get_candidate_details_and_history" -> {
          if (args.properties().map { it.key }.toSet() != setOf("candidateIds"))
            throw aiError("ai_invalid_response")
          val value = args["candidateIds"]
          if (value?.isArray != true) throw aiError("ai_invalid_response")
          value.toList().map {
            it.takeIf(JsonNode::isTextual)?.asString()?.takeIf { value ->
              runCatching { java.util.UUID.fromString(value).toString() == value }
                .getOrDefault(false)
            } ?: throw aiError("ai_invalid_response")
          }
        }
        "validate_and_finalize_plan" -> {
          if (args.properties().map { it.key }.toSet() != setOf("plan") || !args["plan"].isObject)
            throw aiError("ai_invalid_response")
          emptyList<String>()
        }
        else -> throw aiError("ai_invalid_response")
      }
    return PlannerToolProtocol.Call(
      id,
      name,
      ids,
      bytes,
      if (name == "validate_and_finalize_plan") args["plan"] else null,
      patternId = if (name == "get_strength_skeleton") args["patternId"].asString() else null,
    )
  }
}

internal class BoundedAiBodySubscriber(private val max: Int) :
  HttpResponse.BodySubscriber<ByteArray> {
  private val result = CompletableFuture<ByteArray>()
  private val bytes = ByteArrayOutputStream()
  private var subscription: Flow.Subscription? = null

  override fun getBody(): CompletionStage<ByteArray> = result

  override fun onSubscribe(s: Flow.Subscription) {
    subscription = s
    s.request(1)
  }

  override fun onNext(items: List<ByteBuffer>) {
    for (buffer in items) {
      if (buffer.remaining() > max - bytes.size()) {
        subscription?.cancel()
        result.completeExceptionally(aiError("ai_invalid_response"))
        return
      }
      val part = ByteArray(buffer.remaining())
      buffer.get(part)
      bytes.write(part)
    }
    subscription?.request(1)
  }

  override fun onError(error: Throwable) {
    result.completeExceptionally(error)
  }

  override fun onComplete() {
    result.complete(bytes.toByteArray())
  }
}
