package tech.valerochkagym.controller.ai

import java.util.concurrent.FutureTask
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.service.ai.*
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/ai")
class LiveCoachController(
  private val service: CoachTurnService,
  private val prompts: CoachPromptService,
  private val limits: RateLimiter,
  private val auth: tech.valerochkagym.service.auth.AuthService,
) {
  @GetMapping("/coach-prompt")
  fun prompt(@AuthenticationPrincipal identity: Identity) = prompts.get()

  @GetMapping("/coach-models")
  fun models(@AuthenticationPrincipal identity: Identity) = service.catalog()

  @PostMapping(
    "/coach-turn/stream",
    consumes = ["application/json"],
    produces = ["text/event-stream"],
  )
  fun stream(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader("Authorization") authorization: String,
    @RequestBody raw: ByteArray,
    response: jakarta.servlet.http.HttpServletResponse,
  ): org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
    limits.check("coach:${identity.userId}", 30)
    val turn = service.prepareStream(raw)
    response.setHeader("Cache-Control", "no-store")
    response.setHeader("X-Accel-Buffering", "no")
    return CoachStreamExchange(turn, response) {
        auth.authenticate(authorization.removePrefix("Bearer ")) == identity
      }
      .start()
  }

  @PostMapping("/coach-turn", consumes = ["application/json"])
  fun turn(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody raw: ByteArray,
  ): DeferredResult<CoachTurnResponse> {
    limits.check("coach:${identity.userId}", 30)
    val result = DeferredResult<CoachTurnResponse>(45000)
    val task = FutureTask {
      try {
        result.setResult(service.turn(raw))
      } catch (e: Exception) {
        result.setErrorResult(if (e is ApiException) e else aiError("ai_unavailable"))
      }
      Unit
    }
    result.onTimeout {
      task.cancel(true)
      result.setErrorResult(aiError("ai_timeout"))
    }
    result.onError { task.cancel(true) }
    result.onCompletion { if (!task.isDone) task.cancel(true) }
    Thread.ofVirtual().start(task)
    return result
  }
}
