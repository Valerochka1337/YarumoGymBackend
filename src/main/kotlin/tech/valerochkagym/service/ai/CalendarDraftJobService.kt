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
  private val provider: AiProvider,
  private val json: ObjectMapper,
  private val clock: Clock,
  @Value("\${gym.calendar-jobs.enabled:true}") private val enabled: Boolean,
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
      job.result?.let { json.readValue(it, CalendarDraftResponse::class.java) },
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
        if (it.digest != digest) throw aiError("ai_request_conflict")
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
        jdbc.update(
          "UPDATE calendar_draft_jobs SET current_job=false,state='SUPERSEDED' WHERE owner_id=? AND current_job",
          identity.userId,
        )
      }
      val state =
        when {
          superseded -> "SUPERSEDED"
          request.startsAtMillis <= clock.millis() -> "EXPIRED"
          else -> "QUEUED"
        }
      jdbc.update(
        "INSERT INTO calendar_draft_jobs(owner_id,request_id,session_id,request_digest,intent,state,current_job,created_at) VALUES (?,?,?,?,?::jsonb,?,?,?)",
        identity.userId,
        id,
        identity.sessionId,
        digest,
        json.writeValueAsString(request),
        state,
        !superseded,
        Timestamp.from(clock.instant()),
      )
      response(read(identity.userId, id)!!)
    }!!
  }

  fun cancel(identity: Identity, id: UUID) =
    tx.executeWithoutResult {
      sessionGuard.lock(identity)
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

  fun status(identity: Identity, id: UUID): CalendarDraftJobResponse =
    tx.execute {
      sessionGuard.lock(identity)
      checked(
        identity,
        read(identity.userId, id) ?: throw ApiException(404, "not_found", "Заявка не найдена"),
      )
    }!!

  private fun checked(identity: Identity, job: Job): CalendarDraftJobResponse {
    if (!job.current || job.state in setOf("FAILED", "STALE", "EXPIRED", "SUPERSEDED"))
      return response(job)
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
      if (claimed.executions > 3) throw aiError("ai_interrupted")
      if (!provider.available) throw aiError("ai_unavailable")
      val intent = json.readValue(claimed.intent, CalendarDraftRequest::class.java)
      if (intent.startsAtMillis <= clock.millis()) {
        finish(claimed, "EXPIRED", null)
        return
      }
      // Per-lease attempt identity isolates abandoned executions. Publication is atomic with job
      // READY.
      val executionRequest = intent.copy(requestId = claimed.token.toString())
      val identity = Identity(claimed.owner, claimed.session, "")
      calendar.create(
        identity,
        json.writeValueAsBytes(executionRequest),
        publicationGuard = {
          val current = read(claimed.owner, claimed.id) ?: unauthorized()
          if (!current.current || current.state != "RUNNING" || current.token != claimed.token)
            throw aiError("ai_context_stale")
          if (intent.startsAtMillis <= clock.millis()) throw aiError("ai_context_stale")
        },
        publish = { generated ->
          val result = generated.copy(requestId = claimed.id.toString())
          jdbc.update(
            "UPDATE calendar_draft_jobs SET state='READY',result=?::jsonb,proposal_id=?,error_code=null WHERE owner_id=? AND request_id=? AND lease_token=? AND current_job",
            json.writeValueAsString(result),
            result.proposal.proposalId,
            claimed.owner,
            claimed.id,
            claimed.token,
          )
        },
      )
    } catch (e: Exception) {
      val code = (e as? ApiException)?.code
      val safe =
        code?.takeIf {
          it in
            setOf(
              "ai_context_stale",
              "ai_unavailable",
              "ai_timeout",
              "ai_busy",
              "ai_interrupted",
              "ai_invalid_response",
              "ai_context_too_large",
            )
        } ?: "ai_unavailable"
      val expired =
        json.readValue(claimed.intent, CalendarDraftRequest::class.java).startsAtMillis <=
          clock.millis()
      finish(
        claimed,
        if (expired) "EXPIRED" else if (safe == "ai_context_stale") "STALE" else "FAILED",
        if (expired) null else safe,
      )
    }
  }

  private fun finish(job: Job, state: String, code: String?) =
    tx.executeWithoutResult {
      jdbc.update(
        "UPDATE calendar_draft_jobs SET state=?,error_code=? WHERE owner_id=? AND request_id=? AND state='RUNNING' AND lease_token=? AND current_job",
        state,
        code,
        job.owner,
        job.id,
        job.token,
      )
    }
}
