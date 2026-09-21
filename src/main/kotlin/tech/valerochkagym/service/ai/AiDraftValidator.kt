package tech.valerochkagym.service.ai

import java.time.LocalDate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Component
class AiDraftValidator(private val json: ObjectMapper) {
  fun schema(vision: Boolean): JsonNode =
    javaClass
      .getResourceAsStream("/ai/${if(vision) "inbody" else "exercise"}-output-schema.json")!!
      .use { json.readTree(it) }

  fun validate(raw: JsonNode, vision: Boolean, allowedIds: Set<String>): JsonNode {
    if (!matches(raw, schema(vision))) throw aiError("ai_invalid_response")
    val result = raw["result"]
    if (!vision) {
      if (result["kind"].asString() == "EXISTING") {
        if (result["exerciseId"].asString() !in allowedIds) throw aiError("ai_invalid_response")
      } else {
        val rows = result["muscles"].toList()
        if (
          result["name"].asString().isBlank() ||
            rows.map { it["muscle"].asString() }.distinct().size != rows.size ||
            rows.none { it["contribution"].asInt() > 0 }
        )
          throw aiError("ai_invalid_response")
      }
    } else {
      val draft = result["draft"]
      val values =
        draft
          .properties()
          .filter { it.key !in setOf("measuredDate", "measuredTime", "segments") }
          .map { it.value } +
          draft["segments"].properties().flatMap { it.value.properties().map { v -> v.value } }
      if (values.all { it.isNull }) throw aiError("ai_invalid_response")
    }
    return result
  }

  /** Calendar has a separate strict provider envelope with no provider-controlled projections. */
  fun validateCalendar(raw: JsonNode): JsonNode {
    val schema =
      javaClass.getResourceAsStream("/ai/calendar-output-schema.json")!!.use { json.readTree(it) }
    if (!matches(raw, schema, schema)) throw aiError("ai_invalid_response")
    return raw
  }

  fun validatePlanner(raw: JsonNode, onMismatch: (String) -> Unit = {}): JsonNode {
    val schema =
      javaClass.getResourceAsStream("/ai/calendar-planner-output-v3.json")!!.use(json::readTree)
    // Ignore only obsolete explanation metadata, including malformed or contradictory codes.
    // The remaining plan and envelope still have to match the strict provider schema.
    val plan = raw.deepCopy()
    (plan["result"] as? ObjectNode)?.remove("rationale")
    if (!matches(plan, schema, schema)) {
      onMismatch(mismatchPath(plan, schema, schema, ""))
      throw aiError("ai_invalid_response")
    }
    return plan
  }

  /** Paths contain schema-owned keys and bounded array indexes, never supplied property names. */
  private fun mismatchPath(n: JsonNode, s: JsonNode, root: JsonNode, path: String): String {
    s["${'$'}ref"]?.let {
      return mismatchPath(n, resolve(it, root), root, path)
    }
    if (n.isObject) {
      val props = s["properties"] ?: return path
      for (required in s["required"]?.toList().orEmpty()) {
        val key = required.asString()
        if (!n.has(key)) return if (path.isEmpty()) key else "$path.$key"
      }
      for (property in n.properties()) {
        if (!props.has(property.key)) return path
        if (!matches(property.value, props[property.key], root)) {
          val next = if (path.isEmpty()) property.key else "$path.${property.key}"
          return mismatchPath(property.value, props[property.key], root, next)
        }
      }
    }
    if (n.isArray && s["items"] != null) {
      n.toList().take(1000).forEachIndexed { index, item ->
        if (!matches(item, s["items"], root))
          return mismatchPath(item, s["items"], root, "$path[$index]")
      }
    }
    return path
  }

  private fun matches(n: JsonNode, s: JsonNode, root: JsonNode = s): Boolean {
    s["${'$'}ref"]?.let {
      return matches(n, resolve(it, root), root)
    }
    s["anyOf"]?.let {
      return it.any { schema -> matches(n, schema, root) }
    }
    val types =
      s["type"]
        ?.let { if (it.isArray) it.toList().map { v -> v.asString() } else listOf(it.asString()) }
        .orEmpty()
    val actual =
      when {
        n.isNull -> "null"
        n.isObject -> "object"
        n.isArray -> "array"
        n.isString -> "string"
        n.isIntegralNumber -> "integer"
        n.isNumber -> "number"
        else -> "unknown"
      }
    if (actual !in types && !(actual == "integer" && "number" in types)) return false
    if (n.isNull) return true
    s["enum"]?.let { if (it.none { v -> v == n }) return false }
    if (n.isObject) {
      val props = s["properties"] ?: return false
      if (
        n.properties().any { !props.has(it.key) } ||
          s["required"]?.any { !n.has(it.asString()) } == true
      )
        return false
      return n.properties().all { matches(it.value, props[it.key], root) }
    }
    if (n.isArray)
      return (s["minItems"] == null || n.size() >= s["minItems"].asInt()) &&
        n.all { matches(it, s["items"], root) }
    if (n.isNumber) {
      if (
        !n.asDouble().isFinite() ||
          s["minimum"]?.let { n.asDouble() < it.asDouble() } == true ||
          s["maximum"]?.let { n.asDouble() > it.asDouble() } == true
      )
        return false
      if ("integer" in types && (n.asDouble() > Int.MAX_VALUE || n.asDouble() < Int.MIN_VALUE))
        return false
    }
    if (n.isString) {
      val value = n.asString()
      if (
        s["minLength"]?.let { value.length < it.asInt() } == true ||
          s["maxLength"]?.let { value.length > it.asInt() } == true ||
          s["pattern"]?.let { !Regex(it.asString()).matches(value) } == true
      )
        return false
      if (s["format"]?.asString() == "date")
        try {
          if (LocalDate.parse(value).toString() != value) return false
        } catch (_: Exception) {
          return false
        }
    }
    return true
  }

  private fun resolve(reference: JsonNode, schema: JsonNode): JsonNode {
    val path = reference.asString().removePrefix("#/").split('/').filter(String::isNotEmpty)
    return path.fold(schema) { node, segment -> node[segment] ?: return node }
  }
}
