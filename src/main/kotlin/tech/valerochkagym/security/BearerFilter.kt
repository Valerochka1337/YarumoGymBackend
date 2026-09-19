package tech.valerochkagym.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.service.auth.AuthService

class BearerFilter(
  private val auth: AuthService,
  private val healthDisclosure: tech.valerochkagym.service.health.HealthAiDisclosureService,
  private val limits: RateLimiter,
  private val catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
) : OncePerRequestFilter() {
  // Re-authenticate the resumed AI response dispatch; the initial stateless context is cleared.
  override fun shouldNotFilterAsyncDispatch() = false

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    // The stream checks session validity on every send. A final async dispatch must
    // retain its initial principal and never append a JSON authentication error to SSE.
    if (
      request.dispatcherType == jakarta.servlet.DispatcherType.ASYNC &&
        (request.requestURI == "/v1/ai/coach-turn/stream" ||
          request.requestURI.matches(Regex("/v1/coach/runs/[^/]+/events")))
    ) {
      val identity = request.getAttribute("coach.stream.identity")
      if (identity != null)
        SecurityContextHolder.getContext().authentication =
          UsernamePasswordAuthenticationToken(identity, null, emptyList())
      try {
        chain.doFilter(request, response)
      } finally {
        SecurityContextHolder.clearContext()
      }
      return
    }
    try {
      val maxRequestBytes =
        if (request.requestURI in setOf("/v1/ai/coach-turn", "/v1/ai/coach-turn/stream"))
          tech.valerochkagym.service.ai.CoachTurnService.MAX_REQUEST_BYTES
        else 10 * 1024 * 1024
      if (request.contentLengthLong > maxRequestBytes)
        throw ApiException(413, "payload_too_large", "Превышен размер запроса")
      if (request.requestURI.startsWith("/v1/auth/")) limits.check("ip:${request.remoteAddr}", 120)
      val header = request.getHeader("Authorization")
      if (header != null) {
        if (!header.startsWith("Bearer ")) unauthorized()
        val identity = auth.authenticate(header.removePrefix("Bearer ")) ?: unauthorized()
        SecurityContextHolder.getContext().authentication =
          UsernamePasswordAuthenticationToken(identity, null, emptyList())
        if (
          (request.requestURI == "/v1/ai/coach-turn/stream" ||
            request.requestURI.matches(Regex("/v1/coach/runs/[^/]+/events")))
        )
          request.setAttribute("coach.stream.identity", identity)
        limits.check("user:${identity.userId}", 300)
      }
      if (
        (request.requestURI.startsWith("/v1/sync") ||
          request.requestURI.startsWith("/v1/records")) &&
          catalog.findById(1).orElseThrow().active &&
          request.getHeader("X-Gym-Sync-Version") !in setOf("2", "3")
      )
        throw ApiException(426, "client_update_required", "Обновите приложение для общего каталога")
      val identity =
        SecurityContextHolder.getContext().authentication?.principal
          as? tech.valerochkagym.service.model.Identity
      if (
        request.requestURI.startsWith("/v1/health-ledger") ||
          request.requestURI == "/v1/health-ai-disclosure"
      ) {
        if (identity == null) unauthorized()
        val capable =
          request.getHeader("X-Gym-Capabilities")?.split(',')?.any {
            it.trim() == "health-ledger-v1"
          } == true
        response.setHeader("X-Gym-Capabilities", if (capable) "health-ledger-v1" else "")
        if (!capable) throw ApiException(426, "capability_required", "Требуется health-ledger-v1")
      }
      if (request.requestURI == "/v1/ai/inbody-drafts") {
        if (identity == null) unauthorized()
        healthDisclosure.requireEnabled(
          identity,
          request.getHeader("X-Health-AI-Disclosure-Revision")?.toLongOrNull(),
        )
      }
      val bounded =
        object : HttpServletRequestWrapper(request) {
          override fun getInputStream(): ServletInputStream {
            val delegate = request.inputStream
            return object : ServletInputStream() {
              private var count = 0

              override fun read(): Int {
                val value = delegate.read()
                if (value >= 0 && ++count > maxRequestBytes)
                  throw ApiException(413, "payload_too_large", "Превышен размер запроса")
                return value
              }

              override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val read = delegate.read(bytes, offset, length)
                if (read > 0) count += read
                if (count > maxRequestBytes)
                  throw ApiException(413, "payload_too_large", "Превышен размер запроса")
                return read
              }

              override fun isFinished() = delegate.isFinished

              override fun isReady() = delegate.isReady

              override fun setReadListener(listener: ReadListener) =
                delegate.setReadListener(listener)
            }
          }
        }
      chain.doFilter(bounded, response)
    } catch (e: ApiException) {
      response.status = e.status
      response.contentType = "application/json"
      response.characterEncoding = "UTF-8"
      if (e.status == 429) response.setHeader("Retry-After", "60")
      response.writer.write("{\"code\":\"${e.code}\",\"message\":\"${e.message}\"}")
    } finally {
      SecurityContextHolder.clearContext()
    }
  }
}
