package tech.valerochkagym.controller.data

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.ai.PlannerExerciseMappingDto
import tech.valerochkagym.service.ai.PlannerMovementMappingService
import tech.valerochkagym.service.model.Identity

@RestController
@RequestMapping("/v1/planning/v2/exercise-mappings")
class PlannerExerciseMappingController(private val mappings: PlannerMovementMappingService) {
  @GetMapping fun list(@AuthenticationPrincipal identity: Identity) = mappings.list(identity)

  @GetMapping("/{exerciseId}")
  fun get(@AuthenticationPrincipal identity: Identity, @PathVariable exerciseId: UUID) =
    mappings.get(identity, exerciseId)

  @PutMapping("/{exerciseId}")
  fun put(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable exerciseId: UUID,
    @RequestBody body: PlannerExerciseMappingDto,
  ) = mappings.put(identity, exerciseId, body)

  @DeleteMapping("/{exerciseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(@AuthenticationPrincipal identity: Identity, @PathVariable exerciseId: UUID) =
    mappings.delete(identity, exerciseId)
}
