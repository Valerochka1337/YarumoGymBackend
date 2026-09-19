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

  fun select(
    eligible: List<Map<String, Any>>,
    keyExercises: Map<String, String>,
    preferences: Map<String, String>,
    facts: List<CalendarFact>,
    sources: Map<String, CalendarCandidateSource> = emptyMap(),
  ): List<Map<String, Any>> {
    val history = facts.mapTo(mutableSetOf()) { it.exerciseId }
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
                id in history -> 1
                preferences[id] == "LESS" -> 3
                else -> 2
              }
            },
            { it.getValue("exerciseId") as String },
          )
        )
        .toList()
    // Keep a compact pool, but do not let an upper-body/familiarity-heavy sort erase another
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
