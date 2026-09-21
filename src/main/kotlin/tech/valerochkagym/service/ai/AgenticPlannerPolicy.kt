package tech.valerochkagym.service.ai

/**
 * The v2 selector's trust boundary. Hard availability is decided before this policy. This layer
 * deliberately applies only a saved NEVER preference: collections are editable and must not be
 * silently reduced to a legacy strength skeleton or a hand-curated canonical subset.
 */
internal object AgenticPlannerPolicy {
  const val candidateLimit = 24

  data class Candidate(
    val id: String,
    val row: Map<String, Any>,
    val origin: String,
    val explicitOnly: Boolean,
    val curatedCanonical: Boolean,
  )

  fun resolvePreferences(
    legacy: Map<String, String>,
    accents: Map<String, String>?,
    defaults: Map<String, String>,
    sources: Map<String, CalendarCandidateSource>,
  ): Map<String, String> {
    val result = mutableMapOf<String, String>()
    sources.forEach { (id, source) ->
      if (source.curatedCanonical) defaults[id]?.let { result[id] = it }
    }
    if (accents != null) result.putAll(accents) else result.putAll(legacy)
    return result
  }

  fun effectiveStrengthPriorities(
    priorities: Map<String, String>,
    preferences: Map<String, String>,
    accentsAuthoritative: Boolean,
  ): Map<String, String> = buildMap {
    priorities.forEach { (id, priority) ->
      when (preferences[id]) {
        "MORE" -> put(id, if (priority == "HIGH") "HIGH" else "NORMAL")
        "NORMAL",
        "LESS",
        "NEVER" -> Unit
        null -> if (!accentsAuthoritative) put(id, priority)
      }
    }
    preferences.filterValues { it == "MORE" }.keys.forEach { id -> putIfAbsent(id, "NORMAL") }
  }

  fun select(
    eligible: List<Map<String, Any>>,
    keyExercises: Map<String, String>,
    preferences: Map<String, String>,
    facts: List<CalendarFact>,
    sources: Map<String, CalendarCandidateSource> = emptyMap(),
  ): List<Map<String, Any>> {
    val ordered =
      eligible
        .asSequence()
        .filter { preferences[it.getValue("exerciseId") as String] != "NEVER" }
        .sortedWith(
          compareBy<Map<String, Any>>(
            { row ->
              val id = row.getValue("exerciseId") as String
              when {
                id in keyExercises || preferences[id] == "MORE" -> 0
                preferences[id] == "LESS" -> 2
                else -> 1
              }
            },
            { it.getValue("exerciseId") as String },
          )
        )
        .toList()
    // Keep a compact pool, but do not let an upper-body-heavy sort erase another
    // valid movement class before the agent can select its configured pattern.
    val selected = linkedSetOf<String>()
    fun add(row: Map<String, Any>) {
      if (selected.size < candidateLimit) selected += row.getValue("exerciseId") as String
    }
    ordered
      .groupBy { it.getValue("type") as String }
      .toSortedMap()
      .values
      .forEach { add(it.first()) }
    ordered
      .flatMap { row ->
        @Suppress("UNCHECKED_CAST")
        (row["muscles"] as? List<Map<String, Any>>)
          .orEmpty()
          .filter { (it["contribution"] as? Int ?: 0) > 0 }
          .map { it.getValue("muscle") as String to row }
      }
      .groupBy { it.first }
      .toSortedMap()
      .values
      .forEach { add(it.first().second) }
    ordered.forEach(::add)
    return ordered.filter { it.getValue("exerciseId") in selected }
  }
}
