package tech.valerochkagym.service.ai

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.ApiException
import tools.jackson.databind.json.JsonMapper

/**
 * A deliberately small, process-local incident aid. It is not an audit trail: inputs, provider
 * bodies and identities never enter this object.
 */
@Service
class AiDiagnostics(
  private val clock: Clock = Clock.systemUTC(),
  @Value("\${BUILD_REVISION:unknown}") revision: String = "unknown",
) {
  val process =
    AiDiagnosticProcess(
      UUID.randomUUID().toString(),
      clock.instant(),
      revision.takeIf { it.matches(Regex("[a-f0-9]{40}")) } ?: "unknown",
    )
  private val logger = LoggerFactory.getLogger(AiDiagnostics::class.java)
  private val logJson = JsonMapper.builder().build()

  companion object {
    const val maxRuns = 200
    const val maxAgeHours = 24L
    const val maxEvents = 64
    private const val maxStages = 64
    private const val maxCounter = 10_000
    private const val maxDurationMillis = 86_400_000L
    private val allowedCodes =
      setOf(
        "invalid_json_schema",
        "invalid_request_error",
        "invalid_function_parameters",
        "model_not_found",
        "unsupported_parameter",
      )
    private val allowedTypes = setOf("invalid_request_error")
    private val allowedParams = setOf("response_format", "tools", "model", "max_completion_tokens")
  }

  internal data class StageEntry(
    val stage: AiDiagnosticStage,
    val startedAt: Instant,
    var outcome: AiDiagnosticOutcome = AiDiagnosticOutcome.RUNNING,
    var durationMs: Long = 0,
  )

  internal data class RunEntry(
    val id: UUID,
    val startedAt: Instant,
    var model: String?,
    var outcome: AiDiagnosticOutcome = AiDiagnosticOutcome.RUNNING,
    var failure: AiDiagnosticFailureCategory = AiDiagnosticFailureCategory.NONE,
    var httpStatus: Int? = null,
    var upstreamCode: String? = null,
    var upstreamType: String? = null,
    var upstreamParam: String? = null,
    var rounds: Int = 0,
    var toolCalls: Int = 0,
    var durationMs: Long = 0,
    var evicted: Boolean = false,
    val stages: MutableList<StageEntry> = mutableListOf(),
    val events: MutableList<AiDiagnosticEvent> = mutableListOf(),
    var droppedEvents: Int = 0,
    var tool: AiDiagnosticTool? = null,
  )

  private val lock = Any()
  private val runs = ArrayDeque<RunEntry>()
  private val current = ThreadLocal<RunEntry?>()

  /** Opens a typed stage. Nested stages retain the same random run ID. */
  fun open(stage: AiDiagnosticStage, model: String? = null): Scope {
    val previous = current.get()
    val now = clock.instant()
    return synchronized(lock) {
      purge(now)
      val run =
        previous
          ?: RunEntry(UUID.randomUUID(), now, safeModel(model)).also {
            runs.addLast(it)
            trim()
          }
      if (run.model == null) run.model = safeModel(model)
      val index =
        if (run.stages.size < maxStages) {
          run.stages += StageEntry(stage, now)
          run.stages.lastIndex
        } else -1
      current.set(run)
      Scope(run, previous, index)
    }
  }

  fun <T> observe(stage: AiDiagnosticStage, model: String? = null, block: () -> T): T {
    val scope =
      try {
        open(stage, model)
      } catch (_: Exception) {
        return block()
      }
    try {
      return block()
    } catch (error: Throwable) {
      try {
        scope.fail(error)
      } catch (_: Exception) {
        // Observation is always best effort.
      }
      throw error
    } finally {
      try {
        scope.close()
      } catch (_: Exception) {
        // Observation is always best effort.
      }
    }
  }

  /** Records only allowlisted HTTP metadata. Unknown values are dropped. */
  fun recordHttp(status: Int?, code: String? = null, type: String? = null, param: String? = null) {
    val run = current.get() ?: return
    synchronized(lock) {
      if (status in 100..599) run.httpStatus = status
      run.upstreamCode = code?.takeIf { it in allowedCodes }
      run.upstreamType = type?.takeIf { it in allowedTypes }
      run.upstreamParam = normalizeParam(param)
      recordFailure(run, upstreamFailure(status))
    }
  }

  fun recordFailure(category: AiDiagnosticFailureCategory) {
    current.get()?.let { run -> synchronized(lock) { recordFailure(run, category) } }
  }

  /** Only enum labels, server-generated field paths and bounded numbers can enter events. */
  fun event(
    site: AiDiagnosticSite,
    reason: AiDiagnosticReason,
    actual: Long? = null,
    minimum: Long? = null,
    maximum: Long? = null,
    field: String? = null,
  ) {
    try {
      val run = current.get() ?: return
      synchronized(lock) {
        if (run.events.size == maxEvents) {
          run.events.removeAt(0)
          run.droppedEvents++
        }
        val segment =
          "(?:result|name|exercises|exerciseId|restSeconds|plannedSets|reps|durationSec)"
        val safeField =
          field?.takeIf {
            it.length <= 200 && it.matches(Regex("$segment(?:\\[[0-9]{1,3}\\]|\\.$segment)*"))
          }
        fun bounded(value: Long?) = value?.takeIf { it in 0..86_400_000 }
        run.events +=
          AiDiagnosticEvent(
            site,
            reason,
            run.rounds,
            run.tool,
            bounded(actual),
            bounded(minimum),
            bounded(maximum),
            safeField,
          )
      }
    } catch (_: Exception) {
      /* Diagnostics must never change planner behavior. */
    }
  }

  fun reject(
    site: AiDiagnosticSite,
    reason: AiDiagnosticReason,
    actual: Long? = null,
    minimum: Long? = null,
    maximum: Long? = null,
    field: String? = null,
  ): Nothing {
    event(site, reason, actual, minimum, maximum, field)
    throw aiError("ai_invalid_response")
  }

  internal fun <T> withTool(name: String, block: () -> T): T {
    val run = current.get() ?: return block()
    val previous = run.tool
    run.tool =
      AiDiagnosticTool.entries.firstOrNull { it.wireName == name } ?: AiDiagnosticTool.UNKNOWN
    try {
      return block()
    } finally {
      run.tool = previous
    }
  }

  fun recordRound() = increment { it.rounds++ }

  fun recordToolCall() = increment { it.toolCalls++ }

  /**
   * The masked calendar-job catch may retain this local category, but never an exception message.
   */
  fun recordLocalFailure(error: Throwable) {
    if (error is ApiException) return
    recordFailure(
      when (error) {
        is DataAccessException -> AiDiagnosticFailureCategory.DATABASE
        is IOException -> AiDiagnosticFailureCategory.TRANSPORT
        is IllegalArgumentException -> AiDiagnosticFailureCategory.VALIDATION
        else -> AiDiagnosticFailureCategory.INTERNAL
      }
    )
  }

  fun snapshot(): List<AiDiagnosticRun> =
    synchronized(lock) {
      val now = clock.instant()
      purge(now)
      Collections.unmodifiableList(runs.map { run -> run.copyForApi(now) })
    }

  inner class Scope
  internal constructor(
    private val run: RunEntry,
    private val previous: RunEntry?,
    private val stageIndex: Int,
  ) : AutoCloseable {
    private var closed = false

    fun fail(error: Throwable) {
      val category = categoryFor(error)
      synchronized(lock) {
        recordFailure(run, category)
        if (stageIndex >= 0) {
          run.stages[stageIndex].outcome =
            if (category == AiDiagnosticFailureCategory.AI_INTERRUPTED)
              AiDiagnosticOutcome.CANCELLED
            else AiDiagnosticOutcome.FAILURE
        }
      }
    }

    override fun close() {
      if (closed) return
      closed = true
      try {
        synchronized(lock) {
          val now = clock.instant()
          if (stageIndex >= 0) {
            val stage = run.stages[stageIndex]
            stage.durationMs = elapsed(stage.startedAt, now)
            if (stage.outcome == AiDiagnosticOutcome.RUNNING)
              stage.outcome = AiDiagnosticOutcome.SUCCESS
          }
          if (previous == null) {
            run.durationMs = elapsed(run.startedAt, now)
            run.outcome =
              when {
                run.failure == AiDiagnosticFailureCategory.NONE -> AiDiagnosticOutcome.SUCCESS
                run.failure == AiDiagnosticFailureCategory.AI_INTERRUPTED ->
                  AiDiagnosticOutcome.CANCELLED
                else -> AiDiagnosticOutcome.FAILURE
              }
          }
          purge(now)
          if (previous == null) {
            // No model text, account IDs, exception messages or tool arguments in this record.
            logger.info(
              "AI_DIAGNOSTIC {}",
              logJson.writeValueAsString(
                mapOf(
                  "runId" to run.id.toString(),
                  "startedAt" to run.startedAt.toString(),
                  "revision" to process.revision,
                  "processId" to process.id,
                  "outcome" to run.outcome,
                  "category" to run.failure,
                  "rounds" to run.rounds,
                  "toolCalls" to run.toolCalls,
                  "durationMs" to run.durationMs,
                  "events" to run.events.toList(),
                  "droppedEvents" to run.droppedEvents,
                )
              ),
            )
          }
        }
      } catch (_: Exception) {
        // Observation is always best effort.
      } finally {
        if (previous == null) current.remove() else current.set(previous)
      }
    }
  }

  private fun increment(change: (RunEntry) -> Unit) {
    current.get()?.let { run ->
      synchronized(lock) {
        change(run)
        run.rounds = run.rounds.coerceIn(0, maxCounter)
        run.toolCalls = run.toolCalls.coerceIn(0, maxCounter)
      }
    }
  }

  private fun purge(now: Instant) {
    while (
      runs.firstOrNull()?.startedAt?.isBefore(now.minus(Duration.ofHours(maxAgeHours))) == true
    ) {
      runs.removeFirst().evicted = true
    }
  }

  private fun trim() {
    while (runs.size > maxRuns) runs.removeFirst().evicted = true
  }

  private fun RunEntry.copyForApi(now: Instant) =
    AiDiagnosticRun(
      id.toString(),
      startedAt,
      if (outcome == AiDiagnosticOutcome.RUNNING) elapsed(startedAt, now) else durationMs,
      outcome,
      if (outcome == AiDiagnosticOutcome.RUNNING) AiDiagnosticFailureCategory.NONE else failure,
      model,
      httpStatus,
      upstreamCode,
      upstreamType,
      upstreamParam,
      rounds,
      toolCalls,
      Collections.unmodifiableList(
        stages.map { stage ->
          AiDiagnosticStageSnapshot(
            stage.stage,
            stage.outcome,
            if (stage.outcome == AiDiagnosticOutcome.RUNNING) elapsed(stage.startedAt, now)
            else stage.durationMs,
          )
        }
      ),
      Collections.unmodifiableList(events.toList()),
      droppedEvents,
    )

  private fun elapsed(startedAt: Instant, endedAt: Instant): Long =
    Duration.between(startedAt, endedAt).toMillis().coerceIn(0, maxDurationMillis)

  private fun safeModel(value: String?): String? =
    value?.takeIf { it.length in 1..200 && it.all { char -> char.code >= 32 && char.code != 127 } }

  private fun normalizeParam(value: String?): String? =
    when {
      value in allowedParams -> value
      value?.startsWith("response_format.") == true -> "response_format"
      value?.startsWith("tools[") == true -> "tools"
      else -> null
    }

  private fun recordFailure(run: RunEntry, category: AiDiagnosticFailureCategory) {
    if (category == AiDiagnosticFailureCategory.NONE) return
    if (specificity(category) > specificity(run.failure)) run.failure = category
  }

  private fun categoryFor(error: Throwable): AiDiagnosticFailureCategory =
    when (error) {
      is InterruptedException,
      is CancellationException -> AiDiagnosticFailureCategory.AI_INTERRUPTED
      is ApiException ->
        when (error.code) {
          "ai_timeout" -> AiDiagnosticFailureCategory.AI_TIMEOUT
          "ai_busy" -> AiDiagnosticFailureCategory.AI_BUSY
          "ai_interrupted" -> AiDiagnosticFailureCategory.AI_INTERRUPTED
          "ai_invalid_response" -> AiDiagnosticFailureCategory.AI_INVALID_RESPONSE
          "ai_context_stale" -> AiDiagnosticFailureCategory.AI_CONTEXT_STALE
          "ai_context_too_large" -> AiDiagnosticFailureCategory.AI_CONTEXT_TOO_LARGE
          else -> AiDiagnosticFailureCategory.AI_UNAVAILABLE
        }
      is DataAccessException -> AiDiagnosticFailureCategory.DATABASE
      is IOException -> AiDiagnosticFailureCategory.TRANSPORT
      is IllegalArgumentException -> AiDiagnosticFailureCategory.VALIDATION
      else -> AiDiagnosticFailureCategory.INTERNAL
    }

  private fun upstreamFailure(status: Int?): AiDiagnosticFailureCategory =
    when {
      status == 408 || status == 504 -> AiDiagnosticFailureCategory.AI_TIMEOUT
      status == 429 -> AiDiagnosticFailureCategory.AI_BUSY
      status in 400..499 -> AiDiagnosticFailureCategory.UPSTREAM_REJECTED
      else -> AiDiagnosticFailureCategory.UPSTREAM_UNAVAILABLE
    }

  private fun specificity(category: AiDiagnosticFailureCategory): Int =
    when (category) {
      AiDiagnosticFailureCategory.NONE -> 0
      AiDiagnosticFailureCategory.AI_UNAVAILABLE -> 1
      AiDiagnosticFailureCategory.AI_TIMEOUT,
      AiDiagnosticFailureCategory.AI_BUSY,
      AiDiagnosticFailureCategory.AI_INTERRUPTED,
      AiDiagnosticFailureCategory.AI_INVALID_RESPONSE,
      AiDiagnosticFailureCategory.AI_CONTEXT_STALE,
      AiDiagnosticFailureCategory.AI_CONTEXT_TOO_LARGE -> 2
      else -> 3
    }
}

enum class AiDiagnosticOutcome {
  RUNNING,
  SUCCESS,
  FAILURE,
  CANCELLED,
}

enum class AiDiagnosticFailureCategory {
  NONE,
  AI_UNAVAILABLE,
  AI_TIMEOUT,
  AI_BUSY,
  AI_INTERRUPTED,
  AI_INVALID_RESPONSE,
  AI_CONTEXT_STALE,
  AI_CONTEXT_TOO_LARGE,
  UPSTREAM_REJECTED,
  UPSTREAM_UNAVAILABLE,
  DATABASE,
  TRANSPORT,
  VALIDATION,
  PROVIDER_UNCONFIGURED,
  INTERNAL,
}

enum class AiDiagnosticStage {
  CALENDAR_JOB,
  CALENDAR_CREATE,
  CALENDAR_REFINE,
  PLANNER_TURN,
  PLANNER_TOOL,
  PROVIDER_HTTP,
}

data class AiDiagnosticsResponse(
  val generatedAt: Instant,
  val retention: AiDiagnosticRetention,
  val database: AiDiagnosticDatabase,
  val calendarQueue: AiDiagnosticCalendarQueue,
  val runs: List<AiDiagnosticRun>,
  val process: AiDiagnosticProcess? = null,
)

data class AiDiagnosticRetention(
  val maxRuns: Int,
  val maxAgeHours: Long,
  val processLocal: Boolean,
  val lostOnRestart: Boolean,
)

data class AiDiagnosticDatabase(val status: String)

data class AiDiagnosticCalendarQueue(
  val status: String,
  val queued: Long?,
  val running: Long?,
  val failed: Long?,
  val ready: Long?,
)

data class AiDiagnosticRun(
  val id: String,
  val startedAt: Instant,
  val durationMs: Long,
  val outcome: AiDiagnosticOutcome,
  val failureCategory: AiDiagnosticFailureCategory,
  val model: String?,
  val httpStatus: Int?,
  val upstreamCode: String?,
  val upstreamType: String?,
  val upstreamParam: String?,
  val rounds: Int,
  val toolCalls: Int,
  val stages: List<AiDiagnosticStageSnapshot>,
  val events: List<AiDiagnosticEvent> = emptyList(),
  val droppedEvents: Int = 0,
)

data class AiDiagnosticStageSnapshot(
  val stage: AiDiagnosticStage,
  val outcome: AiDiagnosticOutcome,
  val durationMs: Long,
)

data class AiDiagnosticProcess(val id: String, val startedAt: Instant, val revision: String)

enum class AiDiagnosticSite {
  AGENT_LOOP,
  TOOL_PROTOCOL,
  PROVIDER_RESPONSE,
  PLAN_SCHEMA,
  PLAN_VALIDATION,
  FINALIZATION,
}

enum class AiDiagnosticTool(val wireName: String) {
  GET_STRENGTH_SKELETON("get_strength_skeleton"),
  GET_CANDIDATE_DETAILS_AND_HISTORY("get_candidate_details_and_history"),
  VALIDATE_AND_FINALIZE_PLAN("validate_and_finalize_plan"),
  UNKNOWN(""),
}

enum class AiDiagnosticReason {
  ROUND_BUDGET,
  TOOL_CALL_BUDGET,
  TOOL_STARTED,
  TOOL_COMPLETED,
  PLAN_ACCEPTED,
  PLAN_REJECTED,
  ROUND_LIMIT,
  TOOL_CALL_LIMIT,
  TOOL_RESULT_SIZE,
  TRANSCRIPT_SIZE,
  EMPTY_TURN,
  FINAL_WITH_TOOLS,
  DUPLICATE_TOOL_CALL,
  INVALID_TOOL_ARGUMENTS,
  INVALID_TOOL_ID,
  UNKNOWN_TOOL,
  TOOL_ARGUMENT_SIZE,
  INVALID_CANDIDATE_IDS,
  UNKNOWN_CANDIDATE,
  UNKNOWN_PATTERN,
  INVALID_PROVIDER_RESPONSE,
  SCHEMA_MISMATCH,
  PATTERN_MISSING,
  FINALIZATION_MISSING,
  FINAL_PLAN_MISMATCH,
  REFINEMENT_UNCHANGED,
  INVALID_PLAN_SHAPE,
  UNKNOWN_EXERCISE,
  DUPLICATE_EXERCISE,
  INVALID_REST,
  INVALID_SET_COUNT,
  INVALID_SET_VALUES,
  DURATION_TOO_SHORT,
  DURATION_TOO_LONG,
}

data class AiDiagnosticEvent(
  val site: AiDiagnosticSite,
  val reason: AiDiagnosticReason,
  val round: Int,
  val tool: AiDiagnosticTool?,
  val actual: Long?,
  val minimum: Long?,
  val maximum: Long?,
  val field: String?,
)
