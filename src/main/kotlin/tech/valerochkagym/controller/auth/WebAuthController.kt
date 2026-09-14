package tech.valerochkagym.controller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseCookie
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.auth.GoogleIdentity
import tech.valerochkagym.service.auth.GoogleVerifier

/** Browser-only adapter. Android token responses and bearer endpoints are unchanged. */
@RestController
@RequestMapping("/v1/web/auth")
class WebAuthController(
  private val auth: AuthService,
  private val google: GoogleVerifier,
  private val nonces: GoogleIdentity,
  private val limits: RateLimiter,
  @Value("\${web.origin:https://app.valerochkagym.tech}") private val origin: String,
) {
  companion object {
    const val REFRESH = "__Host-yarumo-refresh"
    const val CSRF = "__Host-yarumo-csrf"
  }

  private fun cookie(request: HttpServletRequest, name: String) =
    request.cookies?.firstOrNull { it.name == name }?.value

  private fun cookie(response: HttpServletResponse, name: String, value: String, seconds: Long) {
    response.addHeader(
      "Set-Cookie",
      ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(seconds)
        .build()
        .toString(),
    )
  }

  private fun guard(request: HttpServletRequest, response: HttpServletResponse) {
    response.setHeader("Cache-Control", "no-store")
    if (request.getHeader("Origin") != origin)
      throw ApiException(403, "origin_denied", "Origin rejected")
    val stored = cookie(request, CSRF)
    val supplied = request.getHeader("X-CSRF-Token")
    if (
      stored == null ||
        supplied == null ||
        stored.length != 43 ||
        !MessageDigest.isEqual(stored.toByteArray(), supplied.toByteArray())
    )
      throw ApiException(403, "csrf_failed", "CSRF rejected")
    limits.check("web-ip:${request.remoteAddr}", 120)
  }

  private fun limited(email: String) {
    limits.check("email:${auth.email(email)}", 8)
  }

  private fun browser(tokens: Tokens, response: HttpServletResponse): Map<String, Any> {
    cookie(response, REFRESH, tokens.refreshToken, 30L * 24 * 60 * 60)
    return mapOf(
      "userId" to tokens.userId,
      "email" to tokens.email,
      "accessToken" to tokens.accessToken,
      "expiresIn" to tokens.expiresIn,
    )
  }

  @GetMapping("/csrf")
  fun csrf(request: HttpServletRequest, response: HttpServletResponse): Map<String, String> {
    response.setHeader("Cache-Control", "no-store")
    val supplied = request.getHeader("Origin")
    if (
      (supplied != null && supplied != origin) ||
        request.getHeader("Sec-Fetch-Site") == "cross-site"
    )
      throw ApiException(403, "origin_denied", "Origin rejected")
    val token =
      cookie(request, CSRF)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        ?: Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    cookie(response, CSRF, token, 30L * 24 * 60 * 60)
    return mapOf("csrfToken" to token)
  }

  @PostMapping("/login")
  fun login(
    @RequestBody body: Credentials,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, Any> {
    guard(request, response)
    limited(body.email)
    return browser(auth.login(body.email, body.password, body.deviceName), response)
  }

  @PostMapping("/refresh")
  fun refresh(request: HttpServletRequest, response: HttpServletResponse): Map<String, Any> {
    guard(request, response)
    val token =
      cookie(request, REFRESH) ?: throw ApiException(401, "unauthorized", "Sign in required")
    return browser(auth.refresh(token), response)
  }

  @PostMapping("/logout")
  fun logout(request: HttpServletRequest, response: HttpServletResponse): Map<String, String> {
    guard(request, response)
    cookie(request, REFRESH)?.let {
      try {
        val tokens = auth.refresh(it)
        auth.authenticate(tokens.accessToken)?.let(auth::logout)
      } catch (e: ApiException) {
        if (e.status != 401) throw e
      }
    }
    cookie(response, REFRESH, "", 0)
    return mapOf("status" to "signed_out")
  }

  @PostMapping("/register")
  fun register(
    @RequestBody body: Credentials,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, String> {
    guard(request, response)
    limited(body.email)
    auth.register(body.email, body.password)
    return mapOf("status" to "check_email")
  }

  @PostMapping("/verify/request", "/password/request")
  fun requestCode(
    @RequestBody body: EmailRequest,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, String> {
    guard(request, response)
    limited(body.email)
    auth.requestCode(
      body.email,
      if (request.requestURI.endsWith("password/request")) "reset" else "verify",
    )
    return mapOf("status" to "check_email")
  }

  @PostMapping("/verify")
  fun verify(
    @RequestBody body: CodeRequest,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, String> {
    guard(request, response)
    limited(body.email)
    auth.verify(body.email, body.code)
    return mapOf("status" to "verified")
  }

  @PostMapping("/password/reset")
  fun reset(
    @RequestBody body: ResetRequest,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, String> {
    guard(request, response)
    limited(body.email)
    auth.reset(body.email, body.code, body.password)
    return mapOf("status" to "reset")
  }

  @PostMapping("/google/nonce")
  fun nonce(request: HttpServletRequest, response: HttpServletResponse): Map<String, String> {
    guard(request, response)
    return mapOf("nonce" to nonces.nonce())
  }

  @PostMapping("/google")
  fun google(
    @RequestBody body: GoogleRequest,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Map<String, Any> {
    guard(request, response)
    val account = google.verify(body.idToken, body.nonce)
    return browser(auth.google(account.subject, account.email, body.deviceName), response)
  }
}

@Configuration
class WebAuthSecurity {
  /**
   * CSRF is enforced by the adapter before every action; cookie auth never applies to Android
   * routes.
   */
  @Bean
  @Order(1)
  fun webAuthChain(http: HttpSecurity): SecurityFilterChain =
    http
      .securityMatcher("/v1/web/auth/**")
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests { it.anyRequest().permitAll() }
      .build()
}
