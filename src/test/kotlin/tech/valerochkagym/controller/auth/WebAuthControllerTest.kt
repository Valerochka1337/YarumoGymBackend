package tech.valerochkagym.controller.auth

import jakarta.servlet.http.Cookie
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.model.Tokens
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.auth.GoogleIdentity
import tech.valerochkagym.service.auth.GoogleVerifier

class WebAuthControllerTest {
  private val auth = mock(AuthService::class.java)
  private val controller =
    WebAuthController(
      auth,
      mock(GoogleVerifier::class.java),
      mock(GoogleIdentity::class.java),
      mock(RateLimiter::class.java),
      "https://api.valerochkagym.tech",
      "browser-client.apps.googleusercontent.com",
    )

  private fun request() =
    MockHttpServletRequest().also {
      it.addHeader("Origin", "https://api.valerochkagym.tech")
      it.addHeader("X-CSRF-Token", "a".repeat(43))
      it.setCookies(
        Cookie(WebAuthController.CSRF, "a".repeat(43)),
        Cookie(WebAuthController.REFRESH, "refresh"),
      )
    }

  @Test
  fun `rejects missing Origin before touching auth`() {
    val request = request()
    request.removeHeader("Origin")
    assertEquals(
      403,
      assertThrows(ApiException::class.java) {
          controller.refresh(request, MockHttpServletResponse())
        }
        .status,
    )
    verifyNoInteractions(auth)
  }

  @Test
  fun `rejects cross origin and invalid csrf`() {
    val request = request()
    request.removeHeader("Origin")
    request.addHeader("Origin", "https://evil.example")
    assertEquals(
      403,
      assertThrows(ApiException::class.java) {
          controller.refresh(request, MockHttpServletResponse())
        }
        .status,
    )
    val second = request()
    second.removeHeader("X-CSRF-Token")
    second.addHeader("X-CSRF-Token", "b".repeat(43))
    assertEquals(
      403,
      assertThrows(ApiException::class.java) {
          controller.refresh(second, MockHttpServletResponse())
        }
        .status,
    )
    verifyNoInteractions(auth)
  }

  @Test
  fun `refresh secret is cookie only`() {
    `when`(auth.refresh("refresh"))
      .thenReturn(Tokens(UUID.randomUUID(), "test@example.com", "access", "next-refresh"))
    val response = MockHttpServletResponse()
    val body = controller.refresh(request(), response)
    assertFalse(body.containsKey("refreshToken"))
    assertEquals("access", body["accessToken"])
    val cookie = response.getHeader("Set-Cookie")!!
    assertTrue(cookie.contains("HttpOnly"))
    assertTrue(cookie.contains("Secure"))
    assertTrue(cookie.contains("SameSite=Strict"))
    assertEquals("no-store", response.getHeader("Cache-Control"))
  }

  @Test
  fun `csrf endpoint returns stable cookie bound token`() {
    val response = MockHttpServletResponse()
    val body = controller.csrf(request(), response)
    assertEquals("a".repeat(43), body["csrfToken"])
    assertTrue(response.getHeader("Set-Cookie")!!.contains("HttpOnly"))
  }

  @Test
  fun `google config exposes public browser client id without caching`() {
    val response = MockHttpServletResponse()
    assertEquals(
      "browser-client.apps.googleusercontent.com",
      controller.googleConfig(response)["clientId"],
    )
    assertEquals("no-store", response.getHeader("Cache-Control"))
  }
}
