package tech.valerochkagym.service.ai

import java.math.BigInteger
import kotlin.math.abs

enum class PlannerExerciseType {
  STRENGTH,
  TIMED,
  CARDIO,
}

enum class PlannerTerminalCode {
  NO_FEASIBLE_PLAN,
  PLANNER_LIMIT_REACHED,
  PLANNER_TIMEOUT,
}

data class DeterministicPlannerLimits(
  val structures: Int = 16,
  val candidatesPerSlot: Int = 8,
  val schemesPerSelection: Int = 12,
  val nodes: Int = 4096,
  val evaluations: Int = 256,
  val deadlineMillis: Long = 750,
) {
  init {
    require(structures in 1..32 && candidatesPerSlot in 1..24 && schemesPerSelection in 1..32)
    require(nodes in 64..262144 && evaluations in 1..4096 && deadlineMillis in 100..5000)
  }
}

data class DeterministicSlot(
  val slotId: String,
  val selectionId: String,
  val movementClass: String,
  val allowedTypes: Set<PlannerExerciseType>,
  val allowedEquipment: Set<String>,
  val minSets: Int,
  val maxSets: Int,
  val minReps: Int = 1,
  val maxReps: Int = 1,
  val allowedActiveSeconds: List<Int> = emptyList(),
  val allowedRestSeconds: List<Int>,
  val preferredSetCount: Int,
  val preferredRestSeconds: Int,
  val targetTotalReps: Int? = null,
  val targetIntensityBasisPoints: Int? = null,
  val targetActiveSeconds: Int? = null,
  val transitionSeconds: Int = 90,
  val role: String = "ACCESSORY",
  val requiredExerciseId: String? = null,
)

data class DeterministicCandidate(
  val exerciseId: String,
  val type: PlannerExerciseType,
  val movementClass: String,
  val equipmentIds: Set<String>,
  val accent: String = "NORMAL",
  val historyScore: Int = 0,
  val focus: Boolean = false,
  val repeated: Boolean = false,
  val roles: Set<String> = setOf("PRIMARY", "ACCESSORY", "CONDITIONING"),
)

data class DeterministicStructure(val id: String, val slots: List<DeterministicSlot>)

data class DeterministicScheme(
  val repetitions: List<Int> = emptyList(),
  val activeSeconds: List<Int> = emptyList(),
  val restSeconds: Int,
)

data class DeterministicSelection(
  val slot: DeterministicSlot,
  val exercise: DeterministicCandidate,
  val scheme: DeterministicScheme,
)

data class DeterministicPlan(
  val structureId: String,
  val selections: List<DeterministicSelection>,
  val durationSeconds: Long,
  val optimalityNotGuaranteed: Boolean,
  val nodes: Int,
  val evaluations: Int,
)

sealed interface DeterministicPlannerResult {
  data class Ready(val plan: DeterministicPlan) : DeterministicPlannerResult

  data class Terminal(val code: PlannerTerminalCode) : DeterministicPlannerResult
}

/**
 * Bounded, lexically tied enumeration. It contains no provider, random source, or name inference.
 */
class DeterministicPlannerEngine(
  private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
  fun plan(
    structures: List<DeterministicStructure>,
    candidates: List<DeterministicCandidate>,
    maxDurationSeconds: Long,
    limits: DeterministicPlannerLimits = DeterministicPlannerLimits(),
    minimumDurationSeconds: Long = maxDurationSeconds - maxOf(300L, maxDurationSeconds / 5),
    variant: Int = 0,
  ): DeterministicPlannerResult {
    val started = monotonicMillis()
    var nodes = 0
    var evaluations = 0
    var cap = false
    var best: DeterministicPlan? = null
    fun expired() = monotonicMillis() - started >= limits.deadlineMillis
    fun branch(): Boolean {
      if (expired()) return false
      nodes++
      return nodes <= limits.nodes
    }
    fun complete(selections: List<DeterministicSelection>, structureId: String): Boolean {
      if (expired()) return false
      evaluations++
      if (evaluations > limits.evaluations) return false
      val duration = duration(selections)
      if (duration > maxDurationSeconds) return true
      val candidate =
        DeterministicPlan(structureId, selections, duration, false, nodes, evaluations)
      if (best == null || better(candidate, best!!, candidates, minimumDurationSeconds))
        best = candidate
      return true
    }
    val sortedStructures = structures.sortedBy { it.id }
    val offset = if (sortedStructures.isEmpty()) 0 else variant % sortedStructures.size
    val orderedStructures =
      (sortedStructures.drop(offset) + sortedStructures.take(offset)).take(limits.structures)
    if (sortedStructures.size > limits.structures) cap = true
    val cache =
      mutableMapOf<Pair<DeterministicSlot, PlannerExerciseType>, List<DeterministicScheme>>()
    for (structure in orderedStructures) {
      if (structure.slots.isEmpty() || structure.slots.size > 12) continue
      fun visit(index: Int, chosen: List<DeterministicSelection>) {
        if (expired()) return
        if (index == structure.slots.size) {
          if (!complete(chosen, structure.id)) cap = true
          return
        }
        val slot = structure.slots[index]
        val eligible =
          candidates
            .asSequence()
            .filter { slot.requiredExerciseId == null || it.exerciseId == slot.requiredExerciseId }
            .filter { slot.role in it.roles }
            .filter {
              it.type in slot.allowedTypes &&
                it.movementClass == slot.movementClass &&
                it.accent != "NEVER"
            }
            .filter {
              slot.allowedEquipment.isEmpty() ||
                it.equipmentIds.any(slot.allowedEquipment::contains)
            }
            .sortedWith(
              compareBy<DeterministicCandidate>(
                { if (it.focus) 0 else 1 },
                { accentRank(it.accent) },
                { -it.historyScore },
                { it.exerciseId },
              )
            )
            .take(limits.candidatesPerSlot + 1)
            .toList()
        if (eligible.size > limits.candidatesPerSlot) cap = true
        for (exercise in eligible.take(limits.candidatesPerSlot)) {
          if (chosen.any { it.exercise.exerciseId == exercise.exerciseId }) continue
          if (!branch()) {
            cap = true
            return
          }
          val options =
            cache.getOrPut(slot to exercise.type) {
              val generated = schemes(slot, exercise.type, ::branch)
              if (generated.size > limits.schemesPerSelection) cap = true
              generated.take(limits.schemesPerSelection)
            }
          for (scheme in options) {
            if (!branch()) {
              cap = true
              return
            }
            visit(index + 1, chosen + DeterministicSelection(slot, exercise, scheme))
            if (nodes > limits.nodes || evaluations > limits.evaluations || expired()) return
          }
        }
      }
      visit(0, emptyList())
      if (nodes > limits.nodes || evaluations > limits.evaluations || expired()) break
    }
    if (nodes > limits.nodes || evaluations > limits.evaluations) cap = true
    if (expired()) return DeterministicPlannerResult.Terminal(PlannerTerminalCode.PLANNER_TIMEOUT)
    best?.let {
      return DeterministicPlannerResult.Ready(
        it.copy(optimalityNotGuaranteed = cap, nodes = nodes, evaluations = evaluations)
      )
    }
    return DeterministicPlannerResult.Terminal(
      if (cap) PlannerTerminalCode.PLANNER_LIMIT_REACHED else PlannerTerminalCode.NO_FEASIBLE_PLAN
    )
  }

  fun schemes(
    slot: DeterministicSlot,
    type: PlannerExerciseType,
    consume: () -> Boolean = { true },
  ): List<DeterministicScheme> {
    require(
      slot.minSets in 1..20 &&
        slot.maxSets in slot.minSets..20 &&
        slot.allowedRestSeconds.all { it in 0..600 }
    )
    val rests = slot.allowedRestSeconds.distinct().sorted()
    return when (type) {
      PlannerExerciseType.STRENGTH -> strengthSchemes(slot, rests, consume)
      PlannerExerciseType.TIMED,
      PlannerExerciseType.CARDIO -> timedSchemes(slot, rests, consume)
    }
  }

  private fun strengthSchemes(
    slot: DeterministicSlot,
    rests: List<Int>,
    consume: () -> Boolean,
  ): List<DeterministicScheme> {
    val targetReps = requireNotNull(slot.targetTotalReps).also { require(it > 0) }
    val targetIntensity = requireNotNull(slot.targetIntensityBasisPoints).also { require(it > 0) }
    val raw = mutableSetOf<List<Int>>()
    require(slot.minReps in 1..30 && slot.maxReps in slot.minReps..30)
    var stopped = false
    fun enumerate(count: Int, maximum: Int, current: List<Int>) {
      if (stopped) return
      if (!consume()) {
        stopped = true
        return
      }
      if (count == 0) {
        raw += StrengthSetProgression.descend(current, slot.minReps..slot.maxReps)
        return
      }
      for (rep in maximum downTo slot.minReps) enumerate(count - 1, rep, current + rep)
    }
    for (count in slot.minSets..slot.maxSets) enumerate(count, slot.maxReps, emptyList())
    return raw
      .map { reps -> reps to strengthLoss(reps, targetReps, targetIntensity) }
      .sortedWith(
        compareBy<Pair<List<Int>, Rational>>(
            { it.second },
            { abs(it.first.size - slot.preferredSetCount) },
          )
          .thenComparator { a, b -> lex(a.first, b.first) }
      )
      .flatMap { (reps, _) ->
        rests.map { DeterministicScheme(repetitions = reps, restSeconds = it) }
      }
      .sortedWith(
        compareBy<DeterministicScheme>(
            { strengthLoss(it.repetitions, targetReps, targetIntensity) },
            { abs(it.repetitions.size - slot.preferredSetCount) },
            { abs(it.restSeconds - slot.preferredRestSeconds) },
          )
          .thenComparator { a, b -> lex(a.repetitions, b.repetitions) }
      )
  }

  private fun timedSchemes(
    slot: DeterministicSlot,
    rests: List<Int>,
    consume: () -> Boolean,
  ): List<DeterministicScheme> {
    val target = requireNotNull(slot.targetActiveSeconds).also { require(it > 0) }
    val allowed =
      slot.allowedActiveSeconds.distinct().sorted().also {
        require(it.isNotEmpty() && it.all { seconds -> seconds in 1..14400 })
      }
    val out = mutableListOf<DeterministicScheme>()
    // Equal-duration sets are the declared finite family; no exponential mixed tuple search.
    for (count in slot.minSets..slot.maxSets) for (seconds in allowed) {
      if (!consume()) return out
      rests.forEach {
        out += DeterministicScheme(activeSeconds = List(count) { seconds }, restSeconds = it)
      }
    }
    return out.sortedWith(
      compareBy<DeterministicScheme>(
          { abs(it.activeSeconds.sum() - target) },
          { abs(it.activeSeconds.size - slot.preferredSetCount) },
          { abs(it.restSeconds - slot.preferredRestSeconds) },
        )
        .thenComparator { a, b -> lex(a.activeSeconds, b.activeSeconds) }
    )
  }

  private fun duration(selections: List<DeterministicSelection>): Long {
    var total = 0L
    selections.forEachIndexed { index, selection ->
      val scheme = selection.scheme
      val work =
        if (selection.exercise.type == PlannerExerciseType.STRENGTH)
          scheme.repetitions.sumOf { 45L }
        else scheme.activeSeconds.sumOf(Int::toLong)
      total = Math.addExact(total, work)
      total =
        Math.addExact(
          total,
          (maxOf(0, scheme.repetitions.size + scheme.activeSeconds.size - 1)).toLong() *
            scheme.restSeconds,
        )
      if (index > 0) total = Math.addExact(total, selection.slot.transitionSeconds.toLong())
    }
    return total
  }

  private fun better(
    candidate: DeterministicPlan,
    current: DeterministicPlan,
    candidates: List<DeterministicCandidate>,
    minimum: Long,
  ): Boolean {
    fun score(plan: DeterministicPlan): List<Long> {
      val ids = plan.selections.map { it.exercise.exerciseId }.toSet()
      val applicable =
        candidates.filter {
          it.focus &&
            plan.selections.any { s ->
              s.slot.movementClass == it.movementClass &&
                it.type in s.slot.allowedTypes &&
                s.slot.role in it.roles
            }
        }
      return listOf(
        1000L * plan.selections.sumOf { accentRank(it.exercise.accent) } / plan.selections.size,
        10L * applicable.count { it.exerciseId !in ids } +
          plan.selections.count {
            it.slot.role == "ACCESSORY" && !it.exercise.focus && it.exercise.repeated
          },
        maxOf(0L, minimum - plan.durationSeconds),
      )
    }
    val a = score(candidate)
    val b = score(current)
    for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
    return candidate.selections.joinToString("|") { it.exercise.exerciseId } <
      current.selections.joinToString("|") { it.exercise.exerciseId }
  }

  private fun lex(first: List<Int>, second: List<Int>): Int {
    for (index in 0 until minOf(first.size, second.size)) if (first[index] != second[index])
      return first[index].compareTo(second[index])
    return first.size.compareTo(second.size)
  }

  private fun accentRank(accent: String) =
    when (accent) {
      "MORE" -> 0
      "NORMAL" -> 1
      "LESS" -> 2
      else -> 3
    }

  private data class Rational(val numerator: BigInteger, val denominator: BigInteger) :
    Comparable<Rational> {
    override fun compareTo(other: Rational) =
      numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))
  }

  private fun strengthLoss(reps: List<Int>, targetReps: Int, targetIntensity: Int): Rational {
    val total = reps.sum()
    val volume = BigInteger.valueOf((total - targetReps).toLong()).pow(2)
    val intensityNumerator =
      reps.sumOf { rep -> rep.toLong() * (9750L - 350L * (rep - 1) + 5L * (rep - 1) * (rep - 1)) }
    val delta =
      BigInteger.valueOf(intensityNumerator)
        .subtract(BigInteger.valueOf(total.toLong() * targetIntensity))
    val denominator = BigInteger.valueOf(total.toLong() * 100L).pow(2)
    return Rational(volume.multiply(denominator).add(delta.pow(2)), denominator)
  }
}
