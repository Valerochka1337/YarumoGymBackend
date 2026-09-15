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
) : AiProvider {
  override val available = true

  override fun generate(input: AiProviderInput): JsonNode {
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
          "model" to if (input.vision) settings.visionModel else settings.textModel,
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
      if (response.statusCode() != 200) throw aiError("ai_unavailable")
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
