package tech.valerochkagym.controller.admin

import java.util.UUID
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.ai.PlannerExerciseMappingDto
import tech.valerochkagym.service.ai.PlannerMovementMappingService

@RestController
@RequestMapping("/admin/api/planner-exercise-mappings")
class AdminPlannerExerciseMappingController(private val mappings: PlannerMovementMappingService) {
  @GetMapping fun list() = mappings.listBuiltIn()

  @PutMapping("/{exerciseId}")
  fun put(@PathVariable exerciseId: UUID, @RequestBody body: PlannerExerciseMappingDto) =
    mappings.putBuiltIn(exerciseId, body)
}
