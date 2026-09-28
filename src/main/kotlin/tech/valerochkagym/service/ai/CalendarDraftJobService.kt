package tech.valerochkagym.service.ai

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.CalendarDraftRequest
import tech.valerochkagym.controller.model.CalendarDraftResponse
import tech.valerochkagym.controller.model.PlannerV2CreateRequest
import tech.valerochkagym.controller.model.PlannerV2RefinementRequest
import tech.valerochkagym.service.model.Identity
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

data class CalendarDraftJobResponse(
  val requestId: String,
  val state: String,
  val errorCode: String? = null,
  val result: CalendarDraftResponse? = null,
)

/** Durable intent only: never stores a provider prompt, context capture or raw provider output. */
@Service
class CalendarDraftJobService(
  private val jdbc: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val sessionGuard: tech.valerochkagym.service.auth.IdentitySessionGuard,
  private val contexts: AiContextReader,
  private val calendar: CalendarAiService,
  private val deterministic: DeterministicPlannerRuntime,
  private val json: ObjectMapper,
  private val clock: Clock,
  @Value("\${gym.calendar-jobs.enabled:true}") private val enabled: Boolean,
  private val hooks: CalendarAiExecutionHooks,
) {
  private val active = AtomicBoolean(false)

  private data class Job(
    val owner: UUID,
    val id: UUID,
    val session: UUID,
    val digest: String,
    val intent: String,
    val state: String,
    val current: Boolean,
    val executions: Int,
    val token: UUID?,
    val error: String?,
    val result: String?,
    val protocol: Int,
    val snapshot: String?,
    val leaseUntil: java.time.Instant?,
  )

  private fun row(rs: ResultSet) =
    Job(
      rs.getObject("owner_id", UUID::class.java),
      rs.getObject("request_id", UUID::class.java),
      rs.getObject("session_id", UUID::class.java),
      rs.getString("request_digest").trim(),
      rs.getString("intent"),
      rs.getString("state"),
      rs.getBoolean("current_job"),
      rs.getInt("executions"),
      rs.getObject("lease_token", UUID::class.java),
      rs.getString("error_code"),
      rs.getString("result"),
      rs.getInt("protocol"),
      rs.getString("execution_snapshot"),
      rs.getTimestamp("lease_until")?.toInstant(),
    )

  private fun read(owner: UUID, id: UUID) =
    jdbc
      .query(
        "SELECT * FROM calendar_draft_jobs WHERE owner_id=? AND request_id=?",
        { rs, _ -> row(rs) },
        owner,
        id,
      )
      .firstOrNull()

  private fun response(job: Job) =
    CalendarDraftJobResponse(
      job.id.toString(),
      job.state,
      job.error,
      job.result
        ?.takeIf { job.state == "READY" }
        ?.let { json.readValue(it, CalendarDraftResponse::class.java) },
    )

  fun submit(identity: Identity, raw: ByteArray): CalendarDraftJobResponse {
    if (raw.isEmpty() || raw.size > 524288) bad("Некорректный запрос")
    try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
    if (
      raw
        .take(3)
        .toByteArray()
        .contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
    )
      bad("Некорректный запрос")
    val root =
      try {
        json
          .tokenStreamFactory()
          .rebuild()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build()
          .createParser(raw)
          .use { parser ->
            (json.readTree(parser) as? ObjectNode ?: bad("Некорректный запрос")).also {
              if (parser.nextToken() != null) bad("Некорректный запрос")
            }
          }
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    val replaced = mutableListOf<String>()
    root
      .remove("replacesRequestId")
      ?.takeUnless { it.isNull }
      ?.let {
        if (!it.isTextual) bad("Некорректный запрос")
        replaced += it.asString()
      }
    root.remove("replacesRequestIds")?.let { list ->
      if (!list.isArray || list.size() > 1000 || list.any { !it.isTextual })
        bad("Некорректный запрос")
      replaced += list.toList().map { it.asString() }
    }
    val request = calendar.parse(json.writeValueAsBytes(root), validateFuture = false)
    if (
      replaced.size > 1001 ||
        replaced.any {
          it == request.requestId ||
            runCatching { UUID.fromString(it).toString() != it }.getOrDefault(true)
        }
    )
      bad("Некорректный запрос")
    val digest =
      MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    return tx.execute {
      sessionGuard.lock(identity)
      val id = UUID.fromString(request.requestId)
      read(identity.userId, id)?.let {
        if (it.protocol != 1 || it.digest != digest) throw aiError("ai_request_conflict")
        return@execute checked(identity, it)
      }
      // Every ancestor is tombstoned even when this delivery itself was superseded.
      replaced.distinct().forEach { old ->
        jdbc.update(
          "INSERT INTO calendar_draft_job_supersessions(owner_id,request_id) VALUES (?,?) ON CONFLICT DO NOTHING",
          identity.userId,
          UUID.fromString(old),
        )
        jdbc.update(
          "UPDATE calendar_draft_jobs SET current_job=false,state='SUPERSEDED' WHERE owner_id=? AND request_id=?",
          identity.userId,
          UUID.fromString(old),
        )
      }
      val superseded =
        jdbc.queryForObject(
          "SELECT count(*) FROM calendar_draft_job_supersessions WHERE owner_id=? AND request_id=?",
          Long::class.java,
          identity.userId,
          id,
        )!! > 0
      if (!superseded) {
        contexts.verifyCalendarAdmission(
          identity,
          request.expectedRevision,
          request.expectedCatalogRevision,
        )
      }
      val state =
        when {
          superseded -> "SUPERSEDED"
          request.startsAtMillis <= clock.millis() -> "EXPIRED"
          else -> "FAILED"
        }
      jdbc.update(
        "INSERT INTO calendar_draft_jobs(owner_id,request_id,session_id,request_digest,intent,state,current_job,created_at,protocol,raw_request) VALUES (?,?,?,?,?::jsonb,?,?,?,1,?)",
        identity.userId,
        id,
        identity.sessionId,
        digest,
        json.writeValueAsString(request),
        state,
        !superseded,
        Timestamp.from(clock.instant()),
        raw,
      )
      jdbc.update(
        "UPDATE calendar_draft_jobs SET error_code=CASE WHEN state='FAILED' THEN 'ai_invalid_request' ELSE error_code END WHERE owner_id=? AND request_id=?",
        identity.userId,
        id,
      )
      response(read(identity.userId, id)!!)
    }!!
  }

  /** Exact bytes bind the key; immutable context capture and admission share one transaction. */
  fun submitV2(identity: Identity, raw: ByteArray): CalendarDraftJobResponse {
    val root = strictRoot(raw)
    exact(
      root,
      setOf(
        "requestId",
        "variant",
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
      ),
      setOf("variant"),
    )
    canonical(root["requestId"])
    listOf(
        "variant",
        "expectedRevision",
        "expectedCatalogRevision",
        "baseProposalVersion",
        "startsAtMillis",
        "availableDurationMinutes",
      )
      .forEach { key ->
        root[key]?.let {
          if (!it.isIntegralNumber || !it.canConvertToLong()) bad("Некорректное число")
        }
      }
    listOf("gymIds", "excludedExerciseIds", "excludedEquipmentIds", "priorityMuscles", "changes")
      .forEach { key -> root[key]?.let { if (!it.isArray) bad("Некорректный список") } }
    root["includeNotes"]?.let { if (!it.isBoolean) bad("Некорректное поле") }

    listOf("gymIds", "excludedExerciseIds").forEach { key -> root[key]?.forEach(::canonical) }
    val request =
      try {
        json.treeToValue(root, PlannerV2CreateRequest::class.java)
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    if (request.variant < 0 || request.includeNotes) bad("Некорректный запрос")
    calendar.parse(json.writeValueAsBytes(deterministic.legacy(request)), validateFuture = false)
    return admitV2(identity, request.requestId, raw, "CREATE", null) {
      deterministic.capture(identity, request)
    }
  }

  fun submitV2Refinement(
    identity: Identity,
    proposalId: UUID,
    raw: ByteArray,
  ): CalendarDraftJobResponse {
    val root = strictRoot(raw)
    exact(
      root,
      setOf(
        "requestId",
        "variant",
        "expectedRevision",
        "expectedCatalogRevision",
        "baseProposalVersion",
        "approvalDraft",
        "changes",
      ),
      setOf("variant"),
    )
    canonical(root["requestId"])
    listOf(
        "variant",
        "expectedRevision",
        "expectedCatalogRevision",
        "baseProposalVersion",
        "startsAtMillis",
        "availableDurationMinutes",
      )
      .forEach { key ->
        root[key]?.let {
          if (!it.isIntegralNumber || !it.canConvertToLong()) bad("Некорректное число")
        }
      }
    listOf("gymIds", "excludedExerciseIds", "excludedEquipmentIds", "priorityMuscles", "changes")
      .forEach { key -> root[key]?.let { if (!it.isArray) bad("Некорректный список") } }
    root["includeNotes"]?.let { if (!it.isBoolean) bad("Некорректное поле") }

    val changes = root["changes"] ?: bad("Некорректный запрос")
    if (!changes.isArray || changes.size() !in 1..100) bad("Некорректный запрос")
    changes.forEach {
      when (it["kind"]?.asString()) {
        "REPLACE" -> exact(it, setOf("kind", "slotId", "selectionId", "exerciseId"))
        "EXCLUDE" -> exact(it, setOf("kind", "exerciseId"))
        else -> bad("Некорректный запрос")
      }
      canonical(it["exerciseId"])
    }
    val draft = root["approvalDraft"] ?: bad("Некорректный запрос")
    exact(draft, setOf("name", "gymIds", "exercises", "startsAtMillis", "timeZoneId"))
    draft["gymIds"]?.forEach(::canonical)
    draft["exercises"]?.forEach { exercise ->
      exact(exercise, setOf("exerciseId", "restSeconds", "plannedSets"))
      canonical(exercise["exerciseId"])
      exercise["plannedSets"]?.forEach {
        exact(it, setOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct"))
      }
    }
    val request =
      try {
        json.treeToValue(root, PlannerV2RefinementRequest::class.java)
      } catch (_: Exception) {
        bad("Некорректный запрос")
      }
    if (
      request.variant < 0 ||
        request.baseProposalVersion < 1 ||
        request.expectedRevision < 0 ||
        request.expectedCatalogRevision < 0 ||
        request.changes.any {
          it.kind == "REPLACE" && (it.slotId.isNullOrBlank() || it.selectionId.isNullOrBlank())
        }
    )
      bad("Некорректный запрос")
    return admitV2(identity, request.requestId, raw, "REFINE", proposalId) {
      deterministic.captureRefinement(identity, proposalId, request)
    }
  }

  private fun admitV2(
    identity: Identity,
    id: UUID,
    raw: ByteArray,
    kind: String,
    base: UUID?,
    capture: () -> PlannerExecutionSnapshot,
  ): CalendarDraftJobResponse =
    tx.execute {
      jdbc.queryForObject("SELECT revision FROM catalog_state FOR SHARE", Long::class.java)
      jdbc.query(
        "SELECT revision FROM sync_heads WHERE user_id=? FOR UPDATE",
        { rs, _ -> rs.getLong(1) },
        identity.userId,
      )
      sessionGuard.lock(identity)
      val digest = DeterministicPlannerRuntime.fingerprint(raw)
      read(identity.userId, id)?.let {
        val binding =
          jdbc.queryForMap(
            "SELECT request_kind,base_proposal_id FROM calendar_draft_jobs WHERE owner_id=? AND request_id=?",
            identity.userId,
            id,
          )
        if (
          it.protocol != 2 ||
            it.digest != digest ||
            binding["request_kind"] != kind ||
            binding["base_proposal_id"] != base
        )
          throw aiError("ai_request_conflict")
        return@execute checked(identity, it)
      }
      val snapshot = capture()
      hooks.afterCapture()
      val superseded =
        jdbc.queryForObject(
          "SELECT count(*) FROM calendar_draft_job_supersessions WHERE owner_id=? AND request_id=?",
          Long::class.java,
          identity.userId,
          id,
        )!! > 0
      val state =
        if (superseded) "SUPERSEDED"
        else if (snapshot.request.startsAtMillis <= clock.millis()) "EXPIRED" else "QUEUED"
      jdbc.update(
        "INSERT INTO calendar_draft_jobs(owner_id,request_id,session_id,request_digest,intent,state,current_job,created_at,protocol,variant,execution_snapshot,raw_request,request_kind,base_proposal_id) VALUES (?,?,?,?,?::jsonb,?,?,?,2,?,?::jsonb,?,?,?)",
        identity.userId,
        id,
        identity.sessionId,
        digest,
        json.writeValueAsString(deterministic.legacy(snapshot.request)),
        state,
        !superseded,
        Timestamp.from(clock.instant()),
        snapshot.request.variant,
        json.writeValueAsString(snapshot),
        raw,
        kind,
        base,
      )
      response(read(identity.userId, id)!!)
    }!!

  private fun strictRoot(raw: ByteArray): tools.jackson.databind.JsonNode {
    if (
      raw.isEmpty() ||
        raw.size > 524288 ||
        raw
          .take(3)
          .toByteArray()
          .contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
    )
      bad("Некорректный запрос")
    return try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
      json
        .tokenStreamFactory()
        .rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()
        .createParser(raw)
        .use { parser ->
          json.readTree(parser).also {
            if (!it.isObject || parser.nextToken() != null) bad("Некорректный запрос")
          }
        }
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
  }

  private fun exact(
    node: tools.jackson.databind.JsonNode,
    fields: Set<String>,
    optional: Set<String> = emptySet(),
  ) {
    val actual = node.properties().map { it.key }.toSet()
    if (!node.isObject || !fields.containsAll(actual) || !actual.containsAll(fields - optional))
      bad("Некорректный запрос")
  }

  private fun canonical(node: tools.jackson.databind.JsonNode?) {
    val value = node?.takeIf { it.isString }?.asString() ?: bad("Некорректный UUID")
    if (runCatching { UUID.fromString(value).toString() != value }.getOrDefault(true))
      bad("Некорректный UUID")
  }

  fun statusV2(identity: Identity, id: UUID) = status(identity, id, 2)

  fun cancel(identity: Identity, id: UUID, protocol: Int = 1) =
    tx.executeWithoutResult {
      sessionGuard.lock(identity)
      read(identity.userId, id)?.let {
        if (it.protocol != protocol) throw ApiException(404, "not_found", "Заявка не найдена")
      }
      jdbc.update(
        "INSERT INTO calendar_draft_job_supersessions(owner_id,request_id) VALUES (?,?) ON CONFLICT DO NOTHING",
        identity.userId,
        id,
      )
      jdbc.update(
        "UPDATE calendar_draft_jobs SET current_job=false,state='SUPERSEDED' WHERE owner_id=? AND request_id=?",
        identity.userId,
        id,
      )
    }

  fun status(identity: Identity, id: UUID, protocol: Int = 1): CalendarDraftJobResponse =
    tx.execute {
      sessionGuard.lock(identity)
      checked(
        identity,
        read(identity.userId, id)?.takeIf { it.protocol == protocol }
          ?: throw ApiException(404, "not_found", "Заявка не найдена"),
      )
    }!!

  private fun checked(identity: Identity, job: Job): CalendarDraftJobResponse {
    if (
      !job.current ||
        job.state in setOf("FAILED", "STALE", "EXPIRED", "SUPERSEDED", "IMPOSSIBLE") ||
        (job.protocol == 1 && job.state == "READY")
    )
      return response(job)
    if (job.protocol == 1) {
      jdbc.update(
        "UPDATE calendar_draft_jobs SET state='FAILED',error_code='ai_invalid_request' WHERE owner_id=? AND request_id=? AND state IN ('QUEUED','RUNNING')",
        job.owner,
        job.id,
      )
      return response(read(job.owner, job.id)!!)
    }
    val request = json.readValue(job.intent, CalendarDraftRequest::class.java)
    val catalogRevision =
      jdbc.queryForObject("SELECT revision FROM catalog_state FOR SHARE", Long::class.java)
    val ownerRevision =
      jdbc
        .query(
          "SELECT revision FROM sync_heads WHERE user_id=? FOR SHARE",
          { rs, _ -> rs.getLong(1) },
          identity.userId,
        )
        .firstOrNull()
    val state =
      when {
        request.startsAtMillis <= clock.millis() -> "EXPIRED"
        catalogRevision != request.expectedCatalogRevision ||
          ownerRevision != request.expectedRevision -> "STALE"
        else -> null
      }
    if (state != null) {
      jdbc.update(
        "UPDATE calendar_draft_jobs SET state=?,error_code=? WHERE owner_id=? AND request_id=?",
        state,
        if (state == "STALE") "ai_context_stale" else null,
        job.owner,
        job.id,
      )
      return response(read(job.owner, job.id)!!)
    }
    return response(job)
  }

  /** One bounded execution per process; SQL leases fence other instances and survive restarts. */
  @Scheduled(fixedDelayString = "\${gym.calendar-jobs.poll-ms:5000}")
  fun tick() {
    if (!enabled || !active.compareAndSet(false, true)) return
    Thread.ofVirtual().start {
      try {
        runNext()
      } catch (_: Exception) {
        // Durable leases remain recoverable on transient database failure.
      } finally {
        active.set(false)
      }
    }
  }

  internal fun runNext() {
    val candidate =
      jdbc
        .query(
          "SELECT * FROM calendar_draft_jobs WHERE current_job AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=?)) ORDER BY created_at LIMIT 1",
          { rs, _ -> row(rs) },
          Timestamp.from(clock.instant()),
        )
        .firstOrNull() ?: return
    val claimed =
      tx.execute {
        val token = UUID.randomUUID()
        val changed =
          jdbc.update(
            "UPDATE calendar_draft_jobs SET state='RUNNING',executions=executions+1,lease_token=?,lease_until=? WHERE owner_id=? AND request_id=? AND current_job AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=?))",
            token,
            Timestamp.from(clock.instant().plusSeconds(90)),
            candidate.owner,
            candidate.id,
            Timestamp.from(clock.instant()),
          )
        if (changed == 0) null else read(candidate.owner, candidate.id)
      } ?: return
    try {
      if (claimed.protocol != 2) {
        finish(claimed, "FAILED", "ai_invalid_request")
        return
      }
      if (claimed.executions > 3) throw aiError("ai_interrupted")
      val snapshot =
        claimed.snapshot?.let { json.readValue(it, PlannerExecutionSnapshot::class.java) }
          ?: throw aiError("ai_invalid_request")
      if (snapshot.request.startsAtMillis <= clock.millis()) {
        finish(claimed, "EXPIRED", null)
        return
      }
      val identity = Identity(claimed.owner, claimed.session, "")
      contexts.verifyCalendarAdmission(
        identity,
        snapshot.request.expectedRevision,
        snapshot.request.expectedCatalogRevision,
      )
      when (val computed = deterministic.compute(snapshot)) {
        is DeterministicPlannerResult.Terminal -> finish(claimed, "IMPOSSIBLE", computed.code.name)
        is DeterministicPlannerResult.Ready -> {
          hooks.beforeFinalLock()
          tx.executeWithoutResult {
            val catalog =
              jdbc.queryForObject("SELECT revision FROM catalog_state FOR SHARE", Long::class.java)
            val owner =
              jdbc
                .query(
                  "SELECT revision FROM sync_heads WHERE user_id=? FOR UPDATE",
                  { rs, _ -> rs.getLong(1) },
                  claimed.owner,
                )
                .singleOrNull()
            sessionGuard.lock(identity)
            if (
              catalog != snapshot.request.expectedCatalogRevision ||
                owner != snapshot.request.expectedRevision
            )
              throw aiError("ai_context_stale")
            jdbc.query(
              "SELECT request_id FROM calendar_draft_jobs WHERE owner_id=? AND request_id=? FOR UPDATE",
              { rs, _ -> rs.getObject(1) },
              claimed.owner,
              claimed.id,
            )
            val current = read(claimed.owner, claimed.id) ?: unauthorized()
            if (
              !current.current ||
                current.state != "RUNNING" ||
                current.token != claimed.token ||
                current.leaseUntil?.isAfter(clock.instant()) != true ||
                snapshot.request.startsAtMillis <= clock.millis()
            )
              throw aiError("ai_context_stale")
            hooks.beforeProposalInsert()
            val result = deterministic.publish(identity, snapshot, computed.plan)
            val changed =
              jdbc.update(
                "UPDATE calendar_draft_jobs SET state='READY',result=?::jsonb,proposal_id=?,error_code=null WHERE owner_id=? AND request_id=? AND state='RUNNING' AND lease_token=? AND lease_until>? AND current_job",
                json.writeValueAsString(result),
                result.proposal.proposalId,
                claimed.owner,
                claimed.id,
                claimed.token,
                Timestamp.from(clock.instant()),
              )
            if (changed != 1) throw aiError("ai_context_stale")
          }
        }
      }
    } catch (error: Exception) {
      val code = (error as? ApiException)?.code
      val safe =
        when (code) {
          "ai_context_stale",
          "proposal_stale",
          "active_workout" -> "ai_context_stale"
          "ai_interrupted",
          "ai_invalid_request" -> code
          else -> "ai_invalid_response"
        }
      val impossible = code in setOf("NO_FEASIBLE_PLAN", "PLANNER_LIMIT_REACHED", "PLANNER_TIMEOUT")
      finish(
        claimed,
        if (impossible) "IMPOSSIBLE" else if (safe == "ai_context_stale") "STALE" else "FAILED",
        if (impossible) code else safe,
      )
    }
  }

  private fun finish(job: Job, state: String, code: String?) =
    tx.executeWithoutResult {
      jdbc.update(
        "UPDATE calendar_draft_jobs SET state=?,error_code=? WHERE owner_id=? AND request_id=? AND state='RUNNING' AND lease_token=? AND lease_until>? AND current_job",
        state,
        code,
        job.owner,
        job.id,
        job.token,
        Timestamp.from(clock.instant()),
      )
    }
}
