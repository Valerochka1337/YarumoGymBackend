package tech.valerochkagym.service.ai

import java.util.UUID
import java.util.concurrent.Semaphore
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.bad
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Isolated tool host: it has no account, workout repository, or mutation capability. */
@Service
class CoachModelCheckService(
  private val provider: CoachTurnProvider,
  private val json: ObjectMapper,
) {
  private val permits = Semaphore(2)

  fun check(model: String?): Map<String, Any> {
    if (model != null && model.length > 200) bad("Некорректная модель")
    if (!permits.tryAcquire()) throw aiError("ai_busy")
    try {
      val catalog = provider.catalog()
      val selected = model ?: catalog.defaultModel
      if (selected == null || selected !in catalog.models) return result(false)
      val messages =
        mutableListOf<JsonNode>(
          json.valueToTree(
            mapOf(
              "role" to "user",
              "content" to
                "Проверка на синтетических данных. Вызови get_workout_state, затем submit_workout_changes с base_revision=0 и единственным add_set для секции из состояния.",
            )
          )
        )
      val tools = json.readTree(javaClass.getResourceAsStream("/ai/coach-tools.json")!!)
      var read = false
      repeat(4) {
        val response =
          provider.complete(
            CoachTurnInput(
              UUID.randomUUID().toString(),
              selected,
              json.valueToTree(messages),
              tools,
            )
          )
        val message = response["choices"]?.firstOrNull()?.get("message") ?: return result(false)
        messages.add(message)
        val calls = message["tool_calls"]?.toList().orEmpty()
        if (calls.isEmpty() || calls.size > 8) return result(false)
        for (call in calls) {
          val name = call["function"]?.get("name")?.asString()
          val args =
            runCatching { json.readTree(call["function"]["arguments"].asString()) }.getOrNull()
              ?: return result(false)
          val output =
            when (name) {
              "get_workout_state" -> {
                read = true
                """{"synthetic":true,"workout_id":"$WORKOUT","revision":0,"exercises":[{"section_id":"$SECTION","name":"Тестовое упражнение","sets":[]}]}"""
              }
              "submit_workout_changes" -> {
                val ops = args["operations"]?.toList().orEmpty()
                return result(
                  read &&
                    args["base_revision"]?.asLong() == 0L &&
                    ops.size == 1 &&
                    ops[0]["action"]?.asString() == "add_set" &&
                    ops[0]["section_id"]?.asString() == SECTION
                )
              }
              else -> """{"error":"synthetic_host_only"}"""
            }
          messages.add(
            json.valueToTree(
              mapOf("role" to "tool", "tool_call_id" to call["id"].asString(), "content" to output)
            )
          )
        }
      }
      return result(false)
    } catch (_: Exception) {
      return result(false)
    } finally {
      permits.release()
    }
  }

  private fun result(success: Boolean): Map<String, Any> =
    mapOf(
      "success" to success,
      "message" to
        if (success)
          "Модель поддерживает чтение тренировки и действия. Проверка пройдена на тестовых данных."
        else "Модель не выполнила тестовые вызовы. Выберите модель с поддержкой инструментов.",
    )

  companion object {
    private const val WORKOUT = "10000000-0000-4000-8000-000000000001"
    private const val SECTION = "10000000-0000-4000-8000-000000000002"
  }
}
