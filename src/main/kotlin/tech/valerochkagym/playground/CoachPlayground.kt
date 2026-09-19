package tech.valerochkagym.playground

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.util.UUID
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.service.ai.AiSettingsEdit
import tech.valerochkagym.service.ai.AiSettingsService
import tech.valerochkagym.utils.Crypto
import tools.jackson.databind.ObjectMapper

/** Deliberately unavailable outside the isolated playground profile/database. */
@Configuration
@Profile("playground")
class CoachPlaygroundSecurity {
  companion object {
    @Bean
    @JvmStatic
    fun playgroundEnvironmentGuard(env: Environment) = BeanFactoryPostProcessor { validate(env) }

    internal fun validate(env: Environment) {
      val database = URI(env.getRequiredProperty("spring.datasource.url").removePrefix("jdbc:"))
      require(
        database.host in setOf("localhost", "127.0.0.1", "[::1]") &&
          database.path == "/gym_playground"
      ) {
        "Playground requires a local PostgreSQL database named gym_playground"
      }
      require(env.getProperty("server.address") in setOf("127.0.0.1", "::1")) {
        "Playground must bind to loopback"
      }
      require(env.getProperty("server.forward-headers-strategy") == "none") {
        "Playground must ignore forwarded headers"
      }
    }
  }

  @Bean
  @Order(0)
  fun playgroundChain(http: HttpSecurity): SecurityFilterChain =
    http
      .securityMatcher("/dev/coach", "/dev/coach/**")
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests { it.anyRequest().permitAll() }
      .addFilterBefore(PlaygroundOriginFilter(), UsernamePasswordAuthenticationFilter::class.java)
      .build()
}

internal class PlaygroundOriginFilter : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val origin = "http://${request.getHeader("Host")}"
    val host = runCatching { URI(origin).host }.getOrNull()
    val api = request.requestURI.startsWith("/dev/coach/api/")
    if (
      request.remoteAddr !in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1") ||
        host !in setOf("localhost", "127.0.0.1", "[::1]") ||
        request.getHeader("Sec-Fetch-Site") == "cross-site" ||
        (request.getHeader("Origin") != null && request.getHeader("Origin") != origin) ||
        (api && request.getHeader("X-Coach-Playground") != "1")
    ) {
      response.status = 403
      response.contentType = "application/json"
      response.writer.write("{\"code\":\"playground_origin_denied\"}")
      return
    }
    response.setHeader("Cache-Control", "no-store")
    response.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    )
    chain.doFilter(request, response)
  }
}

@Service
@Profile("playground")
class CoachPlaygroundFixtures(
  private val jdbc: JdbcTemplate,
  private val crypto: Crypto,
  private val json: ObjectMapper,
) {
  companion object {
    val OWNER: UUID = UUID.fromString("c0ac0000-0000-4000-8000-000000000001")
  }

  @Transactional
  fun bootstrap(): Map<String, Any> {
    jdbc.update(
      "INSERT INTO users(id,email,email_verified) VALUES (?, 'coach-playground@example.invalid', true) ON CONFLICT(id) DO NOTHING",
      OWNER,
    )
    val fixtures =
      ClassPathResource("playground/fixtures.json").inputStream.use { json.readTree(it) }
    fixtures["records"].forEach { record ->
      jdbc.update(
        "INSERT INTO records(user_id,kind,id,revision,payload) VALUES (?,?,?,1,?::jsonb) ON CONFLICT(user_id,kind,id) DO NOTHING",
        OWNER,
        record["kind"].asString(),
        UUID.fromString(record["id"].asString()),
        record["payload"].toString(),
      )
    }
    val token = crypto.token()
    jdbc.update("DELETE FROM sessions WHERE user_id=? AND access_expires_at < now()", OWNER)
    jdbc.update(
      "INSERT INTO sessions(id,user_id,device_name,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,'Coach playground',?,now()+interval '20 minutes',now()+interval '20 minutes')",
      UUID.randomUUID(),
      OWNER,
      crypto.hash(token),
    )
    return mapOf("accessToken" to token, "catalog" to fixtures["catalog"])
  }
}

@RestController
@Profile("playground")
@RequestMapping("/dev/coach")
class CoachPlaygroundController(
  private val fixtures: CoachPlaygroundFixtures,
  private val settings: AiSettingsService,
) {
  @GetMapping("", "/", "/index.html") fun page() = asset("index.html", MediaType.TEXT_HTML)

  @GetMapping("/playground.css")
  fun css() = asset("playground.css", MediaType.parseMediaType("text/css"))

  @GetMapping("/playground.js", "/state.js")
  fun js(request: HttpServletRequest) =
    asset(request.requestURI.substringAfterLast('/'), MediaType.parseMediaType("text/javascript"))

  private fun asset(name: String, type: MediaType) =
    ResponseEntity.ok().contentType(type).body(ClassPathResource("playground/$name"))

  @PostMapping("/api/bootstrap") fun bootstrap() = fixtures.bootstrap()

  @GetMapping("/api/settings") fun settings() = settings.get()

  @PutMapping("/api/settings")
  fun settings(@RequestBody body: AiSettingsEdit) = settings.save(body, null)
}
