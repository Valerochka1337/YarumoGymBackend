package tech.valerochkagym.controller.ai

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tech.valerochkagym.service.ai.CoachRunService
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode

@RestController
@RequestMapping("/v1/coach")
class CoachRunController(
  private val runs: CoachRunService,
  private val interventions: tech.valerochkagym.service.ai.CoachInterventionService,
  private val modelCheck: tech.valerochkagym.service.ai.CoachModelCheckService,
) {
  @PostMapping("/model-check")
  fun modelCheck(@RequestBody raw: ByteArray): Map<String, Any> {
    val body = runs.parse(raw)
    val model = body["model"]
    if (model != null && !model.isNull && !model.isString)
      tech.valerochkagym.controller.advice.bad("Некорректная модель")
    return modelCheck.check(model?.takeUnless { it.isNull }?.asString())
  }

  @PostMapping("/sessions/{workoutId}/messages")
  fun message(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @RequestBody raw: ByteArray,
  ): ResponseEntity<JsonNode> {
    val result = runs.message(identity, workoutId, raw)
    return ResponseEntity.accepted()
      .header("Location", "/v1/coach/runs/${result["runId"].asString()}")
      .header("Retry-After", "2")
      .body(result)
  }

  @GetMapping("/sessions/{workoutId}/events", produces = ["text/event-stream"])
  fun sessionEvents(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestHeader(value = "Last-Event-ID", required = false) last: String?,
    response: jakarta.servlet.http.HttpServletResponse,
  ): SseEmitter {
    runs.workoutEvents(identity, workoutId, after)
    response.setHeader("Cache-Control", "no-store")
    response.setHeader("X-Accel-Buffering", "no")
    val emitter = SseEmitter(0L)
    val stopped = AtomicBoolean()
    val sender =
      Thread.ofVirtual().unstarted {
        try {
          var cursor = maxOf(after, last?.toLongOrNull() ?: 0).coerceAtLeast(0)
          var heartbeat = 0L
          while (!stopped.get()) {
            // Every read revalidates the login, including while the workout is idle.
            val events = runs.workoutEvents(identity, workoutId, cursor)
            for (event in events) {
              emitter.send(
                SseEmitter.event()
                  .id(event["sequence"].asLong().toString())
                  .name(event["type"].asString())
                  .data(event)
              )
              cursor = event["sequence"].asLong()
            }
            if (System.nanoTime() - heartbeat >= 10_000_000_000L) {
              emitter.send(SseEmitter.event().comment("heartbeat"))
              heartbeat = System.nanoTime()
            }
            Thread.sleep(500)
          }
        } catch (_: Exception) {
          // Replay is durable and independent of task execution.
        } finally {
          stopped.set(true)
          emitter.complete()
        }
      }
    fun stop() {
      stopped.set(true)
      sender.interrupt()
    }
    emitter.onCompletion(::stop)
    emitter.onTimeout(::stop)
    emitter.onError { stop() }
    sender.start()
    return emitter
  }

  @PostMapping("/runs")
  fun submit(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody raw: ByteArray,
  ): ResponseEntity<JsonNode> {
    val result = runs.submit(identity, raw)
    return ResponseEntity.accepted()
      .header("Location", "/v1/coach/runs/${result["runId"].asString()}")
      .header("Retry-After", "2")
      .body(result)
  }

  @GetMapping("/runs/{id}")
  fun status(@AuthenticationPrincipal identity: Identity, @PathVariable id: UUID) =
    ResponseEntity.ok()
      .header("Cache-Control", "no-store")
      .header("Retry-After", "2")
      .body(runs.status(identity, id))

  @PostMapping("/runs/{id}/cancel")
  fun cancel(@AuthenticationPrincipal identity: Identity, @PathVariable id: UUID) =
    runs.cancel(identity, id)

  @GetMapping("/sessions/{workoutId}/runs")
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @RequestParam(defaultValue = "0") after: Long,
  ) = runs.list(identity, workoutId, after)

  @PostMapping("/sessions/{workoutId}/questions/{questionId}/answers")
  fun answer(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @PathVariable questionId: UUID,
    @RequestBody raw: ByteArray,
  ) = interventions.answer(identity, workoutId, questionId, runs.parse(raw))

  @PostMapping("/sessions/{workoutId}/proposals/{proposalId}/receipt")
  fun interventionReceipt(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @PathVariable proposalId: UUID,
    @RequestBody raw: ByteArray,
  ) = interventions.receipt(identity, workoutId, proposalId, runs.parse(raw))

  @GetMapping("/sessions/{workoutId}/behavior")
  fun behavior(@AuthenticationPrincipal identity: Identity, @PathVariable workoutId: UUID) =
    ResponseEntity.ok()
      .header("Cache-Control", "no-store")
      .body(runs.behaviorState(identity, workoutId))

  @PutMapping("/sessions/{workoutId}")
  fun session(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable workoutId: UUID,
    @RequestBody raw: ByteArray,
  ) = runs.session(identity, workoutId, raw)

  @PostMapping("/runs/{id}/receipt")
  fun receipt(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable id: UUID,
    @RequestBody raw: ByteArray,
  ): Map<String, Any> {
    runs.receipt(identity, id, raw)
    return emptyMap()
  }

  @GetMapping("/runs/{id}/events", produces = ["text/event-stream"])
  fun events(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable id: UUID,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestHeader(value = "Last-Event-ID", required = false) last: String?,
    response: jakarta.servlet.http.HttpServletResponse,
  ): SseEmitter {
    runs.status(identity, id)
    response.setHeader("Cache-Control", "no-store")
    response.setHeader("X-Accel-Buffering", "no")
    val emitter = SseEmitter(60000L)
    val stopped = AtomicBoolean()
    val sender =
      Thread.ofVirtual().unstarted {
        try {
          var cursor = maxOf(after, last?.toLongOrNull() ?: 0).coerceAtLeast(0)
          var heartbeat = 0L
          while (!stopped.get()) {
            val events = runs.events(identity, id, cursor)
            for (event in events) {
              if (stopped.get()) break
              emitter.send(
                SseEmitter.event()
                  .id(event["sequence"].asLong().toString())
                  .name(event["type"].asString())
                  .data(event)
              )
              cursor = event["sequence"].asLong()
              if (event["type"].asString() == "completed") {
                stopped.set(true)
                break
              }
            }
            if (!stopped.get() && events.isEmpty()) {
              val status = runs.status(identity, id)
              if (
                status["state"].asString() !in setOf("QUEUED", "RUNNING") &&
                  cursor >= status["lastEventSequence"].asLong()
              )
                break
            }
            if (System.nanoTime() - heartbeat > 10_000_000_000L) {
              emitter.send(SseEmitter.event().comment("heartbeat"))
              heartbeat = System.nanoTime()
            }
            if (!stopped.get()) Thread.sleep(500)
          }
        } catch (_: Exception) {
          /* Reconnect resumes persisted events; worker remains independent. */
        } finally {
          stopped.set(true)
          emitter.complete()
        }
      }
    fun stop() {
      stopped.set(true)
      sender.interrupt()
    }
    emitter.onCompletion(::stop)
    emitter.onTimeout(::stop)
    emitter.onError { stop() }
    sender.start()
    return emitter
  }
}
