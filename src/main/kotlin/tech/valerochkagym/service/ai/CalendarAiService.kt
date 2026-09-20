package tech.valerochkagym.service.ai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.*
import tech.valerochkagym.repository.ai.CalendarAiAttemptRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.model.CalendarAiAttemptEntity
import tech.valerochkagym.repository.model.CalendarAiAttemptId
import tech.valerochkagym.repository.model.CalendarAiAttemptState
import tech.valerochkagym.repository.model.CalendarPlannerRefinementEntity
import tech.valerochkagym.repository.trainingproposal.CalendarPlannerRefinementRepository
import tech.valerochkagym.service.model.Identity
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The calendar route's durable idempotency boundary. Provider data never enters the ledger. */
@Service
class CalendarAiService(
  private val attempts: CalendarAiAttemptRepository,
  private val refinements: CalendarPlannerRefinementRepository,
  private val catalog: CatalogStateRepository,
  private val heads: HeadRepository,
  private val contexts: AiContextReader,
  private val provider: AiProvider,
  private val creator: TrainingProposalAiCreator,
  private val tx: TransactionTemplate,
  private val json: ObjectMapper,
  private val validator: AiDraftValidator,
  private val clock: Clock,
  private val sessionGuard: tech.valerochkagym.service.auth.IdentitySessionGuard,
  private val hooks: CalendarAiExecutionHooks,
  private val explanations: PlannerExplanationStore,
  private val jdbc: JdbcTemplate,
  private val plannerConfiguration: PlannerConfigurationService,
  private val aiSettings: AiSettingsService,
  private val diagnostics: AiDiagnostics = AiDiagnostics(),
) {
  /**
   * V2 is additive: the existing durable attempt/45s receipt owns generation. Projection is built
   * from the committed draft and is intentionally strict rather than best-effort reconstructed.
   */
  fun createV2(identity: Identity, raw: ByteArray): CalendarDraftV2Response {
    val request = parse(raw)
    var frozen: Pair<List<String>, StrengthPlannerSkeleton>? = null
    val response =
      create(
        identity,
        raw,
        agentic = true,
        projectionCaptured = { ids, skeleton -> frozen = ids to skeleton },
      )
    val (ids, skeleton) =
      frozen
        ?: run {
          val stored =
            attempts
              .findById(CalendarAiAttemptId(identity.userId, UUID.fromString(request.requestId)))
              .orElse(null)
          stored?.v2Receipt?.let {
            return json.readValue(it, CalendarDraftV2Response::class.java)
          }
          throw aiError("ai_invalid_response")
        }
    return CalendarDraftV2Response(
      response.requestId,
      response.context,
      response.proposal,
      AgenticProjection(
        ids,
        AgenticSkeleton(
          skeleton.focusExerciseId,
          skeleton.slots.map {
            AgenticSkeletonSlot(
              it.slotId,
              it.allowedExerciseIds,
              it.minDurationSec,
              it.maxDurationSec,
            )
          },
          skeleton.minDurationSec,
          skeleton.maxDurationSec,
        ),
      ),
    )
  }

  fun refine(identity: Identity, proposalId: UUID, raw: ByteArray): ProposalResponse =
    diagnostics.observe(AiDiagnosticStage.CALENDAR_REFINE) {
      refineUnobserved(identity, proposalId, raw)
    }

  private fun refineUnobserved(
    identity: Identity,
    proposalId: UUID,
    raw: ByteArray,
  ): ProposalResponse {
    val request = parseRefinement(raw)
    if (
      !canonicalUuid(request.requestId) ||
        request.expectedRevision < 0 ||
        request.expectedCatalogRevision < 0 ||
        request.expectedProposalVersion < 1 ||
        request.refinement != request.refinement.trim() ||
        request.refinement.length !in 1..2000
    )
      bad("Некорректный запрос")
    val runtime = frozenPlannerConfiguration()
    val reservation = reserveRefinement(identity, proposalId, request, raw, runtime)
    reservation.replay?.let {
      return it
    }
    return try {
      refineReserved(identity, proposalId, request, raw, reservation.deadlineAt, runtime)
    } catch (error: Exception) {
      // Keep the first raw binding even when provider/validation work fails. Retrying these same
      // bytes is terminal rather than a new provider attempt; differing bytes always conflict.
      abortRefinement(identity, UUID.fromString(request.requestId))
      throw error
    }
  }

  /**
   * Runs outside the reservation transaction: context and provider I/O must never hold DB locks.
   */
  private fun refineReserved(
    identity: Identity,
    proposalId: UUID,
    request: CalendarRefinementRequest,
    raw: ByteArray,
    deadlineAt: Instant,
    runtime: PlannerConfiguration,
  ): ProposalResponse {
    remainingRefinementMillis(deadlineAt)
    val current = creator.detail(identity, proposalId)
    if (
      current.source != "AI" ||
        current.status != "PENDING" ||
        current.currentVersion != request.expectedProposalVersion
    )
      throw aiError("ai_context_stale")
    remainingRefinementMillis(deadlineAt)
    val base = current.snapshot.draft
    val plannerRequest =
      CalendarDraftRequest(
        request.requestId,
        request.expectedRevision,
        request.expectedCatalogRevision,
        base.startsAtMillis,
        base.timeZoneId,
        base.gymIds,
        emptyList(),
        emptyList(),
        emptyList(),
        false,
        240,
        null,
        null,
      )
    val captured =
      contexts.captureCalendar(
        identity,
        request.expectedRevision,
        request.expectedCatalogRevision,
        base.timeZoneId,
        false,
        base.gymIds,
        runtime.historyDays,
        runtime.detailDays,
      )
    remainingRefinementMillis(deadlineAt)
    contexts.verifyCalendarAdmission(
      identity,
      request.expectedRevision,
      request.expectedCatalogRevision,
    )
    remainingRefinementMillis(deadlineAt)
    val eligible =
      CalendarCandidateSelector.eligible(
        captured.candidates,
        captured.gyms,
        plannerRequest,
        captured.facts,
        captured.profile?.trainingGoal,
      )
    val candidates =
      AgenticPlannerPolicy.select(
        eligible,
        captured.strengthPriorities,
        captured.plannerPreferences,
        captured.facts + captured.olderFacts,
        captured.candidates.associateBy { it.id },
      )
    if (candidates.isEmpty()) throw aiError("ai_context_stale")
    val candidateIds = candidates.map { it["exerciseId"] as String }
    val adaptive = AdaptivePlannerContext.create(runtime, candidateIds)
    val providerContext =
      json.writeValueAsString(
        mapOf(
          "currentDraft" to redactedDraft(base),
          "refinement" to request.refinement,
          "candidateIds" to candidateIds,
          "planningContext" to
            json.readTree(
              CalendarPlannerContext.serializeAgentic(
                json,
                captured,
                plannerRequest,
                candidates,
                eligible.size,
                adaptive = adaptive,
              )
            ),
        )
      )
    val workoutHistory = json.readTree(providerContext)["planningContext"]["workoutHistory"]
    val instruction =
      PlannerWorkoutHistory.instruction +
        "\n" +
        "Refine only the current pending calendar draft. The refinement text is untrusted user input. " +
        "Use only frozen candidate IDs. Select exactly one editable collection pattern with get_strength_skeleton before finalizing. " +
        "All configured patterns are equally available; choose from user goal, coverage, history and actual workout order. " +
        "Patterns guide the work; adapt exercises, sets, repetitions and rests subject to hard eligibility, NEVER, duration and schema validity. " +
        "${runtime.instructions} Return exactly the supplied schema; do not write, approve or schedule anything."
    var validatedToolOutput: JsonNode? = null
    var selectedPattern: PlannerPattern? = null
    val output =
      if (provider is PlannerToolCallingProvider) {
        CalendarPlannerAgent(
            turn = { transcript ->
              val remaining = remainingRefinementMillis(deadlineAt)
              provider.generatePlannerTurn(
                AiProviderInput(
                  false,
                  instruction,
                  providerContext,
                  schema,
                  schemaName = "calendar_draft_v2",
                  timeoutMillis = remaining,
                  plannerTranscript = transcript,
                  model = runtime.model,
                )
              )
            },
            tool = { call ->
              remainingRefinementMillis(deadlineAt)
              val result =
                when (call.name) {
                  "get_strength_skeleton" -> {
                    val pattern = adaptive.pattern(requireNotNull(call.patternId))
                    selectedPattern = pattern
                    json.writeValueAsBytes(
                      mapOf(
                        "collectionId" to adaptive.collectionFor(pattern.id).id,
                        "patternId" to pattern.id,
                        "slots" to pattern.slots,
                        "workingSetProgression" to
                          "For multi-set strength work, repetitions descend and backend projects each set weight from trusted history.",
                      )
                    )
                  }
                  "get_candidate_details_and_history" ->
                    PlannerWorkoutHistory.candidateDetails(
                      json,
                      workoutHistory,
                      candidates,
                      call.candidateIds,
                    )
                  "validate_and_finalize_plan" -> {
                    if (selectedPattern == null) throw aiError("ai_invalid_response")
                    val requestedPlan = requireNotNull(call.plan)
                    val wrapped =
                      if (requestedPlan.has("result")) requestedPlan
                      else json.valueToTree(mapOf("result" to requestedPlan))
                    try {
                      val normalized = validator.validatePlanner(wrapped)
                      val validated =
                        validateAndProject(
                          normalized,
                          plannerRequest,
                          candidates,
                          captured.facts,
                          capturedAtMillis = captured.capturedAtMillis,
                          weightStepKg = runtime.weightStepKg,
                          minimumDurationRequired = true,
                          pattern = selectedPattern,
                        )
                      validatedToolOutput = normalized
                      json.writeValueAsBytes(
                        mapOf(
                          "valid" to true,
                          "details" to
                            mapOf(
                              "durationSec" to PlannerDuration.seconds(validated.exercises),
                              "focusExerciseId" to validated.exercises.first().exerciseId,
                            ),
                        )
                      )
                    } catch (error: ApiException) {
                      json.writeValueAsBytes(
                        mapOf(
                          "valid" to false,
                          "code" to error.code,
                          "details" to mapOf("accepted" to false),
                        )
                      )
                    }
                  }
                  else -> throw aiError("ai_invalid_response")
                }
              remainingRefinementMillis(deadlineAt)
              result
            },
            maxRounds = runtime.maxRounds,
            maxCalls = runtime.maxToolCalls,
            diagnostics = diagnostics,
          )
          .run(candidateIds.toSet(), adaptive.patterns.keys) {
            remainingRefinementMillis(deadlineAt)
          }
          .also { if (selectedPattern == null) throw aiError("ai_invalid_response") }
      } else throw aiError("ai_unavailable")
    remainingRefinementMillis(deadlineAt)
    val validatedOutput = validator.validatePlanner(output)
    if (validatedOutput != validatedToolOutput) throw aiError("ai_invalid_response")
    remainingRefinementMillis(deadlineAt)
    val revised =
      validateAndProject(
        validatedOutput,
        plannerRequest,
        candidates,
        captured.facts,
        capturedAtMillis = captured.capturedAtMillis,
        weightStepKg = runtime.weightStepKg,
        minimumDurationRequired = true,
        pattern = selectedPattern,
      )
    remainingRefinementMillis(deadlineAt)
    if (revised == base) throw aiError("ai_invalid_response")
    remainingRefinementMillis(deadlineAt)
    hooks.beforeRefinementCommit()
    return creator.refine(
      InternalCalendarAiRefinementRequest(
        identity,
        proposalId,
        request.expectedProposalVersion,
        request.expectedRevision,
        request.expectedCatalogRevision,
        UUID.fromString(request.requestId),
        raw.copyOf(),
        raw.sha256(),
        revised,
      )
    )
  }

  /** Durable idempotency is claimed before any context/provider work; no DB lock spans I/O. */
  private fun reserveRefinement(
    identity: Identity,
    proposalId: UUID,
    request: CalendarRefinementRequest,
    raw: ByteArray,
    runtime: PlannerConfiguration,
  ): RefinementReservation {
    val requestId = UUID.fromString(request.requestId)
    val digest = raw.sha256()
    return tx.execute {
      val now = Instant.now(clock)
      // There is no row to lock for a first claim. Serialize this owner/request key before the
      // lookup/insert, matching TrainingProposalService's refinement key. This transaction ends
      // before context/provider I/O, so the advisory lock never spans network work.
      jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        { _, _ -> Unit },
        "planner-refinement:${identity.userId}:$requestId",
      )
      val existing = refinements.writeLock(identity.userId, requestId)
      when (
        val claim =
          RefinementReceiptPolicy.claim(
            existing?.let {
              RefinementReceiptPolicy.Existing(
                it.proposalId,
                it.expectedVersion,
                it.requestSha256,
                it.rawRequest,
                it.receipt,
                it.leaseUntil,
              )
            },
            RefinementReceiptPolicy.Binding(
              proposalId,
              request.expectedProposalVersion,
              digest,
              raw,
            ),
            now,
          )
      ) {
        RefinementReceiptPolicy.Claim.Reserve -> {
          refinements.save(
            CalendarPlannerRefinementEntity(
              identity.userId,
              requestId,
              proposalId,
              request.expectedProposalVersion,
              digest,
              raw.copyOf(),
              null,
              now.plusSeconds(runtime.timeoutSeconds.toLong()),
              now,
            )
          )
          refinements.flush()
          jdbc.update(
            "UPDATE calendar_planner_refinements SET plan_config_json=? WHERE owner_id=? AND request_id=?",
            json.writeValueAsString(runtime),
            identity.userId,
            requestId,
          )
          RefinementReservation(deadlineAt = now.plusSeconds(runtime.timeoutSeconds.toLong()))
        }
        is RefinementReceiptPolicy.Claim.Replay ->
          RefinementReservation(
            deadlineAt = requireNotNull(existing).leaseUntil,
            replay = json.readValue(claim.receipt, ProposalResponse::class.java),
          )
        RefinementReceiptPolicy.Claim.InProgress -> throw aiError("ai_in_progress")
        RefinementReceiptPolicy.Claim.Interrupted -> throw aiError("ai_interrupted")
        RefinementReceiptPolicy.Claim.Conflict -> throw aiError("ai_request_conflict")
      }
    }
  }

  private data class RefinementReservation(
    val deadlineAt: Instant,
    val replay: ProposalResponse? = null,
  )

  private fun remainingRefinementMillis(deadlineAt: Instant): Long {
    val remaining = deadlineAt.toEpochMilli() - clock.millis()
    if (remaining <= 0) throw aiError("ai_timeout")
    return remaining
  }

  private fun abortRefinement(identity: Identity, requestId: UUID) {
    tx.executeWithoutResult {
      val existing =
        refinements.writeLock(identity.userId, requestId) ?: return@executeWithoutResult
      // A concurrent winner may have committed while this caller was unwinding. Never overwrite
      // its replay receipt; an incomplete claim becomes a durable expired terminal receipt.
      if (existing.receipt == null) {
        existing.leaseUntil = Instant.now(clock)
        refinements.save(existing)
      }
    }
  }

  private fun parseRefinement(raw: ByteArray): CalendarRefinementRequest {
    if (raw.isEmpty() || raw.size > 16_384) bad("Некорректный запрос")
    val root =
      try {
        json
          .tokenStreamFactory()
          .rebuild()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build()
          .createParser(raw)
          .use { parser ->
            json.readTree(parser).also {
              if (parser.nextToken() != null) bad("Некорректный запрос")
            }
          }
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    val fields =
      setOf(
        "requestId",
        "expectedRevision",
        "expectedCatalogRevision",
        "expectedProposalVersion",
        "refinement",
      )
    if (!root.isObject || root.properties().map { it.key }.toSet() != fields)
      bad("Некорректный запрос")
    return try {
      json.treeToValue(root, CalendarRefinementRequest::class.java)
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
  }

  private val schema: JsonNode by lazy {
    javaClass.getResourceAsStream("/ai/calendar-planner-output-v3.json")!!.use(json::readTree)
  }

  internal fun create(
    identity: Identity,
    raw: ByteArray,
    agentic: Boolean = false,
    projectionCaptured: ((List<String>, StrengthPlannerSkeleton) -> Unit)? = null,
    publicationGuard: () -> Unit = {},
    publish: (CalendarDraftResponse) -> Unit = {},
  ): CalendarDraftResponse =
    diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {
      createUnobserved(identity, raw, agentic, projectionCaptured, publicationGuard, publish)
    }

  private fun createUnobserved(
    identity: Identity,
    raw: ByteArray,
    agentic: Boolean = false,
    projectionCaptured: ((List<String>, StrengthPlannerSkeleton) -> Unit)? = null,
    publicationGuard: () -> Unit = {},
    publish: (CalendarDraftResponse) -> Unit = {},
  ): CalendarDraftResponse {
    val request = parse(raw)
    val digest = raw.sha256()
    val runtime = frozenPlannerConfiguration()
    val attempt = reserve(identity, request, digest, runtime)
    attempt.replay?.let {
      return it
    }
    return try {
      hooks.afterReserve()
      val captured =
        contexts.captureCalendar(
          identity,
          request.expectedRevision,
          request.expectedCatalogRevision,
          request.timeZoneId,
          request.includeNotes,
          request.gymIds,
          runtime.historyDays,
          runtime.detailDays,
        )
      hooks.afterCapture()
      contexts.verifyCalendarAdmission(
        identity,
        request.expectedRevision,
        request.expectedCatalogRevision,
      )
      val eligible =
        CalendarCandidateSelector.eligible(
          captured.candidates,
          captured.gyms,
          request,
          captured.facts,
          captured.profile?.trainingGoal,
        )
      val isStrength = captured.profile?.trainingGoal == "STRENGTH"
      val eligibleById = eligible.associateBy { it["exerciseId"] as String }
      val strengthKeys =
        captured.strengthPriorities.filterKeys { eligibleById[it]?.get("type") == "STRENGTH" }
      val rankingFacts = captured.facts + captured.olderFacts
      val agenticEligible =
        if (agentic)
          AgenticPlannerPolicy.select(
            eligible,
            captured.strengthPriorities,
            captured.plannerPreferences,
            rankingFacts,
            captured.candidates.associateBy { it.id },
          )
        else eligible
      val keyHistory =
        if (isStrength && strengthKeys.isNotEmpty())
          contexts
            .captureStrengthPlannerFacts(
              identity,
              request.expectedRevision,
              request.expectedCatalogRevision,
              strengthKeys.keys,
              captured.capturedAtMillis,
              emptySet(),
            )
            .latestFacts
        else emptyList()
      val selection =
        if (isStrength) {
          if (agenticEligible.none { it["type"] == "STRENGTH" }) throw aiError("ai_context_stale")
          StrengthPlannerFacts.select(
            agenticEligible.map { row ->
              @Suppress("UNCHECKED_CAST") val muscles = row["muscles"] as List<Map<String, Any>>
              StrengthPlannerFacts.Candidate(
                row["exerciseId"] as String,
                row["type"] as String,
                muscles.associate { it["muscle"] as String to it["contribution"] as Int },
              )
            },
            (rankingFacts + keyHistory).distinctBy {
              listOf(it.workoutId, it.sectionId, it.setIndex)
            },
            captured.workouts.map { StrengthPlannerFacts.Workout(it.id, it.finishedAtMillis) },
            strengthKeys,
            request.availableDurationMinutes,
            captured.capturedAtMillis,
          )
        } else null
      val candidates =
        if (selection != null) selection.ranked.map { eligibleById.getValue(it.exerciseId) }
        else if (agentic) agenticEligible
        else
          CalendarCandidateSelector.select(
            eligible,
            rankingFacts,
            listOfNotNull(
                request.preferences,
                request.currentState,
                captured.profile?.manualConstraints,
              )
              .joinToString("\n"),
          )
      if (candidates.isEmpty()) throw aiError("ai_context_stale")
      val adaptive =
        if (agentic)
          AdaptivePlannerContext.create(runtime, candidates.map { it["exerciseId"] as String })
        else null
      val strengthCapture =
        if (isStrength)
          contexts.captureStrengthPlannerFacts(
            identity,
            request.expectedRevision,
            request.expectedCatalogRevision,
            candidates.mapTo(mutableSetOf()) { it["exerciseId"] as String } + strengthKeys.keys,
            captured.capturedAtMillis,
            captured.workouts.mapTo(mutableSetOf()) { it.id },
          )
        else null
      val compact =
        strengthCapture?.let { history ->
          @Suppress("UNCHECKED_CAST")
          val muscles =
            captured.candidates.associate { source ->
              source.id to
                source.payload["muscles"]
                  ?.toList()
                  .orEmpty()
                  .mapNotNull { muscle ->
                    muscle["muscle"]?.asString()?.let { name ->
                      muscle["contribution"]?.asInt()?.let { contribution -> name to contribution }
                    }
                  }
                  .toMap()
            }
          StrengthPlannerFacts.compact(
            captured.facts,
            candidates.mapTo(mutableSetOf()) { it["exerciseId"] as String },
            strengthKeys.keys,
            captured.capturedAtMillis,
            muscles,
            history.efforts,
            history.latestFacts,
          )
        }
      val projectionFacts = strengthCapture?.latestFacts ?: captured.facts
      val context =
        if (agentic)
          CalendarPlannerContext.serializeAgentic(
            json,
            captured,
            request,
            candidates,
            eligible.size,
            selection,
            compact,
            adaptive,
          )
        else
          CalendarPlannerContext.serialize(
            json,
            captured,
            request,
            candidates,
            eligible.size,
            selection,
            compact,
          )
      val workoutHistory =
        if (agentic) json.readTree(context)["workoutHistory"] else json.nullNode()
      val instruction =
        CalendarPlannerContext.instruction +
          (if (agentic) "\n" + PlannerWorkoutHistory.instruction else "") +
          (if (agentic)
            "\nPlanner configuration for this attempt: ${runtime.instructions}\n" +
              "Before finalizing, select exactly one editable collection pattern with " +
              "get_strength_skeleton. Its slots are guidance, not a rigid exercise frame. " +
              "All configured patterns are equally available; choose from user goal, coverage, history and actual workout order. " +
              "You may adapt exercises, sets, reps and rest while obeying hard availability, NEVER, " +
              "duration and valid output structure. Multi-set strength work uses descending repetitions; " +
              "backend projects each set weight from trusted history."
          else "") +
          if (isStrength)
            "\nSTRENGTH: use only eligible candidates. Do not emit weight fields. " +
              "Saved history is descriptive, never a prescription; do not infer recovery, injury or readiness."
          else ""
      var validatedToolOutput: JsonNode? = null
      var selectedPattern: PlannerPattern? = null
      var output =
        if (agentic && provider is PlannerToolCallingProvider) {
          CalendarPlannerAgent(
              turn = { transcript ->
                provider.generatePlannerTurn(
                  AiProviderInput(
                    false,
                    instruction,
                    context,
                    schema,
                    schemaName = "calendar_draft_v2",
                    timeoutMillis = attempt.deadlineAt.toEpochMilli() - clock.millis(),
                    plannerTranscript = transcript,
                    model = runtime.model,
                  )
                )
              },
              tool = { call ->
                // Read-only fixed tools. A call result never contains provider data or mutable
                // state.
                when (call.name) {
                  "get_strength_skeleton" -> {
                    val pattern = requireNotNull(adaptive).pattern(requireNotNull(call.patternId))
                    selectedPattern = pattern
                    json.writeValueAsBytes(
                      mapOf(
                        "collectionId" to adaptive.collectionFor(pattern.id).id,
                        "patternId" to pattern.id,
                        "slots" to pattern.slots,
                        "workingSetProgression" to
                          "For multi-set strength work, repetitions descend and backend projects each set weight from trusted history.",
                      )
                    )
                  }
                  "get_candidate_details_and_history" ->
                    PlannerWorkoutHistory.candidateDetails(
                      json,
                      workoutHistory,
                      candidates,
                      call.candidateIds,
                    )
                  "validate_and_finalize_plan" -> {
                    if (selectedPattern == null) throw aiError("ai_invalid_response")
                    val candidatePlan = requireNotNull(call.plan)
                    val wrapped =
                      if (candidatePlan.has("result")) candidatePlan
                      else json.valueToTree(mapOf("result" to candidatePlan))
                    try {
                      val normalized = validator.validatePlanner(wrapped)
                      val validated =
                        validateAndProject(
                          normalized,
                          request,
                          candidates,
                          projectionFacts,
                          capturedAtMillis = captured.capturedAtMillis,
                          weightStepKg = runtime.weightStepKg,
                          minimumDurationRequired = true,
                          pattern = selectedPattern,
                        )
                      validatedToolOutput = normalized
                      json.writeValueAsBytes(
                        mapOf(
                          "valid" to true,
                          "details" to
                            mapOf(
                              "durationSec" to PlannerDuration.seconds(validated.exercises),
                              "focusExerciseId" to validated.exercises.first().exerciseId,
                            ),
                        )
                      )
                    } catch (error: ApiException) {
                      json.writeValueAsBytes(
                        mapOf(
                          "valid" to false,
                          "code" to error.code,
                          "details" to mapOf("accepted" to false),
                        )
                      )
                    }
                  }
                  else -> throw aiError("ai_invalid_response")
                }
              },
              maxRounds = runtime.maxRounds,
              maxCalls = runtime.maxToolCalls,
              diagnostics = diagnostics,
            )
            .run(
              candidates.mapTo(mutableSetOf()) { it["exerciseId"] as String },
              requireNotNull(adaptive).patterns.keys,
            ) {
              attempt.deadlineAt.toEpochMilli() - clock.millis()
            }
            .also { if (selectedPattern == null) throw aiError("ai_invalid_response") }
        } else if (agentic) throw aiError("ai_unavailable")
        else
          provider.generate(
            AiProviderInput(
              false,
              instruction,
              context,
              schema,
              schemaName = "calendar_draft",
              timeoutMillis = attempt.deadlineAt.toEpochMilli() - clock.millis(),
              model = runtime.model,
            )
          )
      if (Thread.currentThread().isInterrupted || !Instant.now(clock).isBefore(attempt.deadlineAt))
        throw aiError("ai_timeout")
      output = validator.validatePlanner(output)
      if (agentic && output != validatedToolOutput) throw aiError("ai_invalid_response")
      var draft =
        validateAndProject(
          output,
          request,
          candidates,
          projectionFacts,
          capturedAtMillis = captured.capturedAtMillis,
          weightStepKg = runtime.weightStepKg,
          minimumDurationRequired = agentic,
          pattern = selectedPattern,
        )
      // At most one correction, only for a broad unconstrained pool and enough remaining lease.
      if (
        !agentic &&
          PlannerDuration.seconds(draft.exercises) <
            PlannerDuration.minimumSeconds(request.availableDurationMinutes) &&
          candidates.size >= 6 &&
          request.currentState == null &&
          request.preferences == null &&
          captured.profile?.manualConstraints.isNullOrBlank() &&
          Instant.now(clock).plusSeconds(20).isBefore(attempt.deadlineAt)
      ) {
        contexts.verifyCalendarAdmission(
          identity,
          request.expectedRevision,
          request.expectedCatalogRevision,
        )
        val correction =
          context +
            "\nCORRECTION: Previous draft estimated " +
            PlannerDuration.seconds(draft.exercises) +
            " seconds. Target " +
            request.availableDurationMinutes * 60 +
            " seconds. Reconsider composition once, without padding sets, repetitions or rest just to fill time. " +
            "If meaningful volume cannot meet the target, return only the shorter plan. Previous output: " +
            output.toString()
        output =
          provider.generate(
            AiProviderInput(
              false,
              instruction,
              correction,
              schema,
              schemaName = "calendar_draft",
              timeoutMillis = minOf(20_000, attempt.deadlineAt.toEpochMilli() - clock.millis()),
              model = runtime.model,
            )
          )
        draft =
          validateAndProject(
            validator.validatePlanner(output),
            request,
            candidates,
            projectionFacts,
            capturedAtMillis = captured.capturedAtMillis,
            weightStepKg = runtime.weightStepKg,
          )
      }
      if (Thread.currentThread().isInterrupted || !Instant.now(clock).isBefore(attempt.deadlineAt))
        throw aiError("ai_timeout")
      val explanation =
        PlannerExplanationFactory.create(draft, request, captured, candidates, eligible.size)
      val final =
        tx.execute {
          hooks.beforeFinalLock()
          val catalogHead = catalog.readLock()
          val ownerHead = heads.writeLockOrNull(identity.userId) ?: unauthorized()
          sessionGuard.lock(identity)
          if (
            catalogHead.revision != request.expectedCatalogRevision ||
              ownerHead.revision != request.expectedRevision
          )
            throw aiError("ai_context_stale")
          val current =
            attempts.writeLock(identity.userId, UUID.fromString(request.requestId))
              ?: throw aiError("ai_interrupted")
          if (current.state == CalendarAiAttemptState.CANCELLED) throw aiError("ai_timeout")
          if (
            current.state != CalendarAiAttemptState.PROCESSING ||
              !Instant.now(clock).isBefore(current.deadlineAt)
          ) {
            if (current.state == CalendarAiAttemptState.PROCESSING) {
              terminal(current, CalendarAiAttemptState.CANCELLED, 504, "ai_timeout")
              return@execute CalendarFinal(error = aiError("ai_timeout"))
            }
            return@execute CalendarFinal(error = terminalError(current))
          }
          publicationGuard()
          current.state = CalendarAiAttemptState.COMMITTING
          hooks.beforeProposalInsert()
          val proposal =
            creator.createCalendar(
              InternalCalendarAiProposalRequest(
                identity,
                request.expectedRevision,
                request.expectedCatalogRevision,
                draft,
              )
            )
          explanations.save(proposal.proposalId, proposal.currentVersion, explanation)
          val response =
            CalendarDraftResponse(
              request.requestId,
              CalendarDraftContext(
                captured.revision.revision,
                captured.revision.catalogRevision,
                attempt.admittedAt.toEpochMilli(),
              ),
              proposal,
            )
          current.receipt = json.writeValueAsString(response)
          if (agentic) {
            val skeleton =
              StrengthPlannerSkeleton.create(
                draft.exercises.map { it.exerciseId },
                candidates.map { it["exerciseId"] as String },
                request.availableDurationMinutes,
              )
            projectionCaptured?.invoke(candidates.map { it["exerciseId"] as String }, skeleton)
            val ids = candidates.map { it["exerciseId"] as String }
            current.v2Receipt =
              json.writeValueAsString(
                CalendarDraftV2Response(
                  request.requestId,
                  response.context,
                  response.proposal,
                  AgenticProjection(
                    ids,
                    AgenticSkeleton(
                      skeleton.focusExerciseId,
                      skeleton.slots.map {
                        AgenticSkeletonSlot(
                          it.slotId,
                          it.allowedExerciseIds,
                          it.minDurationSec,
                          it.maxDurationSec,
                        )
                      },
                      skeleton.minDurationSec,
                      skeleton.maxDurationSec,
                    ),
                  ),
                )
              )
          }
          current.state = CalendarAiAttemptState.SUCCEEDED
          current.terminalStatus = null
          current.terminalCode = null
          publish(response)
          CalendarFinal(response = response)
        }
      final.error?.let { throw it }
      requireNotNull(final.response)
    } catch (e: ApiException) {
      terminalFailure(identity, request.requestId, e)
    } catch (error: Exception) {
      diagnostics.recordLocalFailure(error)
      terminalFailure(identity, request.requestId, aiError("ai_invalid_response"))
    }
  }

  fun cancel(identity: Identity, raw: ByteArray) {
    val request = runCatching { parse(raw) }.getOrNull() ?: return
    tx.executeWithoutResult {
      val row =
        attempts.writeLock(identity.userId, UUID.fromString(request.requestId))
          ?: return@executeWithoutResult
      if (row.state == CalendarAiAttemptState.PROCESSING)
        terminal(row, CalendarAiAttemptState.CANCELLED, 504, "ai_timeout")
    }
  }

  private data class Reserved(
    val admittedAt: Instant,
    val deadlineAt: Instant,
    val replay: CalendarDraftResponse?,
    val error: ApiException? = null,
  )

  private data class CalendarFinal(
    val response: CalendarDraftResponse? = null,
    val error: ApiException? = null,
  )

  private fun reserve(
    identity: Identity,
    request: CalendarDraftRequest,
    digest: String,
    runtime: PlannerConfiguration,
  ): Reserved {
    val reserved =
      tx.execute {
        val now = Instant.now(clock)
        val catalogHead = catalog.readLock()
        val ownerHead = heads.writeLockOrNull(identity.userId) ?: unauthorized()
        sessionGuard.lock(identity)
        val id = UUID.fromString(request.requestId)
        val previous = attempts.writeLock(identity.userId, id)
        if (previous != null) {
          if (previous.rawRequestSha256 != digest)
            return@execute Reserved(now, now, null, aiError("ai_request_conflict"))
          if (
            previous.state in
              setOf(CalendarAiAttemptState.PROCESSING, CalendarAiAttemptState.COMMITTING)
          ) {
            if (!previous.leaseUntil.isAfter(now)) {
              terminal(previous, CalendarAiAttemptState.INTERRUPTED, 409, "ai_interrupted")
              return@execute Reserved(
                previous.admittedAt,
                previous.deadlineAt,
                null,
                aiError("ai_interrupted"),
              )
            }
            return@execute Reserved(
              previous.admittedAt,
              previous.deadlineAt,
              null,
              aiError("ai_in_progress"),
            )
          }
          if (previous.state == CalendarAiAttemptState.SUCCEEDED)
            return@execute Reserved(
              previous.admittedAt,
              previous.deadlineAt,
              json.readValue(previous.receipt!!, CalendarDraftResponse::class.java),
            )
          return@execute Reserved(
            previous.admittedAt,
            previous.deadlineAt,
            null,
            terminalError(previous),
          )
        }
        if (
          catalogHead.revision != request.expectedCatalogRevision ||
            ownerHead.revision != request.expectedRevision
        )
          throw aiError("ai_context_stale")
        val deadline = now.plusSeconds(runtime.timeoutSeconds.toLong())
        attempts.saveAndFlush(
          CalendarAiAttemptEntity(
            identity.userId,
            id,
            digest,
            CalendarAiAttemptState.PROCESSING,
            now,
            deadline,
            deadline.plusSeconds(15),
          )
        )
        jdbc.update(
          "UPDATE calendar_ai_attempts SET plan_config_json=? WHERE owner_id=? AND request_id=?",
          json.writeValueAsString(runtime),
          identity.userId,
          id,
        )
        Reserved(now, deadline, null)
      }
    reserved.error?.let { throw it }
    return reserved
  }

  private fun frozenPlannerConfiguration(): PlannerConfiguration {
    val snapshot = plannerConfiguration.snapshot()
    return snapshot.copy(model = snapshot.model.ifBlank { aiSettings.get().textModel })
  }

  private fun redactedDraft(draft: ApprovalDraft): JsonNode {
    val node: JsonNode = json.valueToTree(draft)
    fun redact(value: JsonNode) {
      if (value.isObject) {
        val objectNode = value as tools.jackson.databind.node.ObjectNode
        objectNode
          .properties()
          .map { it.key }
          .filter { it.contains("weight", ignoreCase = true) }
          .forEach(objectNode::remove)
        objectNode.properties().forEach { redact(it.value) }
      } else if (value.isArray) value.forEach(::redact)
    }
    redact(node)
    return node
  }

  private fun terminalFailure(owner: Identity, requestId: String, error: ApiException): Nothing {
    tx.executeWithoutResult {
      attempts.writeLock(owner.userId, UUID.fromString(requestId))?.let {
        if (it.state == CalendarAiAttemptState.PROCESSING)
          terminal(it, CalendarAiAttemptState.FAILED, error.status, error.code)
      }
    }
    throw error
  }

  private fun terminal(
    row: CalendarAiAttemptEntity,
    state: CalendarAiAttemptState,
    status: Int,
    code: String,
  ) {
    row.state = state
    row.terminalStatus = status
    row.terminalCode = code
  }

  private fun terminalError(row: CalendarAiAttemptEntity): ApiException =
    aiError(row.terminalCode ?: "ai_interrupted")

  internal fun parse(raw: ByteArray, validateFuture: Boolean = true): CalendarDraftRequest {
    if (
      raw.isEmpty() ||
        raw.size > 524_288 ||
        raw
          .take(3)
          .toByteArray()
          .contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
    )
      bad("Некорректный запрос")
    try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
    val root =
      try {
        json
          .tokenStreamFactory()
          .rebuild()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build()
          .createParser(raw)
          .use { parser ->
            json.readTree(parser).also {
              if (parser.nextToken() != null) bad("Некорректный запрос")
            }
          }
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    val fields =
      setOf(
        "requestId",
        "expectedRevision",
        "expectedCatalogRevision",
        "startsAtMillis",
        "timeZoneId",
        "gymIds",
        "excludedExerciseIds",
        "excludedEquipmentIds",
        "priorityMuscles",
        "includeNotes",
        "availableDurationMinutes",
        "currentState",
        "preferences",
      )
    if (!root.isObject || root.properties().map { it.key }.toSet() != fields)
      bad("Некорректный запрос")
    val request =
      try {
        json.treeToValue(root, CalendarDraftRequest::class.java)
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    fun ids(rows: List<String>, maximum: Int) =
      rows.size <= maximum && rows.distinct().size == rows.size && rows.all(::canonicalUuid)
    val muscles =
      setOf(
        "UPPER_CHEST",
        "LOWER_CHEST",
        "FRONT_DELTS",
        "SIDE_DELTS",
        "REAR_DELTS",
        "ROTATOR_CUFF",
        "SERRATUS_ANTERIOR",
        "BICEPS",
        "TRICEPS",
        "FOREARMS",
        "ABS",
        "OBLIQUES",
        "HIP_FLEXORS",
        "ADDUCTORS",
        "QUADS",
        "TIBIALIS_ANTERIOR",
        "CALVES",
        "HAMSTRINGS",
        "GLUTES",
        "HIP_ABDUCTORS",
        "LOWER_BACK",
        "LATS",
        "UPPER_BACK",
        "TRAPS",
        "NECK",
      )
    if (
      !canonicalUuid(request.requestId) ||
        request.expectedRevision < 0 ||
        request.expectedCatalogRevision < 0 ||
        request.startsAtMillis < 0 ||
        !ids(request.gymIds, 1000) ||
        !ids(request.excludedExerciseIds, 1000) ||
        request.priorityMuscles.size > 25 ||
        request.priorityMuscles.distinct().size != request.priorityMuscles.size ||
        request.priorityMuscles.any { it !in muscles } ||
        request.excludedEquipmentIds.size > 200 ||
        request.excludedEquipmentIds.distinct().size != request.excludedEquipmentIds.size ||
        request.excludedEquipmentIds.any { it.isBlank() || it.length > 255 } ||
        request.availableDurationMinutes !in 10..240 ||
        request.timeZoneId.length !in 1..255 ||
        runCatching { ZoneId.of(request.timeZoneId) }.isFailure
    )
      bad("Некорректный запрос")
    if (
      request.currentState?.let { it.length > 2000 || it.trim().isEmpty() } == true ||
        request.preferences?.let { it.length > 2000 || it.trim().isEmpty() } == true
    )
      bad("Некорректный запрос")
    if (validateFuture && !Instant.ofEpochMilli(request.startsAtMillis).isAfter(Instant.now(clock)))
      bad("Некорректный запрос")
    return request
  }

  private fun validateAndProject(
    raw: JsonNode,
    request: CalendarDraftRequest,
    candidates: List<Map<String, Any>>,
    facts: List<CalendarFact>,
    capturedAtMillis: Long = clock.millis(),
    weightStepKg: Double = 2.5,
    minimumDurationRequired: Boolean = false,
    pattern: PlannerPattern? = null,
  ): ApprovalDraft {
    val result = raw["result"] ?: throw aiError("ai_invalid_response")
    val exercises = result["exercises"]?.toList().orEmpty()
    if (
      !result["name"].isTextual ||
        result["name"].asString().isBlank() ||
        result["name"].asString().length > 200 ||
        exercises.size !in 1..12 ||
        exercises.sumOf { it["plannedSets"]?.size() ?: 0 } > 40
    )
      throw aiError("ai_invalid_response")
    val candidateTypes = candidates.associate { it["exerciseId"] as String to it["type"] as String }
    val ids = exercises.map { it["exerciseId"]?.asString() }
    if (ids.any { it == null || it !in candidateTypes } || ids.distinct().size != ids.size)
      throw aiError("ai_invalid_response")
    val patternBounds =
      pattern
        ?.slots
        ?.filter { it.exerciseType == "STRENGTH" }
        ?.map { it.repsMin..it.repsMax }
        .orEmpty()
    val planned =
      exercises.map { e ->
        val type = candidateTypes.getValue(e["exerciseId"].asString())
        val rest = e["restSeconds"]?.takeUnless(JsonNode::isNull)?.asInt()
        if (rest != null && rest !in 0..900) throw aiError("ai_invalid_response")
        val sets = e["plannedSets"]?.toList().orEmpty()
        if (sets.size !in 1..10) throw aiError("ai_invalid_response")
        val supplied =
          sets.map { s ->
            val reps = s["reps"]?.takeUnless(JsonNode::isNull)?.asInt()
            val duration = s["durationSec"]?.takeUnless(JsonNode::isNull)?.asInt()
            if (
              (type == "STRENGTH" && (reps !in 1..100 || duration != null)) ||
                (type != "STRENGTH" && (reps != null || duration !in 1..7200))
            )
              throw aiError("ai_invalid_response")
            reps to duration
          }
        val progressedReps =
          if (type == "STRENGTH")
            StrengthSetProgression.descend(
              supplied.map { requireNotNull(it.first) },
              patternBounds
                .filter { bounds -> supplied.all { (reps, _) -> requireNotNull(reps) in bounds } }
                .minByOrNull { bounds -> bounds.last - bounds.first },
            )
          else emptyList()
        PlannedExercise(
          e["exerciseId"].asString(),
          rest ?: 90,
          supplied.mapIndexed { setIndex, (reps, duration) ->
            val plannedReps = progressedReps.getOrNull(setIndex) ?: reps
            val weight =
              if (type == "STRENGTH")
                PlannerWeightEngine.calculate(
                  facts,
                  e["exerciseId"].asString(),
                  requireNotNull(plannedReps),
                  capturedAtMillis,
                  weightStepKg,
                )
              else null
            PlannedSet(weight, plannedReps, duration, null, null)
          },
        )
      }
    val durationSeconds = PlannerDuration.seconds(planned)
    if (
      durationSeconds > request.availableDurationMinutes * 60L ||
        (minimumDurationRequired &&
          durationSeconds < PlannerDuration.minimumSeconds(request.availableDurationMinutes))
    )
      throw aiError("ai_invalid_response")
    return ApprovalDraft(
      result["name"].asString(),
      request.gymIds,
      planned,
      request.startsAtMillis,
      request.timeZoneId,
    )
  }

  private fun canonicalUuid(value: String) =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

  private fun ByteArray.sha256() =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
