package tech.valerochkagym.service.ai

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
import tech.valerochkagym.service.auth.IdentitySessionGuard
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class CoachRunService(
  private val jdbc: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val guard: IdentitySessionGuard,
  private val executor: CoachRunExecutor,
  private val json: ObjectMapper,
  private val clock: Clock,
  @Value("\${gym.coach-runs.enabled:true}") private val enabled: Boolean,
) {
  private val busy = AtomicBoolean()
  private val timerBusy = AtomicBoolean()

  private data class Run(
    val owner: UUID,
    val id: UUID,
    val workout: UUID,
    val version: String,
    val state: String,
    val stage: String,
    val sequence: Long,
    val input: JsonNode,
    val checkpoint: JsonNode?,
    val result: JsonNode?,
    val error: String?,
    val token: UUID?,
    val executions: Int,
    val ordinal: Long,
  )

  private fun row(r: ResultSet) =
    Run(
      r.getObject("owner_id", UUID::class.java),
      r.getObject("request_id", UUID::class.java),
      r.getObject("workout_id", UUID::class.java),
      r.getString("context_version"),
      r.getString("state"),
      r.getString("stage"),
      r.getLong("last_event_sequence"),
      json.readTree(r.getString("input")),
      r.getString("checkpoint")?.let(json::readTree),
      r.getString("result")?.let(json::readTree),
      r.getString("error_code"),
      r.getObject("lease_token", UUID::class.java),
      r.getInt("executions"),
      r.getLong("ordinal"),
    )

  private fun read(owner: UUID, id: UUID, lock: Boolean = false): Run? =
    jdbc
      .query(
        "SELECT * FROM coach_runs WHERE owner_id=? AND request_id=?" +
          if (lock) " FOR UPDATE" else "",
        { r, _ -> row(r) },
        owner,
        id,
      )
      .firstOrNull()

  private fun response(r: Run): JsonNode =
    json.valueToTree(
      mapOf(
        "runId" to r.id.toString(),
        "ordinal" to r.ordinal,
        "requestId" to r.id.toString(),
        "workoutId" to r.workout.toString(),
        "contextVersion" to r.version,
        "state" to r.state,
        "stage" to r.stage,
        "lastEventSequence" to r.sequence,
        "result" to r.result,
        "errorCode" to r.error,
        "applicationStatus" to
          jdbc
            .query(
              "SELECT status FROM coach_run_receipts WHERE owner_id=? AND request_id=? LIMIT 1",
              { row, _ -> row.getString(1) },
              r.owner,
              r.id,
            )
            .firstOrNull(),
      )
    )

  private fun missing(): Nothing = throw ApiException(404, "not_found", "Задача не найдена")

  private fun conflict(): Nothing =
    throw ApiException(409, "coach_request_conflict", "Запрос уже существует с другим содержимым")

  private fun uuid(n: JsonNode?): UUID =
    try {
      UUID.fromString(n?.asString()).also {
        if (it.toString() != n?.asString()) bad("Некорректный идентификатор")
      }
    } catch (_: RuntimeException) {
      bad("Некорректный идентификатор")
    }

  private fun digest(value: JsonNode) =
    MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)).joinToString("") {
      "%02x".format(it)
    }

  fun parse(raw: ByteArray): JsonNode {
    if (raw.isEmpty() || raw.size > 2 * 1024 * 1024) bad("Некорректный размер контекста")
    return try {
      json
        .tokenStreamFactory()
        .rebuild()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()
        .createParser(raw)
        .use { p ->
          json.readTree(p).also {
            if (!it.isObject || p.nextToken() != null) bad("Некорректный запрос")
          }
        }
    } catch (_: Exception) {
      bad("Некорректный запрос")
    }
  }

  private fun validateSnapshot(input: JsonNode, workout: UUID) {
    if (
      input["contextVersion"]?.asString()?.matches(Regex("[a-f0-9]{64}")) != true ||
        input["snapshot"]?.isObject != true
    )
      bad("Некорректный контекст")
    val snapshot = input["snapshot"]
    if (
      uuid(snapshot["workout_id"]) != workout ||
        snapshot["revision"]?.isIntegralNumber != true ||
        snapshot["revision"].asLong() < 0
    )
      bad("Некорректная тренировка")
    if (snapshot["exercises"]?.isArray != true || snapshot["exercises"].size() > 100)
      bad("Некорректные упражнения")
  }

  fun submit(identity: Identity, raw: ByteArray): JsonNode {
    val input = parse(raw)
    uuid(input["requestId"])
    uuid(input["workoutId"])
    validateSnapshot(input, uuid(input["workoutId"]))
    if (
      input["message"]?.isString != true ||
        input["message"].asString().length > 4000 ||
        input["history"]?.isArray != true ||
        input["history"].size() > 80
    )
      bad("Некорректное сообщение")
    if (
      input["model"]?.let { !it.isNull && (!it.isString || it.asString().length > 200) } == true ||
        input["automatic"]?.isBoolean == false
    )
      bad("Некорректные параметры")
    input["history"].forEach { item ->
      if (
        item["role"]?.asString() !in setOf("user", "assistant") ||
          item["text"]?.isString != true ||
          item["text"].asString().length > 16000
      )
        bad("Некорректная история")
    }
    return tx.execute {
      guard.lock(identity)
      insert(identity.userId, input)
    }!!
  }

  private fun insert(owner: UUID, input: JsonNode): JsonNode {
    val id = uuid(input["requestId"])
    val workout = uuid(input["workoutId"])
    val hash = digest(input)
    val prior = read(owner, id)
    if (prior != null) {
      if (
        jdbc
          .queryForObject(
            "SELECT request_digest FROM coach_runs WHERE owner_id=? AND request_id=?",
            String::class.java,
            owner,
            id,
          )
          ?.trim() != hash
      )
        conflict()
      return response(prior)
    }
    jdbc.update(
      "INSERT INTO coach_runs(owner_id,request_id,workout_id,context_version,request_digest,input,automatic,state) VALUES (?,?,?,?,?,?::jsonb,?,'QUEUED')",
      owner,
      id,
      workout,
      input["contextVersion"].asString(),
      hash,
      json.writeValueAsString(input),
      input["automatic"]?.asBoolean() ?: false,
    )
    event(owner, id, "progress", "queued")
    return response(read(owner, id)!!)
  }

  fun status(identity: Identity, id: UUID): JsonNode =
    tx.execute {
      guard.lock(identity)
      response(read(identity.userId, id) ?: missing())
    }!!

  fun list(identity: Identity, workout: UUID, after: Long): List<JsonNode> =
    tx.execute {
      guard.lock(identity)
      jdbc.query(
        "SELECT * FROM coach_runs WHERE owner_id=? AND workout_id=? AND ordinal>? ORDER BY ordinal LIMIT 200",
        { r, _ -> response(row(r)) },
        identity.userId,
        workout,
        after,
      )
    }!!

  fun events(identity: Identity, id: UUID, after: Long): List<JsonNode> =
    tx.execute {
      guard.lock(identity)
      read(identity.userId, id) ?: missing()
      jdbc.query(
        "SELECT payload::text FROM coach_run_events WHERE owner_id=? AND request_id=? AND sequence>? ORDER BY sequence LIMIT 100",
        { r, _ -> json.readTree(r.getString(1)) },
        identity.userId,
        id,
        after,
      )
    }!!

  fun cancel(identity: Identity, id: UUID): JsonNode =
    tx.execute {
      guard.lock(identity)
      val run = read(identity.userId, id, true) ?: missing()
      if (run.state in ACTIVE) {
        jdbc.update(
          "UPDATE coach_runs SET state='CANCELLED',stage='cancelled',lease_token=null,lease_until=null WHERE owner_id=? AND request_id=?",
          identity.userId,
          id,
        )
        event(identity.userId, id, "completed", "cancelled")
      }
      response(read(identity.userId, id)!!)
    }!!

  private fun event(
    owner: UUID,
    id: UUID,
    type: String,
    stage: String? = null,
    text: String? = null,
  ) {
    val sequence =
      jdbc.queryForObject(
        "UPDATE coach_runs SET last_event_sequence=last_event_sequence+1 WHERE owner_id=? AND request_id=? RETURNING last_event_sequence",
        Long::class.java,
        owner,
        id,
      )!!
    val payload =
      mapOf(
        "sequence" to sequence,
        "type" to type,
        "stage" to stage,
        "text" to text,
        "run" to if (type == "completed") response(read(owner, id)!!) else null,
      )
    jdbc.update(
      "INSERT INTO coach_run_events(owner_id,request_id,sequence,payload) VALUES (?,?,?,?::jsonb)",
      owner,
      id,
      sequence,
      json.writeValueAsString(payload),
    )
  }

  fun session(identity: Identity, workout: UUID, raw: ByteArray): JsonNode =
    tx.execute {
      val input = parse(raw)
      validateSnapshot(input, workout)
      val eventId = uuid(input["eventId"])
      val sequence = input["sequence"]?.asLong() ?: 0
      if (
        sequence < 1 ||
          input["sequence"]?.isIntegralNumber != true ||
          input["active"]?.isBoolean == false ||
          input["initiativeEnabled"]?.isBoolean == false
      )
        bad("Некорректная последовательность")
      guard.lock(identity)
      val hash = digest(input)
      val prior =
        jdbc
          .query(
            "SELECT request_digest FROM coach_session_events WHERE owner_id=? AND event_id=?",
            { r, _ -> r.getString(1).trim() },
            identity.userId,
            eventId,
          )
          .firstOrNull()
      if (prior != null) {
        if (prior != hash) conflict()
        return@execute json.valueToTree(mapOf("accepted" to true, "sequence" to sequence))
      }
      val previous =
        jdbc
          .query(
            "SELECT sequence,snapshot::text,context_version FROM coach_sessions WHERE owner_id=? AND workout_id=? FOR UPDATE",
            { r, _ -> Triple(r.getLong(1), json.readTree(r.getString(2)), r.getString(3)) },
            identity.userId,
            workout,
          )
          .firstOrNull()
      jdbc.update(
        "INSERT INTO coach_session_events(owner_id,event_id,workout_id,request_digest) VALUES (?,?,?,?)",
        identity.userId,
        eventId,
        workout,
        hash,
      )
      if (previous != null && sequence <= previous.first)
        return@execute json.valueToTree(mapOf("accepted" to false, "sequence" to previous.first))
      val active = input["active"]?.asBoolean() ?: true
      val initiative = input["initiativeEnabled"]?.asBoolean() ?: false
      jdbc.update(
        "INSERT INTO coach_sessions(owner_id,workout_id,sequence,context_version,snapshot,initiative_enabled,active) VALUES (?,?,?,?,?::jsonb,?,?) ON CONFLICT(owner_id,workout_id) DO UPDATE SET sequence=excluded.sequence,context_version=excluded.context_version,snapshot=excluded.snapshot,initiative_enabled=excluded.initiative_enabled,active=excluded.active,updated_at=now()",
        identity.userId,
        workout,
        sequence,
        input["contextVersion"].asString(),
        json.writeValueAsString(input["snapshot"]),
        initiative,
        active,
      )
      if (!active || !initiative) {
        jdbc
          .query(
            "SELECT request_id FROM coach_runs WHERE owner_id=? AND workout_id=? AND automatic AND state IN ('QUEUED','RUNNING') FOR UPDATE",
            { r, _ -> r.getObject(1, UUID::class.java) },
            identity.userId,
            workout,
          )
          .forEach { id ->
            jdbc.update(
              "UPDATE coach_runs SET state='SUPERSEDED',stage='superseded',lease_token=null WHERE owner_id=? AND request_id=?",
              identity.userId,
              id,
            )
            event(identity.userId, id, "completed", "superseded")
          }
      } else if (initiative && previous?.third != input["contextVersion"].asString()) {
        val memory =
          json.valueToTree<JsonNode>(
            jdbc.query(
              "SELECT r.status,j.result::text FROM coach_run_receipts r JOIN coach_runs j ON j.owner_id=r.owner_id AND j.request_id=r.request_id WHERE r.owner_id=? AND j.workout_id=?",
              { r, _ ->
                val proposal = json.readTree(r.getString(2))["proposal"]
                mapOf(
                  "status" to r.getString(1),
                  "reason" to proposal?.get("reason"),
                  "sectionIds" to
                    proposal
                      ?.get("operations")
                      ?.toList()
                      .orEmpty()
                      .mapNotNull { it["section_id"]?.asString() }
                      .distinct(),
                )
              },
              identity.userId,
              workout,
            )
          )
        val reason = executor.initiativeDecision(input["snapshot"], previous?.second, memory)
        if (reason != null) {
          jdbc
            .query(
              "SELECT request_id FROM coach_runs WHERE owner_id=? AND workout_id=? AND automatic AND state='QUEUED' FOR UPDATE",
              { r, _ -> r.getObject(1, UUID::class.java) },
              identity.userId,
              workout,
            )
            .forEach { id ->
              jdbc.update(
                "UPDATE coach_runs SET state='SUPERSEDED',stage='superseded' WHERE owner_id=? AND request_id=?",
                identity.userId,
                id,
              )
              event(identity.userId, id, "completed", "superseded")
            }
          insert(
            identity.userId,
            json.valueToTree(
              mapOf(
                "requestId" to eventId.toString(),
                "workoutId" to workout.toString(),
                "contextVersion" to input["contextVersion"],
                "snapshot" to input["snapshot"],
                "message" to reason,
                "history" to emptyList<Any>(),
                "automatic" to true,
              )
            ),
          )
        }
      }
      json.valueToTree(mapOf("accepted" to true, "sequence" to sequence))
    }!!

  fun receipt(identity: Identity, id: UUID, raw: ByteArray) =
    tx.executeWithoutResult {
      guard.lock(identity)
      val input = parse(raw)
      val receipt = uuid(input["receiptId"])
      val proposal = uuid(input["proposalId"])
      val status = input["status"]?.asString()
      if (status !in setOf("APPLIED", "REJECTED", "STALE")) bad("Некорректный статус")
      val run = read(identity.userId, id, true) ?: missing()
      if (run.result?.get("proposal")?.get("proposalId")?.asString() != proposal.toString())
        conflict()
      val previousStatus =
        jdbc
          .query(
            "SELECT status FROM coach_run_receipts WHERE owner_id=? AND request_id=? AND proposal_id=? LIMIT 1",
            { r, _ -> r.getString(1) },
            identity.userId,
            id,
            proposal,
          )
          .firstOrNull()
      if (previousStatus != null && previousStatus != status) conflict()
      val existing =
        jdbc
          .query(
            "SELECT request_id,proposal_id,status FROM coach_run_receipts WHERE owner_id=? AND receipt_id=?",
            { r, _ ->
              Triple(
                r.getObject(1, UUID::class.java),
                r.getObject(2, UUID::class.java),
                r.getString(3),
              )
            },
            identity.userId,
            receipt,
          )
          .firstOrNull()
      if (existing != null) {
        if (existing != Triple(id, proposal, status)) conflict()
      } else
        jdbc.update(
          "INSERT INTO coach_run_receipts(owner_id,request_id,receipt_id,proposal_id,status) VALUES (?,?,?,?,?)",
          identity.userId,
          id,
          receipt,
          proposal,
          status,
        )
    }

  @Scheduled(fixedDelayString = "\${gym.coach-runs.poll-ms:1000}")
  fun tick() {
    if (enabled && busy.compareAndSet(false, true))
      Thread.ofVirtual().start {
        try {
          runNext()
        } catch (_: Exception) {
          /* A lost lease remains recoverable. */
        } finally {
          busy.set(false)
        }
      }
  }

  @Scheduled(fixedDelayString = "\${gym.coach-runs.timer-poll-ms:5000}")
  fun timerTick() {
    if (enabled && timerBusy.compareAndSet(false, true))
      Thread.ofVirtual().start {
        try {
          scheduleTimers()
        } catch (_: Exception) {
          /* A later timer scan retries transient database failures. */
        } finally {
          timerBusy.set(false)
        }
      }
  }

  internal fun scheduleTimers() {
    val owners =
      jdbc.query(
        "SELECT owner_id,workout_id FROM coach_sessions WHERE active AND initiative_enabled AND updated_at>?",
        { r, _ -> r.getObject(1, UUID::class.java) to r.getObject(2, UUID::class.java) },
        Timestamp.from(clock.instant().minusSeconds(120)),
      )
    owners.forEach { (owner, workout) ->
      tx.executeWithoutResult {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID::class.java, owner)
        val session =
          jdbc
            .query(
              "SELECT snapshot::text,context_version FROM coach_sessions WHERE owner_id=? AND workout_id=? AND active AND initiative_enabled AND updated_at>? FOR UPDATE",
              { r, _ -> json.readTree(r.getString(1)) to r.getString(2) },
              owner,
              workout,
              Timestamp.from(clock.instant().minusSeconds(120)),
            )
            .firstOrNull() ?: return@executeWithoutResult
        val ends =
          session.first["available_time_ends_at_millis"]?.asLong() ?: return@executeWithoutResult
        val reminded =
          jdbc.queryForObject(
            "SELECT timer_version FROM coach_sessions WHERE owner_id=? AND workout_id=?",
            String::class.java,
            owner,
            workout,
          )
        if (reminded == ends.toString()) return@executeWithoutResult
        val remaining = ends - clock.millis()
        if (ends <= 0 || remaining > 300000) return@executeWithoutResult
        val pending =
          jdbc.queryForObject(
            "SELECT count(*) FROM coach_runs r WHERE owner_id=? AND workout_id=? AND (state IN ('QUEUED','RUNNING') OR (state='SUCCEEDED' AND result->>'kind'='proposal' AND (result->'proposal'->>'expiresAtMillis')::bigint>? AND NOT EXISTS (SELECT 1 FROM coach_run_receipts p WHERE p.owner_id=r.owner_id AND p.request_id=r.request_id)))",
            Int::class.java,
            owner,
            workout,
            clock.millis(),
          )!!
        if (pending > 0) return@executeWithoutResult
        jdbc.update(
          "UPDATE coach_sessions SET timer_version=? WHERE owner_id=? AND workout_id=?",
          ends.toString(),
          owner,
          workout,
        )
        val timerSnapshot =
          (session.first.deepCopy() as tools.jackson.databind.node.ObjectNode).apply {
            put("available_time_minutes", ((remaining.coerceAtLeast(0) + 59999) / 60000).toInt())
          }
        insert(
          owner,
          json.valueToTree(
            mapOf(
              "requestId" to UUID.randomUUID().toString(),
              "workoutId" to workout.toString(),
              "contextVersion" to session.second,
              "snapshot" to timerSnapshot,
              "message" to
                "До конца доступного времени осталось ${remaining.coerceAtLeast(0)/60000} мин. Проверь необходимость изменения оставшихся подходов.",
              "history" to emptyList<Any>(),
              "automatic" to true,
            )
          ),
        )
      }
    }
  }

  internal fun runNext() {
    val run =
      tx.execute {
        // Lock the account to serialize claims with submit/session changes and competing workers.
        val candidate =
          jdbc
            .query(
              "SELECT * FROM coach_runs r WHERE (state='QUEUED' OR (state='RUNNING' AND lease_until<=?)) AND NOT EXISTS (SELECT 1 FROM coach_runs older WHERE older.owner_id=r.owner_id AND older.workout_id=r.workout_id AND older.ordinal<r.ordinal AND older.state IN ('QUEUED','RUNNING')) ORDER BY ordinal LIMIT 1",
              { r, _ -> row(r) },
              Timestamp.from(clock.instant()),
            )
            .firstOrNull() ?: return@execute null
        jdbc.queryForObject(
          "SELECT id FROM users WHERE id=? FOR UPDATE",
          UUID::class.java,
          candidate.owner,
        )
        val token = UUID.randomUUID()
        val changed =
          jdbc.update(
            "UPDATE coach_runs SET state='RUNNING',stage='analyzing',executions=executions+1,lease_token=?,lease_until=? WHERE owner_id=? AND request_id=? AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=?))",
            token,
            Timestamp.from(clock.instant().plusSeconds(90)),
            candidate.owner,
            candidate.id,
            Timestamp.from(clock.instant()),
          )
        if (changed == 0) null
        else {
          event(candidate.owner, candidate.id, "progress", "analyzing")
          read(candidate.owner, candidate.id)
        }
      } ?: return
    val stopped = AtomicBoolean()
    val renewal =
      Thread.ofVirtual().start {
        try {
          while (!stopped.get()) {
            Thread.sleep(20000)
            if (!stopped.get())
              fenced(run) {
                jdbc.update(
                  "UPDATE coach_runs SET lease_until=? WHERE owner_id=? AND request_id=?",
                  Timestamp.from(clock.instant().plusSeconds(90)),
                  run.owner,
                  run.id,
                )
              }
          }
        } catch (_: Exception) {
          stopped.set(true)
        }
      }
    val hooks =
      object : CoachRunHooks {
        override fun checkActive() {
          if (stopped.get()) throw IllegalStateException("Lease lost")
          fenced(run) {}
        }

        override fun checkpoint(value: JsonNode) {
          fenced(run) {
            jdbc.update(
              "UPDATE coach_runs SET checkpoint=?::jsonb WHERE owner_id=? AND request_id=?",
              json.writeValueAsString(value),
              run.owner,
              run.id,
            )
          }
        }

        override fun progress(stage: String) {
          fenced(run) {
            jdbc.update(
              "UPDATE coach_runs SET stage=? WHERE owner_id=? AND request_id=?",
              stage.take(80),
              run.owner,
              run.id,
            )
            event(run.owner, run.id, "progress", stage)
          }
        }

        override fun text(value: String) {
          fenced(run) { event(run.owner, run.id, "text", text = value) }
        }
      }
    try {
      if (run.executions > 3) throw aiError("ai_interrupted")
      val result = executor.execute(run.owner, executionInput(run), run.checkpoint, hooks)
      fenced(run) {
        jdbc.update(
          "UPDATE coach_runs SET state='SUCCEEDED',stage='completed',result=?::jsonb,lease_token=null,lease_until=null WHERE owner_id=? AND request_id=?",
          json.writeValueAsString(result),
          run.owner,
          run.id,
        )
        event(run.owner, run.id, "completed", "completed")
      }
    } catch (e: Exception) {
      runCatching {
        fenced(run) {
          val code =
            (e as? ApiException)?.code?.takeIf {
              it in
                setOf(
                  "ai_timeout",
                  "ai_unavailable",
                  "ai_busy",
                  "ai_invalid_response",
                  "ai_context_too_large",
                  "ai_interrupted",
                )
            } ?: "ai_unavailable"
          jdbc.update(
            "UPDATE coach_runs SET state='FAILED',stage='failed',error_code=?,lease_token=null,lease_until=null WHERE owner_id=? AND request_id=?",
            code,
            run.owner,
            run.id,
          )
          event(run.owner, run.id, "completed", "failed")
        }
      }
    } finally {
      stopped.set(true)
      renewal.interrupt()
    }
  }

  private fun executionInput(run: Run): JsonNode {
    if (run.checkpoint != null) return run.input
    val prior =
      jdbc
        .query(
          "SELECT input::text,result::text,automatic FROM coach_runs WHERE owner_id=? AND workout_id=? AND ordinal<? AND state='SUCCEEDED' ORDER BY ordinal DESC LIMIT 20",
          { r, _ ->
            Triple(json.readTree(r.getString(1)), json.readTree(r.getString(2)), r.getBoolean(3))
          },
          run.owner,
          run.workout,
          run.ordinal,
        )
        .reversed()
    if (prior.isEmpty()) return run.input
    val history =
      prior
        .flatMap { (input, result, automatic) ->
          buildList {
            if (!automatic) add(mapOf("role" to "user", "text" to input["message"].asString()))
            val text = result["text"]?.asString().orEmpty()
            if (text.isNotBlank()) add(mapOf("role" to "assistant", "text" to text))
          }
        }
        .takeLast(20)
    return (run.input.deepCopy() as tools.jackson.databind.node.ObjectNode).apply {
      set("history", json.valueToTree<JsonNode>(history))
    }
  }

  private fun fenced(run: Run, action: () -> Unit) =
    tx.executeWithoutResult {
      val current = read(run.owner, run.id, true) ?: throw IllegalStateException("Lease lost")
      val valid =
        jdbc.queryForObject(
          "SELECT lease_until>? FROM coach_runs WHERE owner_id=? AND request_id=?",
          Boolean::class.java,
          Timestamp.from(clock.instant()),
          run.owner,
          run.id,
        ) == true
      if (current.state != "RUNNING" || current.token != run.token || !valid)
        throw IllegalStateException("Lease lost")
      action()
    }

  companion object {
    private val ACTIVE = setOf("QUEUED", "RUNNING")
  }
}
