package tech.valerochkagym.controller.data

import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.routineshare.RoutineShareService
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v1/routine-shares")
class RoutineShareController(
  private val shares: RoutineShareService,
  private val json: ObjectMapper,
) {
  @PostMapping
  fun create(
    @AuthenticationPrincipal identity: Identity,
    @RequestBody raw: ByteArray,
  ): RoutineShareCreated =
    shares.create(identity, strict(raw, CreateRoutineShareRequest::class.java))

  @GetMapping
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam routineId: String?,
    @RequestParam(defaultValue = "50") limit: Int,
  ): RoutineShareList = shares.list(identity, routineId, limit)

  @PostMapping("/{shareId}/revoke")
  fun revoke(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable shareId: UUID,
    @RequestBody raw: ByteArray,
  ): RoutineShareRevoked =
    shares.revoke(identity, shareId, strict(raw, RevokeRoutineShareRequest::class.java))

  @PostMapping("/preview/{token}/import")
  fun import(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable token: String,
    @RequestBody raw: ByteArray,
  ): RoutineShareImport =
    shares.import(identity, token, strict(raw, ImportRoutineShareRequest::class.java))

  private fun <T> strict(raw: ByteArray, type: Class<T>): T {
    if (raw.size > maxRequestBytes)
      throw ApiException(413, "payload_too_large", "Превышен размер запроса")
    val root =
      try {
        json
          .tokenStreamFactory()
          .rebuild()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build()
          .createParser(raw)
          .use { parser ->
            json.readTree(parser).also {
              if (parser.nextToken() != null) bad("Некорректный запрос")
              validateShape(it, type)
            }
          }
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    return try {
      json.treeToValue(root, type)
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
  }

  private fun <T> validateShape(root: tools.jackson.databind.JsonNode, type: Class<T>) {
    val fields =
      when (type) {
        CreateRoutineShareRequest::class.java ->
          setOf("operationId", "routineId", "expectedRevision", "catalogRevision")
        RevokeRoutineShareRequest::class.java,
        ImportRoutineShareRequest::class.java -> setOf("operationId")
        else -> error("Unsupported routine-share request")
      }
    if (
      !root.isObject || root.properties().any { it.key !in fields } || fields.any { !root.has(it) }
    )
      bad("Некорректный запрос")
    if (!root["operationId"].isString) bad("Некорректный запрос")
    if (type == CreateRoutineShareRequest::class.java) {
      if (
        !root["routineId"].isString ||
          !root["expectedRevision"].isIntegralNumber ||
          !root["catalogRevision"].isIntegralNumber
      )
        bad("Некорректный запрос")
    }
  }

  private companion object {
    const val maxRequestBytes = 16 * 1024
  }
}
