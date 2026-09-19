package tech.valerochkagym.playground

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CoachPlaygroundTest {
  @Test
  fun `profile refuses a non isolated database or public binding`() {
    for ((url, address) in
      listOf(
        "jdbc:postgresql://127.0.0.1:5432/gym" to "127.0.0.1",
        "jdbc:postgresql://remote.example:5432/gym_playground" to "127.0.0.1",
        "jdbc:postgresql://127.0.0.1:5432/gym_playground" to "0.0.0.0",
      )) {
      assertThrows(IllegalArgumentException::class.java) {
        CoachPlaygroundSecurity.validate(
          MockEnvironment()
            .withProperty("spring.datasource.url", url)
            .withProperty("server.address", address)
        )
      }
    }
  }

  @Test
  fun `local bootstrap rejects foreign origins hosts missing header and remote callers`() {
    fun check(host: String, origin: String?, remote: String, header: Boolean, accepted: Boolean) {
      val request = MockHttpServletRequest("POST", "/dev/coach/api/bootstrap")
      request.remoteAddr = remote
      request.addHeader("Host", host)
      origin?.let { request.addHeader("Origin", it) }
      if (header) request.addHeader("X-Coach-Playground", "1")
      val response = MockHttpServletResponse()
      var reached = false
      PlaygroundOriginFilter().doFilter(request, response, FilterChain { _, _ -> reached = true })
      assertEquals(accepted, reached)
      if (!accepted) assertEquals(403, response.status)
    }
    check("localhost:18081", "http://localhost:18081", "127.0.0.1", true, true)
    check("localhost:18081", "https://evil.example", "127.0.0.1", true, false)
    check("evil.example:18081", null, "127.0.0.1", true, false)
    check("localhost:18081", null, "127.0.0.1", false, false)
    check("localhost:18081", null, "10.0.0.2", true, false)
  }
}
