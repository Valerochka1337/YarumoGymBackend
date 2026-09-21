package tech.valerochkagym.controller.data

import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.PushRequest
import tech.valerochkagym.controller.model.Record
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.data.SyncService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1")
class DataController(private val sync: SyncService) {
  private fun accept(raw: String?, response: HttpServletResponse): Set<String> {
    val requested = raw?.split(",")?.map { it.trim() }?.toSet().orEmpty()
    val accepted =
      setOf(
          "calendar-plans",
          "annotated-workout-writes",
          "exercise-hint",
          "profile",
          "strength-planner-personalization",
          "workout-rir-v1",
          "ai-planner-agentic-v1",
          "planner-default-accents-v2",
        )
        .intersect(requested)
    response.setHeader("X-Gym-Capabilities", accepted.joinToString(","))
    return accepted
  }

  @ApiResponse(
    responseCode = "200",
    description = "OK",
    useReturnTypeSchema = true,
    headers =
      [
        Header(
          name = "X-Gym-Capabilities",
          description =
            "Accepted intersection: calendar-plans, annotated-workout-writes, exercise-hint, profile, strength-planner-personalization, workout-rir-v1, ai-planner-agentic-v1, planner-default-accents-v2; otherwise empty",
          schema = Schema(type = "string"),
        )
      ],
  )
  @GetMapping("/sync")
  fun snapshot(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestHeader(name = "X-Gym-Capabilities", required = false) capabilities: String?,
    response: HttpServletResponse,
  ) = sync.snapshot(identity.userId, version, accept(capabilities, response))

  @ApiResponse(
    responseCode = "200",
    description = "OK",
    useReturnTypeSchema = true,
    headers =
      [
        Header(
          name = "X-Gym-Capabilities",
          description =
            "Accepted intersection: calendar-plans, annotated-workout-writes, exercise-hint, profile, strength-planner-personalization, workout-rir-v1, ai-planner-agentic-v1, planner-default-accents-v2; otherwise empty",
          schema = Schema(type = "string"),
        )
      ],
  )
  @PostMapping("/sync")
  fun push(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody request: PushRequest,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestHeader(name = "X-Gym-Capabilities", required = false) capabilities: String?,
    response: HttpServletResponse,
  ) = sync.push(identity.userId, request, version, accept(capabilities, response))

  @ApiResponse(
    responseCode = "200",
    description = "OK",
    useReturnTypeSchema = true,
    headers =
      [
        Header(
          name = "X-Gym-Capabilities",
          description =
            "Accepted intersection: calendar-plans, annotated-workout-writes, exercise-hint, profile, strength-planner-personalization, workout-rir-v1, ai-planner-agentic-v1, planner-default-accents-v2; otherwise empty",
          schema = Schema(type = "string"),
        )
      ],
  )
  @GetMapping("/sync/changes")
  fun changes(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestHeader(name = "X-Gym-Capabilities", required = false) capabilities: String?,
    response: HttpServletResponse,
    @RequestParam(defaultValue = "0") after: Long,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "200") limit: Int,
  ) = sync.changes(identity.userId, after, cursor, limit, version, accept(capabilities, response))

  @ApiResponse(
    responseCode = "200",
    description = "OK",
    useReturnTypeSchema = true,
    headers =
      [
        Header(
          name = "X-Gym-Capabilities",
          description =
            "Accepted intersection: calendar-plans, annotated-workout-writes, exercise-hint, profile, strength-planner-personalization, workout-rir-v1, ai-planner-agentic-v1, planner-default-accents-v2; otherwise empty",
          schema = Schema(type = "string"),
        )
      ],
  )
  @GetMapping("/records/{kind}")
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestHeader(name = "X-Gym-Capabilities", required = false) capabilities: String?,
    response: HttpServletResponse,
    @PathVariable kind: String,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "100") limit: Int,
  ): List<Record> {
    if (kind !in RecordValidator.kinds || offset < 0 || limit !in 1..1000)
      bad("Некорректная пагинация")
    return sync
      .snapshot(identity.userId, version, accept(capabilities, response))
      .records
      .filter { it.kind == kind && !it.deleted }
      .drop(offset)
      .take(limit)
  }

  @ApiResponse(
    responseCode = "200",
    description = "OK",
    useReturnTypeSchema = true,
    headers =
      [
        Header(
          name = "X-Gym-Capabilities",
          description =
            "Accepted intersection: calendar-plans, annotated-workout-writes, exercise-hint, profile, strength-planner-personalization, workout-rir-v1, ai-planner-agentic-v1, planner-default-accents-v2; otherwise empty",
          schema = Schema(type = "string"),
        )
      ],
  )
  @GetMapping("/records/{kind}/{id}")
  fun record(
    @AuthenticationPrincipal identity: Identity,
    @RequestHeader(name = "X-Gym-Sync-Version", required = false) version: String?,
    @RequestHeader(name = "X-Gym-Capabilities", required = false) capabilities: String?,
    response: HttpServletResponse,
    @PathVariable kind: String,
    @PathVariable id: UUID,
  ): Record =
    sync.snapshot(identity.userId, version, accept(capabilities, response)).records.firstOrNull {
      it.kind == kind && it.id == id && !it.deleted
    } ?: throw ApiException(404, "not_found", "Объект не найден")
}
