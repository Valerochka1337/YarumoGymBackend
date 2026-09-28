package tech.valerochkagym.controller.ai

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.model.PlannerV2Capabilities
import tech.valerochkagym.service.ai.CalendarDraftJobService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/planning/v2")
class DeterministicPlannerController(private val jobs: CalendarDraftJobService) {
  @GetMapping("/capabilities") fun capabilities() = PlannerV2Capabilities()

  @PostMapping("/jobs", consumes = ["application/json"])
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun create(@AuthenticationPrincipal identity: Identity, @RequestBody raw: ByteArray) =
    jobs.submitV2(identity, raw)

  @GetMapping("/jobs/{requestId}")
  fun status(@AuthenticationPrincipal identity: Identity, @PathVariable requestId: UUID) =
    jobs.statusV2(identity, requestId)

  @DeleteMapping("/jobs/{requestId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun cancel(@AuthenticationPrincipal identity: Identity, @PathVariable requestId: UUID) =
    jobs.cancel(identity, requestId, 2)

  @PostMapping("/proposals/{proposalId}/refinements", consumes = ["application/json"])
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun refine(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable proposalId: UUID,
    @RequestBody raw: ByteArray,
  ) = jobs.submitV2Refinement(identity, proposalId, raw)
}
