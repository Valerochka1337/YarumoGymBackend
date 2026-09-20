package tech.valerochkagym.service.ai

/**
 * Frozen, per-attempt planner inputs. Configuration never changes in the middle of an agent run.
 */
internal data class AdaptivePlannerContext(
  val configuration: PlannerConfiguration,
  val candidateIds: List<String>,
) {
  val patterns: Map<String, PlannerPattern> =
    configuration.collections.flatMap { it.patterns }.associateBy { it.id }
  private val collectionsByPatternId: Map<String, PlannerPatternCollection> =
    configuration.collections
      .flatMap { collection -> collection.patterns.map { it.id to collection } }
      .toMap()

  fun pattern(id: String): PlannerPattern = patterns[id] ?: throw aiError("ai_invalid_response")

  fun collectionFor(patternId: String): PlannerPatternCollection =
    collectionsByPatternId[patternId] ?: throw aiError("ai_invalid_response")

  companion object {
    fun create(
      configuration: PlannerConfiguration,
      candidateIds: List<String>,
    ): AdaptivePlannerContext {
      if (configuration.collections.isEmpty()) throw aiError("ai_context_stale")
      return AdaptivePlannerContext(configuration, candidateIds.sorted())
    }
  }
}
