package tech.valerochkagym.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class PlannerProtocolFilter : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val v2 = request.getHeader("X-Planner-Protocol") == "2"
    if (request.requestURI.startsWith("/v1/planning/v2/") && !v2) {
      response.status = 426
      response.contentType = "application/json"
      response.writer.write(
        """{"code":"capability_required","message":"Требуется протокол планировщика 2"}"""
      )
      return
    }
    if (v2) {
      response.setHeader("X-Planner-Protocol", "2")
      response.setHeader("X-Gym-Capabilities", "deterministic-workout-planner-v2")
    }
    chain.doFilter(request, response)
  }
}
