package tech.valerochkagym.service.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import tech.valerochkagym.config.AiProviderSettings
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class HttpOpenAiChatCompletionsProviderTest {
  private val json = JsonMapper.builder().build()
  private lateinit var server: HttpServer
  private lateinit var executor: ExecutorService
  private val captured = AtomicReference<JsonNode>()
  private var handler: (com.sun.net.httpserver.HttpExchange) -> Unit = {}

  @BeforeEach
  fun setup() {
    executor = Executors.newCachedThreadPool()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = executor
    server.createContext("/v1/chat/completions") { exchange ->
      try {
        captured.set(json.readTree(exchange.requestBody))
        handler(exchange)
      } finally {
        exchange.close()
      }
    }
    server.start()
  }

  @AfterEach
  fun close() {
    server.stop(0)
    executor.shutdownNow()
  }

  fun provider(timeout: Long = 1000) =
    HttpOpenAiChatCompletionsProvider(
      AiProviderSettings(
        URI("http://127.0.0.1:${server.address.port}/v1/chat/completions"),
        "dummy-key",
        "text-fixture",
        "vision-fixture",
      ),
      json,
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
      timeout,
    )

  fun input(vision: Boolean = false) =
    AiProviderInput(
      vision,
      "server instructions",
      "bounded context",
      json.readTree(
        javaClass.getResourceAsStream(
          "/ai/${if(vision) "inbody" else "exercise"}-output-schema.json"
        )
      ),
      if (vision) "aGVsbG8=" else null,
    )

  fun calendarInput() =
    AiProviderInput(
      false,
      "server instructions",
      "bounded context",
      json.readTree(javaClass.getResourceAsStream("/ai/calendar-output-schema.json")),
      schemaName = "calendar_draft",
    )

  fun envelope() =
    json.writeValueAsString(
      mapOf(
        "choices" to
          listOf(
            mapOf(
              "finish_reason" to "stop",
              "message" to mapOf("role" to "assistant", "content" to "{\"result\":{}}"),
            )
          )
      )
    )

  fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
    val bytes = body.toByteArray()
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.write(bytes)
  }

  @Test
  fun `wire uses exact required model strict schema and selected image only`() {
    handler = { exchange ->
      assertEquals("POST", exchange.requestMethod)
      assertEquals("Bearer dummy-key", exchange.requestHeaders.getFirst("Authorization"))
      respond(exchange, envelope())
    }
    val p = provider()
    p.generate(input())
    val text = captured.get()
    assertEquals("text-fixture", text["model"].asString())
    assertFalse(text["stream"].asBoolean())
    assertFalse(text["store"].asBoolean())
    assertEquals(1, text["n"].asInt())
    assertEquals(2048, text["max_completion_tokens"].asInt())
    assertTrue(text["response_format"]["json_schema"]["strict"].asBoolean())
    assertEquals(input().schema, text["response_format"]["json_schema"]["schema"])
    p.generate(input(true))
    val vision = captured.get()
    assertEquals("vision-fixture", vision["model"].asString())
    assertEquals(
      "data:image/jpeg;base64,aGVsbG8=",
      vision["messages"][1]["content"][1]["image_url"]["url"].asString(),
    )
    assertFalse(vision.has("tools"))
    p.generate(calendarInput())
    assertEquals(
      "calendar_draft",
      captured.get()["response_format"]["json_schema"]["name"].asString(),
    )
  }

  @Test
  fun `response body exact limit succeeds and max plus one cancels safely`() {
    val max = envelope().padEnd(256 * 1024, ' ')
    handler = { respond(it, max) }
    provider().generate(input())
    handler = { respond(it, max + " ") }
    assertEquals(
      "ai_invalid_response",
      assertThrows(ApiException::class.java) { provider().generate(input()) }.code,
    )
  }

  @Test
  fun `per call remaining budget bounds a correction request`() {
    handler = {
      Thread.sleep(400)
      respond(it, envelope())
    }
    assertEquals(
      "ai_timeout",
      assertThrows(ApiException::class.java) {
          provider(2000).generate(input().copy(timeoutMillis = 100))
        }
        .code,
    )
  }

  @Test
  fun `slow headers and streaming body obey end to end deadline`() {
    handler = {
      Thread.sleep(400)
      respond(it, envelope())
    }
    assertEquals(
      "ai_timeout",
      assertThrows(ApiException::class.java) { provider(100).generate(input()) }.code,
    )
    handler = { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.write("{".toByteArray())
      exchange.responseBody.flush()
      Thread.sleep(500)
    }
    assertEquals(
      "ai_timeout",
      assertThrows(ApiException::class.java) { provider(100).generate(input()) }.code,
    )
  }

  @Test
  fun `cancellation interrupts concurrent exchanges and permits later requests`() {
    val entered = CountDownLatch(2)
    val release = CountDownLatch(1)
    handler = { exchange ->
      entered.countDown()
      release.await(3, TimeUnit.SECONDS)
      respond(exchange, envelope())
    }
    val p = provider(2000)
    val finished = CountDownLatch(2)
    val a =
      executor.submit<JsonNode> {
        try {
          p.generate(input())
        } finally {
          finished.countDown()
        }
      }
    val b =
      executor.submit<JsonNode> {
        try {
          p.generate(input())
        } finally {
          finished.countDown()
        }
      }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    a.cancel(true)
    b.cancel(true)
    release.countDown()
    assertTrue(finished.await(2, TimeUnit.SECONDS))
    handler = { respond(it, envelope()) }
    assertTrue(p.generate(input()).isObject)
  }

  @Test
  fun `refusal truncation multiple choices redirects and upstream errors are safe`() {
    for (body in
      listOf(
        "{}",
        envelope().replace("stop", "length"),
        envelope().replace("assistant", "tool"),
        """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{}","refusal":"private"}}]}""",
      )) {
      handler = { respond(it, body) }
      val e = assertThrows(ApiException::class.java) { provider().generate(input()) }
      assertEquals("ai_invalid_response", e.code)
      assertFalse(e.message.contains("private"))
    }
    handler = { respond(it, "dummy-secret upstream", 503) }
    assertEquals(
      "ai_unavailable",
      assertThrows(ApiException::class.java) { provider().generate(input()) }.code,
    )
    handler = {
      it.responseHeaders.add("Location", "http://127.0.0.1:1/secret")
      respond(it, "redirect", 302)
    }
    assertEquals(
      "ai_unavailable",
      assertThrows(ApiException::class.java) { provider().generate(input()) }.code,
    )
  }
}
