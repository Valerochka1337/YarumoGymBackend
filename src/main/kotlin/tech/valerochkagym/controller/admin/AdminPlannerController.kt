package tech.valerochkagym.controller.admin

import org.springframework.web.bind.annotation.*
import tech.valerochkagym.service.ai.PlannerConfiguration
import tech.valerochkagym.service.ai.PlannerConfigurationService

@RestController
@RequestMapping("/admin/api/planner-settings")
class AdminPlannerController(private val settings: PlannerConfigurationService) {
  @GetMapping fun get() = settings.snapshot()

  @PutMapping fun save(@RequestBody body: PlannerConfiguration) = settings.save(body)
}
