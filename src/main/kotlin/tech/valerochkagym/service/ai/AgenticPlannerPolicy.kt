package tech.valerochkagym.service.ai

/**
 * The v2 selector's trust boundary. User prose never reaches this policy and therefore cannot
 * expand the model-visible pool. Classification is intentionally conservative: an unknown or custom
 * exercise needs an explicit saved signal or completed history.
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
    val candidates =
      eligible
        .map { row ->
          val id = row.getValue("exerciseId") as String
          val source = sources[id]?.payload
          // Missing metadata is never silently promoted to a canonical basic.
          Candidate(
            id,
            row,
            when {
              source == null -> "UNKNOWN"
              source["isCustom"]?.asBoolean() == true -> "CUSTOM"
              sources[id]?.curatedCanonical == true && id in StrengthPlannerFacts.compoundSeedIds ->
                "STANDARD"
              else -> "UNKNOWN"
            },
            // Calisthenics, balance and plyometric work is deliberately not a default candidate.
            source?.get("plannerCategory")?.asString() == "EXPLICIT_ONLY" ||
              source?.get("movementFamily")?.asString() in
                setOf("CALISTHENICS", "BALANCE", "PLYOMETRIC"),
            sources[id]?.curatedCanonical == true,
          )
        }
        .filter { preferences[it.id] != "NEVER" }
    fun trusted(candidate: Candidate): Boolean {
      val explicit =
        candidate.id in keyExercises ||
          preferences[candidate.id] == "MORE" ||
          candidate.id in history
      return when {
        candidate.explicitOnly -> explicit
        candidate.origin == "STANDARD" -> true
        else -> explicit
      }
    }
    val trusted = candidates.filter(::trusted)
    val nonLess = trusted.filter { preferences[it.id] != "LESS" }
    // The frozen skeleton has one interchangeable accessory class. A LESS exercise enters only
    // when that class has no non-LESS trusted candidate; never merely to reach the pool cap.
    val ordered =
      (if (nonLess.isNotEmpty()) nonLess else trusted)
        .distinctBy { it.id }
        .sortedWith(
          compareBy<Candidate>(
            { if (preferences[it.id] == "LESS") 1 else 0 },
            { sourceRank(it, keyExercises, preferences, history) },
            { it.id },
          )
        )
    var unfamiliarStandard = 0
    return ordered
      .filter { candidate ->
        val unfamiliar = candidate.origin == "STANDARD" && candidate.id !in history
        if (unfamiliar && unfamiliarStandard++ >= 1) false else true
      }
      .take(candidateLimit)
      .map { it.row }
  }

  private fun sourceRank(
    candidate: Candidate,
    keys: Map<String, String>,
    preferences: Map<String, String>,
    history: Set<String>,
  ): Int =
    when {
      candidate.id in keys || preferences[candidate.id] == "MORE" -> 0
      candidate.id in history -> 1
      candidate.origin == "STANDARD" -> 2
      else -> 3
    }
}
