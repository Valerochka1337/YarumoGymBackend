package tech.valerochkagym.service.ai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
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
import tech.valerochkagym.repository.model.CalendarAiAttemptState
import tech.valerochkagym.service.model.Identity
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The calendar route's durable idempotency boundary. Provider data never enters the ledger. */
@Service
class CalendarAiService(
  private val attempts: CalendarAiAttemptRepository,
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
) {
  private val schema: JsonNode by lazy {
    javaClass.getResourceAsStream("/ai/calendar-planner-output-v3.json")!!.use(json::readTree)
  }

  fun create(
    identity: Identity,
    raw: ByteArray,
    publicationGuard: () -> Unit = {},
    publish: (CalendarDraftResponse) -> Unit = {},
  ): CalendarDraftResponse {
    val request = parse(raw)
    val digest = raw.sha256()
    val attempt = reserve(identity, request, digest)
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
          if (eligible.none { it["type"] == "STRENGTH" }) throw aiError("ai_context_stale")
          StrengthPlannerFacts.select(
            eligible.map { row ->
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
            eligible.associate { row ->
              (row["exerciseId"] as String) to
                (row["muscles"] as List<Map<String, Any>>).associate {
                  it["muscle"] as String to it["contribution"] as Int
                }
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
        CalendarPlannerContext.serialize(
          json,
          captured,
          request,
          candidates,
          eligible.size,
          selection,
          compact,
        )
      val instruction =
        CalendarPlannerContext.instruction +
          if (isStrength)
            "\nSTRENGTH: selection.focusExerciseId must occur in result.exercises. Use only the ranked candidates. " +
              "strengthFacts version strength-compact-v1 contains explicit saved observations, not weight prescriptions. " +
              "Never emit weight fields. LEGACY/UNKNOWN numeric values are unavailable. Movement units are exercise-based; " +
              "lastWorkoutExerciseIds lists observed selected exercises in the latest finished workout. 7/28-day windows overlap, so do not add them or add latest tuples to volume. Efforts are optional user ratings " +
              "ordered by latest finished workout first; null means cleared, not easy. Do not infer recovery, injury or readiness."
          else ""
      var output =
        provider.generate(
          AiProviderInput(
            false,
            instruction,
            context,
            schema,
            schemaName = "calendar_draft",
            timeoutMillis = attempt.deadlineAt.toEpochMilli() - clock.millis(),
          )
        )
      if (Thread.currentThread().isInterrupted || !Instant.now(clock).isBefore(attempt.deadlineAt))
        throw aiError("ai_timeout")
      output = validator.validatePlanner(output)
      var draft =
        validateAndProject(output, request, candidates, projectionFacts, selection?.focusExerciseId)
      // At most one correction, only for a broad unconstrained pool and enough remaining lease.
      if (
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
            )
          )
        draft =
          validateAndProject(
            validator.validatePlanner(output),
            request,
            candidates,
            projectionFacts,
            selection?.focusExerciseId,
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
    } catch (_: Exception) {
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

  private fun reserve(identity: Identity, request: CalendarDraftRequest, digest: String): Reserved {
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
        attempts.save(
          CalendarAiAttemptEntity(
            identity.userId,
            id,
            digest,
            CalendarAiAttemptState.PROCESSING,
            now,
            now.plusSeconds(45),
            now.plusSeconds(60),
          )
        )
        Reserved(now, now.plusSeconds(45), null)
      }
    reserved.error?.let { throw it }
    return reserved
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
    focusExerciseId: String? = null,
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
    if (focusExerciseId != null && focusExerciseId !in ids) throw aiError("ai_invalid_response")
    val planned =
      exercises.map { e ->
        val type = candidateTypes.getValue(e["exerciseId"].asString())
        val rest = e["restSeconds"]?.takeUnless(JsonNode::isNull)?.asInt()
        if (rest != null && rest !in 0..900) throw aiError("ai_invalid_response")
        val sets = e["plannedSets"]?.toList().orEmpty()
        if (sets.size !in 1..10) throw aiError("ai_invalid_response")
        PlannedExercise(
          e["exerciseId"].asString(),
          rest ?: 90,
          sets.map { s ->
            val reps = s["reps"]?.takeUnless(JsonNode::isNull)?.asInt()
            val duration = s["durationSec"]?.takeUnless(JsonNode::isNull)?.asInt()
            if (
              (type == "STRENGTH" && (reps !in 1..100 || duration != null)) ||
                (type != "STRENGTH" && (reps != null || duration !in 1..7200))
            )
              throw aiError("ai_invalid_response")
            val weight =
              if (type == "STRENGTH")
                facts
                  .asSequence()
                  .filter { it.exerciseId == e["exerciseId"].asString() }
                  .sortedWith(
                    compareByDescending<CalendarFact> { it.factTimeMillis }
                      .thenBy { it.workoutId }
                      .thenBy { it.sectionId }
                      .thenBy { it.setIndex }
                  )
                  .firstOrNull()
                  ?.let { if (it.actualWeightPresent) it.actualWeightKg else it.legacyWeightKg }
              else null
            PlannedSet(weight, reps, duration, null, null)
          },
        )
      }
    if (PlannerDuration.seconds(planned) > request.availableDurationMinutes * 60L)
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
