package tech.valerochkagym.controller.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.AdminAction
import tech.valerochkagym.controller.model.AdminCredentials
import tech.valerochkagym.controller.model.AdminEdit
import tech.valerochkagym.repository.catalog.EquipmentRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.security.ADMIN_COOKIE
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.security.adminCookie
import tech.valerochkagym.service.admin.AdminService
import tech.valerochkagym.service.auth.AuthService
import tech.valerochkagym.service.data.RecordValidator
import tech.valerochkagym.service.model.Identity

@org.springframework.stereotype.Controller
class AdminPageController {
  @GetMapping("/admin", "/admin/") fun index() = "forward:/admin/index.html"
}

@RestController
@RequestMapping("/admin/api")
class AdminController(
  private val admin: AdminService,
  private val auth: AuthService,
  private val limits: RateLimiter,
  private val equipment: tech.valerochkagym.repository.catalog.EquipmentRepository,
  private val standard: StandardRepository,
  private val json: tools.jackson.databind.ObjectMapper,
) {
  private fun cookie(response: HttpServletResponse, value: String, age: Duration) {
    response.addHeader(
      HttpHeaders.SET_COOKIE,
      ResponseCookie.from(ADMIN_COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString(),
    )
  }

  @PostMapping("/login")
  fun login(
    @RequestBody body: AdminCredentials,
    response: HttpServletResponse,
  ): Map<String, String> {
    val username = body.username.trim().lowercase()
    if (username.length !in 1..64) unauthorized()
    limits.check("admin-login:" + username, 8)
    val (email, token) = admin.login(username, body.password)
    cookie(response, token, Duration.ofHours(8))
    return mapOf("email" to email, "csrfToken" to admin.csrf(token))
  }

  @GetMapping("/session")
  fun session(@AuthenticationPrincipal identity: Identity, request: HttpServletRequest) =
    mapOf(
      "email" to identity.email,
      "userId" to identity.userId.toString(),
      "csrfToken" to admin.csrf(adminCookie(request)!!),
    )

  @PostMapping("/logout")
  fun logout(@AuthenticationPrincipal identity: Identity, response: HttpServletResponse) {
    auth.logout(identity)
    cookie(response, "", Duration.ZERO)
  }

  @GetMapping("/summary") fun summary() = admin.summary()

  @GetMapping("/users")
  fun users(
    @RequestParam(defaultValue = "") q: String,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.users(q, offset, limit)

  @GetMapping("/users/{id}") fun user(@PathVariable id: UUID) = admin.user(id)

  @PostMapping("/users/{id}/revoke-sessions")
  fun revoke(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable id: UUID,
    @RequestBody body: AdminAction,
  ) = admin.revoke(actor, id, body)

  @GetMapping("/records")
  fun records(
    @RequestParam kind: String,
    @RequestParam(required = false) userId: UUID?,
    @RequestParam(defaultValue = "") q: String,
    @RequestParam(defaultValue = "false") deleted: Boolean,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.records(kind, userId, q, deleted, offset, limit)

  @GetMapping("/users/{user}/records/{kind}/{id}")
  fun record(@PathVariable user: UUID, @PathVariable kind: String, @PathVariable id: UUID) =
    admin.record(user, kind, id)

  @PutMapping("/users/{user}/records/{kind}/{id}")
  fun edit(
    @AuthenticationPrincipal actor: Identity,
    @PathVariable user: UUID,
    @PathVariable kind: String,
    @PathVariable id: UUID,
    @RequestBody body: AdminEdit,
  ) = admin.edit(actor, user, kind, id, body)

  @GetMapping("/audit")
  fun audit(
    @RequestParam(required = false) userId: UUID?,
    @RequestParam(defaultValue = "0") offset: Int,
    @RequestParam(defaultValue = "50") limit: Int,
  ) = admin.audit(userId, offset, limit)

  @GetMapping("/audit/{id}") fun auditEntry(@PathVariable id: Long) = admin.auditEntry(id)

  @GetMapping("/users/{id}/exercise-options")
  fun exerciseOptions(@PathVariable id: UUID) = admin.exerciseOptions(id)

  @GetMapping("/planner-exercises")
  fun plannerExercises() =
    standard
      .findAllByOrderByKindAscIdAsc()
      .filter { it.kind == "exercise" && !it.archived }
      .map {
        mapOf("id" to it.id.toString(), "name" to json.readTree(it.payload)["name"].asString())
      }

  @GetMapping("/catalog")
  fun catalog() =
    mapOf(
      "equipment" to equipment.findAllByOrderByIdAsc().filter { !it.archived }.map { it.id },
      "equipmentLabels" to
        equipment.findAllByOrderByIdAsc().associate {
          it.id to json.readTree(it.payload)["name"].asString()
        },
      "muscles" to RecordValidator.muscles,
    )
}
