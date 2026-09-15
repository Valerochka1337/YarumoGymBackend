package tech.valerochkagym.controller.data

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.service.trainingproposal.TrainingProposalService
import tech.valerochkagym.service.trainingproposal.TrainingProposalValidator
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v1/training-proposals")
class TrainingProposalController(
  private val service: TrainingProposalService,
  private val validator: TrainingProposalValidator,
  private val json: ObjectMapper,
  private val explanations: tech.valerochkagym.service.ai.PlannerExplanationStore,
) {
  @GetMapping
  fun list(
    @AuthenticationPrincipal identity: Identity,
    @RequestParam(defaultValue = "20") limit: Int,
    @RequestParam(required = false) cursor: String?,
  ) = service.list(identity, limit, cursor)

  @GetMapping("/{proposalId}")
  fun detail(@AuthenticationPrincipal identity: Identity, @PathVariable proposalId: UUID) =
    service.detail(identity, proposalId)

  @GetMapping("/{proposalId}/planner-explanation")
  fun plannerExplanation(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable proposalId: UUID,
  ) = explanations.read(identity, proposalId)

  @GetMapping("/{proposalId}/accepted-result")
  fun acceptedResult(@AuthenticationPrincipal identity: Identity, @PathVariable proposalId: UUID) =
    service.acceptedResult(identity, proposalId)

  @PostMapping("/{proposalId}/approve")
  fun approve(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable proposalId: UUID,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Any {
    capability(request, response)
    val raw = raw(request)
    val hash = sha256(raw)
    return service.approve(identity, proposalId, raw, hash, validator.approval(tree(raw)))
  }

  @PostMapping("/{proposalId}/reject")
  fun reject(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable proposalId: UUID,
    request: HttpServletRequest,
  ) = service.reject(identity, proposalId, validator.reject(tree(raw(request))))

  @PostMapping("/{proposalId}/revoke")
  fun revoke(
    @AuthenticationPrincipal identity: Identity,
    @PathVariable proposalId: UUID,
    request: HttpServletRequest,
  ) = service.revoke(identity, proposalId, validator.revoke(tree(raw(request))))

  private fun capability(request: HttpServletRequest, response: HttpServletResponse) {
    val accepted =
      request
        .getHeader("X-Gym-Capabilities")
        ?.split(',')
        ?.map { it.trim() }
        ?.toSet()
        ?.intersect(setOf("calendar-plans"))
        .orEmpty()
    response.setHeader("X-Gym-Capabilities", accepted.joinToString(","))
    if ("calendar-plans" !in accepted)
      throw ApiException(426, "capability_required", "Требуется возможность calendar-plans")
  }

  private fun raw(request: HttpServletRequest): ByteArray {
    if (request.contentLengthLong > maxBody) tooLarge()
    val mediaType = request.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (mediaType != "application/json") bad("Требуется JSON")
    val bytes =
      request.inputStream.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          if (output.size() + count > maxBody) tooLarge()
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
    if (bytes.isEmpty() || bytes.startsWithBom()) bad("Некорректное тело запроса")
    try {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
    } catch (_: CharacterCodingException) {
      bad("Некорректный UTF-8")
    }
    return bytes
  }

  private fun tree(raw: ByteArray): JsonNode {
    val text = raw.toString(StandardCharsets.UTF_8)
    DuplicateNames.reject(text)
    return try {
      json.readTree(raw) ?: bad("Некорректный JSON")
    } catch (e: ApiException) {
      throw e
    } catch (_: Exception) {
      bad("Некорректный JSON")
    }
  }

  private fun ByteArray.startsWithBom() =
    size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()

  private fun tooLarge(): Nothing =
    throw ApiException(413, "payload_too_large", "Тело запроса слишком велико")

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private companion object {
    const val maxBody = 524288
  }
}

/** Detect names before Jackson's tree model can collapse duplicate object members. */
private object DuplicateNames {
  fun reject(text: String) {
    val parser = Parser(text)
    parser.value(0)
    parser.whitespace()
    if (!parser.end()) bad("Некорректный JSON")
  }

  private class Parser(private val input: String) {
    private var index = 0

    fun end() = index == input.length

    fun whitespace() {
      while (index < input.length && input[index] in " \t\r\n") index++
    }

    fun value(depth: Int) {
      if (depth > maxDepth) bad("Слишком глубокий JSON")
      whitespace()
      if (index == input.length) bad("Некорректный JSON")
      when (input[index]) {
        '{' -> objectValue(depth + 1)
        '[' -> arrayValue(depth + 1)
        '"' -> string()
        't' -> literal("true")
        'f' -> literal("false")
        'n' -> literal("null")
        '-',
        in '0'..'9' -> number()
        else -> bad("Некорректный JSON")
      }
    }

    private fun objectValue(depth: Int) {
      index++
      whitespace()
      val names = mutableSetOf<String>()
      if (take('}')) return
      while (true) {
        whitespace()
        if (index == input.length || input[index] != '"') bad("Некорректный JSON")
        if (!names.add(string())) bad("Повтор поля JSON")
        whitespace()
        if (!take(':')) bad("Некорректный JSON")
        value(depth)
        whitespace()
        if (take('}')) return
        if (!take(',')) bad("Некорректный JSON")
      }
    }

    private fun arrayValue(depth: Int) {
      index++
      whitespace()
      if (take(']')) return
      while (true) {
        value(depth)
        whitespace()
        if (take(']')) return
        if (!take(',')) bad("Некорректный JSON")
      }
    }

    private fun string(): String {
      if (!take('"')) bad("Некорректный JSON")
      val value = StringBuilder()
      while (index < input.length) {
        val c = input[index++]
        when (c) {
          '"' -> return value.toString()
          '\\' -> {
            if (index == input.length) bad("Некорректный JSON")
            when (val escaped = input[index++]) {
              '"',
              '\\',
              '/' -> value.append(escaped)
              'b' -> value.append('\b')
              'f' -> value.append('\u000c')
              'n' -> value.append('\n')
              'r' -> value.append('\r')
              't' -> value.append('\t')
              'u' -> {
                if (index + 4 > input.length) bad("Некорректный JSON")
                val code =
                  input.substring(index, index + 4).toIntOrNull(16) ?: bad("Некорректный JSON")
                value.append(code.toChar())
                index += 4
              }
              else -> bad("Некорректный JSON")
            }
          }
          else -> if (c.code < 0x20) bad("Некорректный JSON") else value.append(c)
        }
      }
      bad("Некорректный JSON")
    }

    private fun literal(value: String) {
      if (!input.startsWith(value, index)) bad("Некорректный JSON")
      index += value.length
    }

    private fun number() {
      val start = index
      if (take('-') && index == input.length) bad("Некорректный JSON")
      if (take('0')) Unit else digits()
      if (take('.')) digits()
      if (index < input.length && input[index] in "eE") {
        index++
        if (index < input.length && input[index] in "+-") index++
        digits()
      }
      if (index == start) bad("Некорректный JSON")
    }

    private fun digits() {
      val start = index
      while (index < input.length && input[index] in '0'..'9') index++
      if (start == index) bad("Некорректный JSON")
    }

    private fun take(char: Char): Boolean =
      (index < input.length && input[index] == char).also { if (it) index++ }

    private companion object {
      const val maxDepth = 128
    }
  }
}
