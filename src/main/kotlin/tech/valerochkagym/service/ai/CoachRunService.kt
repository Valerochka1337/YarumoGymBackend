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
        "origin" to if (r.input["automatic"]?.asBoolean() == true) "COACH" else "USER",
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
    val run = read(owner, id)!!
    val origin = if (run.input["automatic"]?.asBoolean() == true) "COACH" else "USER"
    val payload =
      mapOf(
        "runId" to id.toString(),
        "origin" to origin,
        "sequence" to sequence,
        "type" to type,
        "stage" to stage,
        "text" to text,
        "run" to response(run),
      )
    jdbc.update(
      "INSERT INTO coach_run_events(owner_id,request_id,sequence,payload) VALUES (?,?,?,?::jsonb)",
      owner,
      id,
      sequence,
      json.writeValueAsString(payload),
    )
    val workoutSequence =
      jdbc.queryForObject(
        "INSERT INTO coach_workout_event_cursors(owner_id,workout_id,sequence) VALUES (?,?,1) ON CONFLICT(owner_id,workout_id) DO UPDATE SET sequence=coach_workout_event_cursors.sequence+1 RETURNING sequence",
        Long::class.java,
        owner,
        run.workout,
      )!!
    jdbc.update(
      "INSERT INTO coach_workout_events(owner_id,workout_id,sequence,request_id,payload) VALUES (?,?,?,?,?::jsonb)",
      owner,
      run.workout,
      workoutSequence,
      id,
      json.writeValueAsString(
        payload +
          mapOf("sequence" to workoutSequence, "type" to if (sequence == 1L) "created" else type)
      ),
    )
  }

  fun workoutEvents(identity: Identity, workout: UUID, after: Long): List<JsonNode> =
    tx.execute {
      guard.lock(identity)
      jdbc.query(
        "SELECT payload::text FROM coach_workout_events WHERE owner_id=? AND workout_id=? AND sequence>? ORDER BY sequence LIMIT 100",
        { r, _ -> json.readTree(r.getString(1)) },
        identity.userId,
        workout,
        after,
      )
    }!!

  fun message(identity: Identity, workout: UUID, raw: ByteArray): JsonNode =
    tx.execute {
      guard.lock(identity)
      val body = parse(raw)
      uuid(body["requestId"])
      val message = body["message"]
      if (
        message?.isString != true ||
          message.asString().isBlank() ||
          message.asString().length > 4000
      )
        bad("Некорректное сообщение")
      val state = body["state"] ?: bad("Необходим снимок")
      validateSnapshot(state, workout)
      val model = body["model"]
      if (model != null && !model.isNull && (!model.isString || model.asString().length > 200))
        bad("Некорректная модель")
      // Insert first so admission of this snapshot cannot initiate a competing automatic run.
      val input =
        json.valueToTree<JsonNode>(
          mapOf(
            "requestId" to body["requestId"],
            "workoutId" to workout.toString(),
            "contextVersion" to state["contextVersion"],
            "snapshot" to state["snapshot"],
            "message" to message,
            "model" to model,
            "automatic" to false,
            "history" to emptyList<Any>(),
            "admissionDigest" to digest(body),
          )
        )
      val existed = read(identity.userId, uuid(body["requestId"])) != null
      val result = insert(identity.userId, input)
      if (existed) return@execute result
      session(identity, workout, json.writeValueAsBytes(state))
      if (model != null)
        jdbc.update(
          "UPDATE coach_sessions SET model=? WHERE owner_id=? AND workout_id=?",
          model.takeUnless { it.isNull }?.asString(),
          identity.userId,
          workout,
        )
      result
    }!!

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
        return@execute jdbc
          .query(
            "SELECT response::text FROM coach_session_events WHERE owner_id=? AND event_id=?",
            { r, _ -> r.getString(1)?.let(json::readTree) },
            identity.userId,
            eventId,
          )
          .firstOrNull() ?: json.valueToTree(mapOf("accepted" to true, "sequence" to sequence))
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
      fun acknowledge(accepted: Boolean, acknowledgedSequence: Long): JsonNode {
        val result =
          json.valueToTree<JsonNode>(
            mapOf("accepted" to accepted, "sequence" to acknowledgedSequence)
          )
        jdbc.update(
          "UPDATE coach_session_events SET response=?::jsonb WHERE owner_id=? AND event_id=?",
          json.writeValueAsString(result),
          identity.userId,
          eventId,
        )
        return result
      }
      if (previous != null && sequence <= previous.first)
        return@execute acknowledge(false, previous.first)
      val semantic = semanticVersion(input["snapshot"])
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
      jdbc.update(
        "UPDATE coach_sessions SET semantic_version=? WHERE owner_id=? AND workout_id=?",
        semantic,
        identity.userId,
        workout,
      )
      input["model"]?.let { model ->
        if (!model.isNull && (!model.isString || model.asString().length > 200))
          bad("Некорректная модель")
        jdbc.update(
          "UPDATE coach_sessions SET model=? WHERE owner_id=? AND workout_id=?",
          model.takeUnless { it.isNull }?.asString(),
          identity.userId,
          workout,
        )
      }
      migrateMemory(identity.userId, workout, input["snapshot"])
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
      } else {
        evaluate(identity.userId, workout)
      }
      acknowledge(true, sequence)
    }!!

  fun receipt(identity: Identity, id: UUID, raw: ByteArray) =
    tx.executeWithoutResult {
      guard.lock(identity)
      val input = parse(raw)
      val receipt = uuid(input["receiptId"])
      val proposal = uuid(input["proposalId"])
      val status = input["status"]?.asString()
      val reason = input["reason"]?.takeUnless { it.isNull }?.asString()
      if (reason != null && reason.length > 2000) bad("Некорректная причина")
      input["snapshot"]
        ?.takeUnless { it.isNull }
        ?.let {
          if (
            !it.isObject ||
              uuid(it["workout_id"]) != (read(identity.userId, id) ?: missing()).workout
          )
            bad("Некорректный снимок решения")
        }
      val requestHash = digest(input)
      val storedHash =
        jdbc
          .query(
            "SELECT request_digest FROM coach_run_receipts WHERE owner_id=? AND receipt_id=?",
            { r, _ -> r.getString(1) },
            identity.userId,
            receipt,
          )
          .firstOrNull()
      if (storedHash != null && storedHash != requestHash) conflict()
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
      jdbc.update(
        "UPDATE coach_run_receipts SET reason=?,snapshot=?::jsonb,request_digest=? WHERE owner_id=? AND receipt_id=?",
        reason,
        json.writeValueAsString(input["snapshot"] ?: run.input["snapshot"]),
        requestHash,
        identity.userId,
        receipt,
      )
      remember(run, proposal, status!!, reason, input["snapshot"] ?: run.input["snapshot"])
      evaluate(identity.userId, run.workout)
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
        evaluate(owner, workout)
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
              "model" to sessionModel(owner, workout),
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
        if (!automaticAllowed(candidate)) {
          supersede(candidate)
          return@execute null
        }
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
          "SELECT input::text,result::text,automatic FROM coach_runs WHERE owner_id=? AND workout_id=? AND ordinal<? AND (NOT automatic OR state='SUCCEEDED') ORDER BY ordinal DESC LIMIT 20",
          { r, _ ->
            Triple(
              json.readTree(r.getString(1)),
              r.getString(2)?.let(json::readTree) ?: json.nullNode(),
              r.getBoolean(3),
            )
          },
          run.owner,
          run.workout,
          run.ordinal,
        )
        .reversed()
    val knownIds =
      jdbc
        .query(
          "SELECT request_id FROM coach_runs WHERE owner_id=? AND workout_id=?",
          { r, _ -> r.getObject(1, UUID::class.java) },
          run.owner,
          run.workout,
        )
        .flatMap { listOf(it, UUID.nameUUIDFromBytes("coach-answer:$it".toByteArray())) }
        .toSet()
    val legacy =
      jdbc
        .query(
          "SELECT id,payload::text FROM coach_journal WHERE user_id=? AND workout_id=? AND NOT deleted AND payload->>'role' IN ('user','assistant') AND created_at < (SELECT (extract(epoch FROM min(created_at))*1000)::bigint FROM coach_runs WHERE owner_id=? AND workout_id=?) ORDER BY created_at DESC,id LIMIT 40",
          { r, _ -> r.getObject(1, UUID::class.java) to json.readTree(r.getString(2)) },
          run.owner,
          run.workout,
          run.owner,
          run.workout,
        )
        .reversed()
        .filter { it.first !in knownIds }
        .mapNotNull { (_, payload) ->
          payload["text"]
            ?.takeIf { it.isString }
            ?.asString()
            ?.take(16000)
            ?.let { mapOf("role" to payload["role"].asString(), "text" to it) }
        }
    val history =
      (legacy +
          prior.flatMap { (input, result, automatic) ->
            buildList {
              if (!automatic) add(mapOf("role" to "user", "text" to input["message"].asString()))
              val text = result["text"]?.asString().orEmpty()
              if (text.isNotBlank() && result["kind"]?.asString() != "no_change")
                add(mapOf("role" to "assistant", "text" to text))
            }
          })
        .takeLast(40)
    return (run.input.deepCopy() as tools.jackson.databind.node.ObjectNode).apply {
      set("history", json.valueToTree<JsonNode>(history))
      if (run.input["model"] == null || run.input["model"].isNull) {
        sessionModel(run.owner, run.workout)?.let { put("model", it) }
      }
      val snapshot = run.input["snapshot"].deepCopy() as tools.jackson.databind.node.ObjectNode
      snapshot.set("decisions", memory(run.owner, run.workout))
      snapshot["available_time_ends_at_millis"]
        ?.takeUnless { it.isNull }
        ?.asLong()
        ?.let { ends ->
          snapshot.put(
            "available_time_minutes",
            ((ends - clock.millis()).coerceAtLeast(0) + 59999) / 60000,
          )
        }
      (snapshot["rest"] as? tools.jackson.databind.node.ObjectNode)?.let { rest ->
        rest["ends_at_millis"]
          ?.takeUnless { it.isNull }
          ?.asLong()
          ?.let { ends ->
            rest.put("remaining_seconds", ((ends - clock.millis()).coerceAtLeast(0) + 999) / 1000)
          }
      }
      snapshot["observed_at_millis"]
        ?.takeUnless { it.isNull }
        ?.asLong()
        ?.let { observed ->
          snapshot.put(
            "elapsed_seconds",
            (snapshot["elapsed_seconds"]?.asLong() ?: 0) +
              (clock.millis() - observed).coerceAtLeast(0) / 1000,
          )
        }
      set("snapshot", snapshot)
    }
  }

  private fun fenced(run: Run, action: () -> Unit) {
    val allowed =
      tx.execute {
        jdbc.queryForObject(
          "SELECT id FROM users WHERE id=? FOR UPDATE",
          UUID::class.java,
          run.owner,
        )
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
        if (!automaticAllowed(current)) {
          supersede(current)
          false
        } else {
          action()
          true
        }
      }
    if (allowed != true) throw IllegalStateException("Automatic context expired")
  }

  internal fun semanticVersion(snapshot: JsonNode): String {
    fun canonical(node: JsonNode): JsonNode =
      when {
        node.isObject ->
          json.valueToTree(
            node.properties().sortedBy { it.key }.associate { it.key to canonical(it.value) }
          )
        node.isArray -> json.valueToTree(node.toList().map(::canonical))
        else -> node
      }
    val stable = snapshot.deepCopy() as tools.jackson.databind.node.ObjectNode
    listOf("elapsed_seconds", "observed_at_millis", "pulse").forEach { stable.remove(it) }
    if (stable.has("available_time_ends_at_millis")) stable.remove("available_time_minutes")
    (stable["rest"] as? tools.jackson.databind.node.ObjectNode)?.remove("remaining_seconds")
    return digest(canonical(stable))
  }

  private fun sessionModel(owner: UUID, workout: UUID): String? =
    jdbc
      .query(
        "SELECT model FROM coach_sessions WHERE owner_id=? AND workout_id=?",
        { r, _ -> r.getString(1) },
        owner,
        workout,
      )
      .firstOrNull()

  private fun memory(owner: UUID, workout: UUID): JsonNode =
    json.valueToTree(
      jdbc
        .query(
          "SELECT payload::text FROM coach_decisions WHERE owner_id=? AND workout_id=? ORDER BY created_at DESC,proposal_id LIMIT 30",
          { r, _ -> json.readTree(r.getString(1)) },
          owner,
          workout,
        )
        .reversed()
    )

  private fun migrateMemory(owner: UUID, workout: UUID, snapshot: JsonNode) {
    snapshot["decisions"]
      ?.takeIf { it.isArray }
      ?.toList()
      ?.takeLast(30)
      ?.forEach { decision ->
        val proposal =
          runCatching { UUID.fromString(decision["proposalId"]?.asString()) }.getOrNull()
            ?: return@forEach
        if (decision["status"]?.asString() !in setOf("APPLIED", "REJECTED", "STALE")) return@forEach
        jdbc.update(
          "INSERT INTO coach_decisions(owner_id,workout_id,proposal_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
          owner,
          workout,
          proposal,
          json.writeValueAsString(decision),
        )
      }
  }

  private fun remember(
    run: Run,
    proposal: UUID,
    status: String,
    reason: String?,
    snapshot: JsonNode,
  ) {
    val operations = run.result?.get("proposal")?.get("operations")?.toList().orEmpty()
    val sets = operations.mapNotNull { it["set_id"]?.asString() }.toMutableSet()
    if (operations.any { it["action"]?.asString()?.contains("rest") == true }) {
      snapshot["previous_set_id"]?.takeUnless { it.isNull }?.asString()?.let(sets::add)
    }
    val sections =
      (operations.flatMap { op ->
          listOfNotNull(op["section_id"]?.asString(), op["source_section_id"]?.asString()) +
            op["section_ids"]?.toList()?.map { it.asString() }.orEmpty()
        } +
          snapshot["exercises"]
            ?.filter { e -> e["sets"]?.any { it["set_id"]?.asString() in sets } == true }
            ?.map { it["section_id"].asString() }
            .orEmpty())
        .toSet()
    val evidence =
      snapshot["exercises"]
        ?.filter { it["section_id"]?.asString() in sections }
        ?.mapNotNull { e ->
          e["sets"]
            ?.filter { it["completed"]?.asBoolean() == true }
            ?.maxByOrNull { it["completed_at"]?.asLong() ?: 0 }
            ?.let { set ->
              mapOf(
                "sectionId" to e["section_id"],
                "setId" to set["set_id"],
                "weightKg" to
                  (set["actual_weight_kg"]?.takeUnless { it.isNull } ?: set["weight_kg"]),
                "reps" to (set["actual_reps"]?.takeUnless { it.isNull } ?: set["reps"]),
                "rir" to set["actual_rir"],
                "feelings" to set["reported_feelings"],
              )
            }
        }
        .orEmpty()
    val value =
      mapOf(
        "proposalId" to proposal.toString(),
        "status" to status,
        "reason" to reason,
        "summary" to run.result?.get("proposal")?.get("reason"),
        "sectionIds" to sections,
        "exerciseIds" to
          (snapshot["exercises"]
              ?.filter { it["section_id"]?.asString() in sections }
              ?.mapNotNull { it["exercise_id"]?.asString() }
              .orEmpty() + operations.mapNotNull { it["exercise_id"]?.asString() })
            .distinct(),
        "evidence" to evidence,
      )
    jdbc.update(
      "INSERT INTO coach_decisions(owner_id,workout_id,proposal_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT(owner_id,workout_id,proposal_id) DO UPDATE SET payload=excluded.payload",
      run.owner,
      run.workout,
      proposal,
      json.writeValueAsString(value),
    )
  }

  private fun blocked(owner: UUID, workout: UUID, excluding: UUID? = null): Boolean =
    jdbc.queryForObject(
      "SELECT count(*) FROM coach_runs r WHERE owner_id=? AND workout_id=? AND request_id<>? AND ((NOT automatic AND state IN ('QUEUED','RUNNING')) OR (state='SUCCEEDED' AND result->>'kind'='proposal' AND COALESCE((result->'proposal'->>'expiresAtMillis')::bigint,0)>? AND NOT EXISTS (SELECT 1 FROM coach_run_receipts p WHERE p.owner_id=r.owner_id AND p.request_id=r.request_id)))",
      Int::class.java,
      owner,
      workout,
      excluding ?: UUID(0, 0),
      clock.millis(),
    )!! > 0

  private fun automaticAllowed(run: Run): Boolean {
    if (run.input["automatic"]?.asBoolean() != true) return true
    return jdbc.queryForObject(
      "SELECT count(*) FROM coach_sessions WHERE owner_id=? AND workout_id=? AND active AND initiative_enabled AND (semantic_version=? OR (semantic_version IS NULL AND context_version=?)) AND updated_at>?",
      Int::class.java,
      run.owner,
      run.workout,
      semanticVersion(run.input["snapshot"]),
      run.version,
      Timestamp.from(clock.instant().minusSeconds(120)),
    )!! > 0 && !blocked(run.owner, run.workout, run.id)
  }

  private fun supersede(run: Run) {
    val changed =
      jdbc.update(
        "UPDATE coach_runs SET state='SUPERSEDED',stage='superseded',lease_token=null,lease_until=null WHERE owner_id=? AND request_id=? AND state IN ('QUEUED','RUNNING')",
        run.owner,
        run.id,
      )
    if (changed > 0) event(run.owner, run.id, "completed", "superseded")
  }

  /** Called under the account lock; deferred changes are re-evaluated by the timer scanner. */
  private fun evaluate(owner: UUID, workout: UUID) {
    val sessionRow =
      jdbc
        .query(
          "SELECT snapshot::text,evaluated_snapshot::text,semantic_version,evaluated_version,context_version FROM coach_sessions WHERE owner_id=? AND workout_id=? AND active AND initiative_enabled AND updated_at>? FOR UPDATE",
          { r, _ ->
            listOf(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))
          },
          owner,
          workout,
          Timestamp.from(clock.instant().minusSeconds(120)),
        )
        .firstOrNull() ?: return
    if (sessionRow[2] == null || sessionRow[2] == sessionRow[3] || blocked(owner, workout)) return
    if (
      jdbc.queryForObject(
        "SELECT count(*) FROM coach_runs WHERE owner_id=? AND workout_id=? AND automatic AND state='RUNNING'",
        Int::class.java,
        owner,
        workout,
      )!! > 0
    )
      return
    val snapshot = json.readTree(sessionRow[0]) as tools.jackson.databind.node.ObjectNode
    snapshot.set("decisions", memory(owner, workout))
    val reason =
      executor.initiativeDecision(
        snapshot,
        sessionRow[1]?.let(json::readTree),
        memory(owner, workout),
      )
    jdbc
      .query(
        "SELECT * FROM coach_runs WHERE owner_id=? AND workout_id=? AND automatic AND state='QUEUED' FOR UPDATE",
        { r, _ -> row(r) },
        owner,
        workout,
      )
      .forEach(::supersede)
    jdbc.update(
      "UPDATE coach_sessions SET evaluated_snapshot=snapshot,evaluated_version=semantic_version WHERE owner_id=? AND workout_id=?",
      owner,
      workout,
    )
    if (reason == null) return
    insert(
      owner,
      json.valueToTree(
        mapOf(
          "requestId" to UUID.randomUUID().toString(),
          "workoutId" to workout.toString(),
          "contextVersion" to sessionRow[4],
          "snapshot" to json.readTree(sessionRow[0]),
          "message" to reason,
          "history" to emptyList<Any>(),
          "model" to sessionModel(owner, workout),
          "automatic" to true,
        )
      ),
    )
  }

  companion object {
    private val ACTIVE = setOf("QUEUED", "RUNNING")
  }
}
