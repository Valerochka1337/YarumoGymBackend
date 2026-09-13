package tech.valerochkagym.service.ai

import tech.valerochkagym.controller.model.CalendarDraftRequest

/** Hard eligibility precedes deterministic, expanding representation selection. */
internal object CalendarCandidateSelector {
  fun eligible(
    sources: List<CalendarCandidateSource>,
    gyms: List<CalendarCandidateSource>,
    request: CalendarDraftRequest,
    facts: List<CalendarFact>,
    goal: String?,
  ): List<Map<String, Any>> {
    val sourceById = sources.associateBy { it.id }
    val totals = mutableMapOf<String, Int>()
    facts.forEach { fact ->
      sourceById[fact.exerciseId]?.payload?.get("muscles")?.toList().orEmpty().forEach { muscle ->
        val value = muscle["contribution"]?.asInt() ?: 0
        if (value > 0)
          totals[muscle["muscle"].asString()] = (totals[muscle["muscle"].asString()] ?: 0) + value
      }
    }
    fun group(type: String) =
      when (goal) {
        "STRENGTH",
        "MUSCLE_GAIN" -> if (type == "STRENGTH") 1 else 0
        "ENDURANCE" -> if (type in setOf("TIMED", "CARDIO")) 1 else 0
        "FAT_LOSS" -> if (type == "CARDIO") 1 else 0
        else -> 1
      }
    return sources
      .mapNotNull { row ->
        val payload = row.payload
        val id = row.id
        fun availableAt(gym: CalendarCandidateSource): Boolean {
          val body = gym.payload
          if (body["inventoryConfigured"]?.asBoolean() != true)
            return body["exerciseIds"]?.toList()?.any { it.asString() == id } == true
          if (payload["equipmentRequirementState"]?.asString() != "KNOWN") return false
          val inventory = body["equipmentIds"]?.toList()?.map { it.asString() }?.toSet().orEmpty()
          return payload["equipmentIds"]?.toList()?.all { it.asString() in inventory } == true
        }
        if (
          payload["archived"]?.asBoolean() == true ||
            id in request.excludedExerciseIds ||
            payload["equipmentIds"]?.toList()?.any {
              it.asString() in request.excludedEquipmentIds
            } == true ||
            payload["type"]?.asString() !in setOf("STRENGTH", "TIMED", "CARDIO") ||
            (request.gymIds.isNotEmpty() && gyms.any { !availableAt(it) })
        )
          return@mapNotNull null
        val muscles =
          payload["muscles"]
            ?.toList()
            ?.map {
              mapOf(
                "muscle" to it["muscle"].asString(),
                "contribution" to it["contribution"].asInt(),
              )
            }
            .orEmpty()
        mapOf(
          "exerciseId" to id,
          "type" to payload["type"].asString(),
          "name" to payload["name"].asString(),
          "available" to true,
          "equipmentIds" to payload["equipmentIds"]?.toList()?.map { it.asString() }.orEmpty(),
          "priority" to
            muscles.sumOf {
              if (it["muscle"] in request.priorityMuscles) it["contribution"] as Int else 0
            },
          "coverage" to
            muscles
              .filter { (it["contribution"] as Int) > 0 }
              .sumOf { totals[it["muscle"] as String] ?: 0 },
          "goalGroup" to group(payload["type"].asString()),
          "muscles" to muscles,
        )
      }
      .filter { (it["muscles"] as List<*>).isNotEmpty() }
      .sortedWith(
        compareByDescending<Map<String, Any>> { it["goalGroup"] as Int }
          .thenByDescending { it["priority"] as Int }
          .thenBy { it["coverage"] as Int }
          .thenBy { it["exerciseId"] as String }
      )
      .map { it - "goalGroup" }
      .take(1001)
      .also { if (it.size > 1000) throw aiError("ai_context_too_large") }
  }

  fun select(
    eligible: List<Map<String, Any>>,
    facts: List<CalendarFact>,
    preferences: String?,
  ): List<Map<String, Any>> {
    // Arbitrary explicit preferences may identify any name/variant. Never guess their meaning.
    if (!preferences.isNullOrBlank()) return eligible
    val familiar = facts.mapTo(mutableSetOf()) { it.exerciseId }
    val selected = linkedSetOf<String>()
    fun keep(row: Map<String, Any>) {
      selected += row["exerciseId"] as String
    }
    eligible.filter { it["exerciseId"] in familiar }.forEach(::keep)
    val groups = sortedMapOf<String, MutableList<Map<String, Any>>>()
    eligible.forEach { row ->
      val type = row["type"] as String
      val muscles = row["muscles"] as List<Map<String, Any>>
      val equipment = (row["equipmentIds"] as List<String>).sorted()
      val keys =
        muscles.filter { (it["contribution"] as Int) > 0 }.map { "muscle:$type:${it["muscle"]}" } +
          "equipment:$type:${equipment.joinToString("|")}"
      keys.forEach { groups.getOrPut(it) { mutableListOf() }.add(row) }
    }
    groups.values.forEach { rows -> rows.take(2).forEach(::keep) }
    eligible.forEach { if (selected.size < 36) keep(it) }
    return eligible.filter { it["exerciseId"] in selected }
  }
}
