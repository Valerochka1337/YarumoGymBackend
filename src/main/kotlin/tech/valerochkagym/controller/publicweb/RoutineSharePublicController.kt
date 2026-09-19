package tech.valerochkagym.controller.publicweb

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.RoutineSharePreview
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.service.routineshare.RoutineShareService

@RestController
class RoutineSharePublicController(
  private val shares: RoutineShareService,
  private val limits: RateLimiter,
) {
  @GetMapping("/v1/routine-shares/preview/{token}")
  fun preview(
    @PathVariable token: String,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): RoutineSharePreview {
    publicHeaders(response)
    limits.check("routine-share-preview:${request.remoteAddr}", 60)
    return shares.preview(token)
      ?: throw ApiException(404, "share_unavailable", "Ссылка недоступна")
  }

  @GetMapping("/r/{token}")
  fun page(
    @PathVariable token: String,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): ResponseEntity<String> {
    publicHeaders(response)
    limits.check("routine-share-preview:${request.remoteAddr}", 60)
    val preview = shares.preview(token)
    if (preview != null) {
      return ResponseEntity.status(302)
        .location(URI.create("$browserOrigin/r/$token"))
        .headers { publicHeaders(it) }
        .body("")
    }
    return ResponseEntity.status(404)
      .contentType(MediaType.TEXT_HTML)
      .headers { publicHeaders(it) }
      .body(RoutineShareHtml.unavailable())
  }

  private fun publicHeaders(response: HttpServletResponse) {
    response.setHeader("Cache-Control", "no-store")
    response.setHeader("Referrer-Policy", "no-referrer")
    response.setHeader("X-Robots-Tag", "noindex, nofollow")
    response.setHeader("Content-Security-Policy", RoutineShareHtml.contentSecurityPolicy)
  }

  private fun publicHeaders(headers: org.springframework.http.HttpHeaders) {
    headers.set("Cache-Control", "no-store")
    headers.set("Referrer-Policy", "no-referrer")
    headers.set("X-Robots-Tag", "noindex, nofollow")
    headers.set("Content-Security-Policy", RoutineShareHtml.contentSecurityPolicy)
  }

  private companion object {
    const val browserOrigin = "https://app.valerochkagym.tech"
  }
}
