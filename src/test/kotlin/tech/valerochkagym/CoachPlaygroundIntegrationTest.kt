package tech.valerochkagym

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.service.ai.CoachRunContext
import tech.valerochkagym.service.auth.AuthService
import tools.jackson.databind.ObjectMapper

@Testcontainers
@ActiveProfiles("playground")
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = ["gym.coach-runs.enabled=false", "gym.calendar-jobs.enabled=false"],
)
class CoachPlaygroundIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
          "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
        )
        .withDatabaseName("gym_playground")

    @DynamicPropertySource
    @JvmStatic
    fun properties(r: DynamicPropertyRegistry) {
      r.add("spring.datasource.url", postgres::getJdbcUrl)
      r.add("spring.datasource.username", postgres::getUsername)
      r.add("spring.datasource.password", postgres::getPassword)
      r.add("gym.token-pepper") { "test-pepper-with-at-least-thirty-two-bytes" }
      r.add("AI_SETTINGS_ENCRYPTION_KEY") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
    }
  }

  @org.springframework.boot.test.web.server.LocalServerPort var port: Int = 0
  @Autowired lateinit var json: ObjectMapper
  @Autowired lateinit var auth: AuthService
  @Autowired lateinit var context: CoachRunContext
  @Autowired lateinit var db: JdbcTemplate
  private val client = HttpClient.newHttpClient()

  private fun call(
    path: String,
    method: String = "GET",
    body: String? = null,
    token: String? = null,
    localHeader: Boolean = true,
  ): HttpResponse<String> {
    val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
    if (localHeader) builder.header("X-Coach-Playground", "1")
    if (token != null) builder.header("Authorization", "Bearer $token")
    if (body != null) builder.header("Content-Type", "application/json")
    builder.method(
      method,
      body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
    )
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  @Test
  fun `local UI seeds real readable context and authenticates through normal bearer endpoints`() {
    assertEquals(200, call("/dev/coach/").statusCode())
    assertEquals(200, call("/dev/coach/state.js").statusCode())
    assertEquals(403, call("/dev/coach/api/bootstrap", "POST", localHeader = false).statusCode())
    val response = call("/dev/coach/api/bootstrap", "POST")
    assertEquals(200, response.statusCode(), response.body())
    val bootstrap = json.readTree(response.body())
    val token = bootstrap["accessToken"].asString()
    val owner = auth.authenticate(token)!!
    assertEquals(4, bootstrap["catalog"].size())
    val exercise = bootstrap["catalog"][0]["exercise_id"].asString()
    assertEquals(9, context.history(owner.userId, exercise)["history"].size())
    assertEquals(200, call("/v1/ai/coach-models", token = token).statusCode())
    val workout = java.util.UUID.randomUUID().toString()
    val run =
      """{"requestId":"${java.util.UUID.randomUUID()}","workoutId":"$workout","contextVersion":"${"a".repeat(64)}","snapshot":{"workout_id":"$workout","revision":1,"exercises":[]},"message":"Привет","history":[]}"""
    assertEquals(202, call("/v1/coach/runs", "POST", run, token).statusCode())
    call("/dev/coach/api/bootstrap", "POST")
    assertEquals(
      7,
      db.queryForObject(
        "SELECT count(*) FROM records WHERE user_id=?",
        Int::class.java,
        owner.userId,
      ),
    )
  }

  @Test
  fun `settings are encrypted and the secret is never returned`() {
    val before = json.readTree(call("/dev/coach/api/settings").body())
    val body =
      """{"revision":${before["revision"]},"enabled":true,"baseUrl":"https://example.test/v1","textModel":"fixture","visionModel":"fixture","coachModel":"fixture","coachModels":["fixture"],"apiKey":"private-test-key"}"""
    val response = call("/dev/coach/api/settings", "PUT", body)
    assertEquals(200, response.statusCode(), response.body())
    assertFalse(response.body().contains("private-test-key"))
    assertTrue(json.readTree(response.body())["hasApiKey"].asBoolean())
    val stored =
      db.queryForObject(
        "SELECT encrypted_api_key FROM ai_settings WHERE id=1",
        String::class.java,
      )!!
    assertTrue(stored.startsWith("v1:"))
    assertFalse(stored.contains("private-test-key"))
  }
}
