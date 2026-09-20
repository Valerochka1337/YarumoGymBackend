package tech.valerochkagym.service.ai

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.service.auth.IdentitySessionGuard
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Structured decisions use the same account lock and workout stream, without an AI task. */
@Service
class CoachInterventionService(
  private val jdbc: JdbcTemplate,
  private val tx: TransactionTemplate,
  private val guard: IdentitySessionGuard,
  private val behavior: CoachBehaviorStore,
  private val json: ObjectMapper,
  private val clock: Clock,
) {
  private val codec = CoachRunTools(json)

  private fun tree(value: Any?): JsonNode = json.valueToTree(value)

  private fun hash(value: JsonNode): String {
    fun canonical(n: JsonNode): JsonNode =
      when {
        n.isObject ->
          tree(n.properties().sortedBy { it.key }.associate { it.key to canonical(it.value) })
        n.isArray -> tree(n.toList().map(::canonical))
        else -> n
      }
    return MessageDigest.getInstance("SHA-256")
      .digest(json.writeValueAsBytes(canonical(value)))
      .joinToString("") { "%02x".format(it) }
  }

  internal fun dependencies(snapshot: JsonNode): String {
    val copy = snapshot.deepCopy() as ObjectNode
    listOf(
        "revision",
        "elapsed_seconds",
        "observed_at_millis",
        "pulse",
        "phase",
        "paused",
        "online",
        "decisions",
      )
      .forEach { copy.remove(it) }
    if (copy.has("available_time_ends_at_millis")) copy.remove("available_time_minutes")
    (copy["rest"] as? ObjectNode)?.remove("remaining_seconds")
    return hash(copy)
  }

  private fun id(node: JsonNode?): UUID =
    try {
      UUID.fromString(node?.asString())
    } catch (_: Exception) {
      bad("Некорректный идентификатор")
    }

  private fun conflict(): Nothing =
    throw ApiException(
      409,
      "coach_intervention_conflict",
      "Решение уже изменилось; обновите состояние",
    )

  private fun missing(): Nothing = throw ApiException(404, "not_found", "Решение не найдено")

  private fun emit(owner: UUID, workout: UUID, type: String, result: JsonNode) {
    val sequence =
      jdbc.queryForObject(
        "INSERT INTO coach_workout_event_cursors(owner_id,workout_id,sequence) VALUES (?,?,1) ON CONFLICT(owner_id,workout_id) DO UPDATE SET sequence=coach_workout_event_cursors.sequence+1 RETURNING sequence",
        Long::class.java,
        owner,
        workout,
      )!!
    val payload =
      tree(
        mapOf(
          "type" to type,
          "source" to "INTERVENTION",
          "schemaVersion" to 1,
          "sequence" to sequence,
          "result" to result,
        )
      )
    jdbc.update(
      "INSERT INTO coach_workout_events(owner_id,workout_id,sequence,request_id,payload) VALUES (?,?,?,NULL,?::jsonb)",
      owner,
      workout,
      sequence,
      json.writeValueAsString(payload),
    )
  }

  private fun current(owner: UUID, workout: UUID): Pair<JsonNode, String> =
    jdbc
      .query(
        "SELECT snapshot::text,context_version FROM coach_sessions WHERE owner_id=? AND workout_id=? FOR UPDATE",
        { r, _ -> json.readTree(r.getString(1)) to r.getString(2) },
        owner,
        workout,
      )
      .firstOrNull() ?: missing()

  private fun assessment(owner: UUID, snapshot: JsonNode) =
    codec.assessment(owner, snapshot, json.createObjectNode())

  private fun publish(
    owner: UUID,
    workout: UUID,
    snapshot: JsonNode,
    context: String,
    decision: JsonNode,
    dependencySnapshot: JsonNode = snapshot,
  ): JsonNode {
    val key = decision["intervention_key"].asString()
    val prior =
      jdbc
        .query(
          "SELECT payload::text FROM coach_intervention_proposals WHERE owner_id=? AND workout_id=? AND intervention_key=?",
          { r, _ -> json.readTree(r.getString(1)) },
          owner,
          workout,
          key,
        )
        .firstOrNull()
    if (prior != null) return prior
    val operations =
      try {
        codec.operations(
          owner,
          snapshot,
          tree(
            mapOf("base_revision" to snapshot["revision"], "operations" to decision["operations"])
          ),
        ) {
          false
        }
      } catch (_: IllegalArgumentException) {
        return tree(
          mapOf(
            "kind" to "no_change",
            "text" to "План сохранён: пакет изменений не прошёл проверку.",
            "reasonCode" to "invalid_candidate",
          )
        )
      }
    if (operations.isEmpty()) return tree(mapOf("kind" to "no_change", "text" to "План сохранён."))
    val text =
      listOf("observation", "reason", "expected_effect")
        .mapNotNull { decision[it]?.asString()?.takeIf(String::isNotBlank) }
        .joinToString(" ")
    val proposalId = UUID.randomUUID()
    val expires = clock.millis() + 300_000
    val payload =
      tree(
        mapOf(
          "kind" to "proposal",
          "text" to text,
          "decision" to decision,
          "proposal" to
            mapOf(
              "proposalId" to proposalId,
              "version" to 1,
              "baseRevision" to snapshot["revision"],
              "contextVersion" to context,
              "expiresAtMillis" to expires,
              "operations" to operations,
              "reason" to text,
            ),
        )
      )
    jdbc.update(
      "INSERT INTO coach_intervention_proposals(owner_id,workout_id,proposal_id,intervention_key,status,base_revision,dependencies,expires_at,payload) VALUES (?,?,?,?,'PRESENTED',?,?,?,?::jsonb)",
      owner,
      workout,
      proposalId,
      key,
      snapshot["revision"].asLong(),
      dependencies(dependencySnapshot),
      expires,
      json.writeValueAsString(payload),
    )
    emit(owner, workout, "intervention", payload)
    return payload
  }

  /** Called after the existing initiative/memory gate, under the session transaction. */
  fun evaluate(owner: UUID, workout: UUID, snapshot: JsonNode, context: String): Boolean {
    if (!behavior.enforce(owner, workout)) return false
    val decision = assessment(owner, snapshot)
    when (decision["reason_code"].asString()) {
      "confirmed_harder_adjustment",
      "time_capacity" -> {
        publish(owner, workout, snapshot, context, decision)
        return true
      }
      "unexplained_drop",
      "interrupted_set" -> {
        val latest =
          snapshot["exercises"]
            .flatMap { it["sets"].toList() }
            .filter { it["completed"]?.asBoolean() == true }
            .maxByOrNull { it["completed_at"]?.asLong() ?: 0 } ?: return false
        val topicKey = hash(tree(mapOf("set" to latest, "topic" to "cause")))
        if (
          jdbc.queryForObject(
            "SELECT count(*) FROM coach_questions WHERE owner_id=? AND workout_id=? AND topic_key=?",
            Int::class.java,
            owner,
            workout,
            topicKey,
          )!! > 0
        )
          return true
        val questionId = UUID.randomUUID()
        val expires = clock.millis() + 300_000
        val harder = withAnswer(snapshot, latest["set_id"].asString(), "HARDER_THAN_EXPECTED")
        val payload =
          tree(
            mapOf(
              "kind" to "question",
              "text" to "Почему изменился результат подхода?",
              "decision" to decision,
              "question" to
                mapOf(
                  "questionId" to questionId,
                  "version" to 1,
                  "setId" to latest["set_id"],
                  "expiresAtMillis" to expires,
                  "options" to
                    listOf(
                      mapOf("id" to "PLANNED_EFFORT", "text" to "Остановился специально"),
                      mapOf("id" to "HARDER_THAN_EXPECTED", "text" to "Было тяжелее"),
                      mapOf("id" to "INTERRUPTED", "text" to "Прервали"),
                    ),
                  "branches" to
                    mapOf(
                      "HARDER_THAN_EXPECTED" to assessment(owner, harder),
                      "PLANNED_EFFORT" to mapOf("kind" to "KEEP"),
                      "INTERRUPTED" to mapOf("kind" to "KEEP"),
                    ),
                ),
            )
          )
        jdbc.update(
          "INSERT INTO coach_questions(owner_id,workout_id,question_id,topic_key,status,set_id,base_revision,dependencies,expires_at,payload) VALUES (?,?,?,?,'OPEN',?,?,?,?,?::jsonb)",
          owner,
          workout,
          questionId,
          topicKey,
          id(latest["set_id"]),
          snapshot["revision"].asLong(),
          dependencies(snapshot),
          expires,
          json.writeValueAsString(payload),
        )
        emit(owner, workout, "intervention", payload)
        return true
      }
      else -> return false
    }
  }

  private fun withAnswer(snapshot: JsonNode, setId: String, answer: String): JsonNode {
    val copy = snapshot.deepCopy() as ObjectNode
    val set =
      copy["exercises"].flatMap { it["sets"].toList() }.single { it["set_id"].asString() == setId }
        as ObjectNode
    val feelings =
      set["reported_feelings"]
        ?.toList()
        .orEmpty()
        .map { it.asString() }
        .filterNot { it in setOf("PLANNED_EFFORT", "HARDER_THAN_EXPECTED", "INTERRUPTED") }
    set.set("reported_feelings", tree(feelings + answer))
    return copy
  }

  private fun replay(owner: UUID, eventId: UUID, body: JsonNode): JsonNode? =
    jdbc
      .query(
        "SELECT request_digest,response::text FROM coach_intervention_receipts WHERE owner_id=? AND event_id=?",
        { r, _ -> if (r.getString(1) != hash(body)) conflict() else json.readTree(r.getString(2)) },
        owner,
        eventId,
      )
      .firstOrNull()

  private fun remember(owner: UUID, eventId: UUID, body: JsonNode, response: JsonNode): JsonNode {
    jdbc.update(
      "INSERT INTO coach_intervention_receipts(owner_id,event_id,request_digest,response) VALUES (?,?,?,?::jsonb)",
      owner,
      eventId,
      hash(body),
      json.writeValueAsString(response),
    )
    return response
  }

  fun answer(identity: Identity, workout: UUID, questionId: UUID, body: JsonNode): JsonNode =
    tx.execute {
      guard.lock(identity)
      val envelope = tree(mapOf("questionId" to questionId, "workoutId" to workout, "body" to body))
      val answerId = id(body["answerId"])
      replay(identity.userId, answerId, envelope)?.let {
        return@execute it
      }
      if (
        body["expectedVersion"]?.isIntegralNumber != true || body["expectedVersion"].asLong() != 1L
      )
        conflict()
      val answer = body["answer"]?.asString()
      if (answer !in setOf("PLANNED_EFFORT", "HARDER_THAN_EXPECTED", "INTERRUPTED"))
        bad("Неизвестный ответ")
      val row =
        jdbc
          .queryForList(
            "SELECT * FROM coach_questions WHERE owner_id=? AND workout_id=? AND question_id=? FOR UPDATE",
            identity.userId,
            workout,
            questionId,
          )
          .firstOrNull() ?: missing()
      if (row["status"] == "ANSWERED") conflict()
      val (rawSnapshot, context) = current(identity.userId, workout)
      val snapshot = behavior.planningSnapshot(identity.userId, workout, rawSnapshot)
      val stale =
        row["status"] == "STALE" ||
          dependencies(snapshot) != row["dependencies"] ||
          snapshot["revision"].asLong() != (row["base_revision"] as Number).toLong() ||
          !behavior.allowsAutomatic(identity.userId, workout)
      val expired =
        row["status"] == "EXPIRED" || clock.millis() >= (row["expires_at"] as Number).toLong()
      if (stale || expired) {
        val status = if (expired) "EXPIRED" else "STALE"
        jdbc.update(
          "UPDATE coach_questions SET status=? WHERE owner_id=? AND question_id=?",
          status,
          identity.userId,
          questionId,
        )
        val result =
          tree(
            mapOf(
              "kind" to "no_change",
              "questionId" to questionId,
              "status" to status,
              "text" to "Вопрос больше не актуален. План сохранён.",
            )
          )
        emit(identity.userId, workout, "intervention_answer", result)
        return@execute remember(identity.userId, answerId, envelope, result)
      }
      val derived = withAnswer(snapshot, row["set_id"].toString(), answer!!)
      val decision = assessment(identity.userId, derived)
      val result =
        if (answer == "HARDER_THAN_EXPECTED" && decision["kind"].asString() == "ADJUST")
          publish(identity.userId, workout, derived, context, decision, snapshot)
        else
          tree(
            mapOf(
              "kind" to "no_change",
              "text" to "Ответ сохранён. План оставлен без изменений.",
              "decision" to decision,
            )
          )
      jdbc.update(
        "UPDATE coach_questions SET status='ANSWERED',payload=payload || ?::jsonb WHERE owner_id=? AND question_id=?",
        json.writeValueAsString(mapOf("answer" to answer, "answerId" to answerId)),
        identity.userId,
        questionId,
      )
      val response =
        (result.deepCopy() as ObjectNode).apply {
          put("questionId", questionId.toString())
          put("status", "ANSWERED")
        }
      emit(identity.userId, workout, "intervention_answer", response)
      remember(identity.userId, answerId, envelope, response)
    }!!

  fun receipt(identity: Identity, workout: UUID, proposalId: UUID, body: JsonNode): JsonNode =
    tx.execute {
      guard.lock(identity)
      val envelope = tree(mapOf("proposalId" to proposalId, "workoutId" to workout, "body" to body))
      val receiptId = id(body["receiptId"])
      replay(identity.userId, receiptId, envelope)?.let {
        return@execute it
      }
      val status = body["status"]?.asString()
      if (status !in setOf("APPLIED", "REJECTED", "STALE")) bad("Некорректный статус")
      if (body["version"]?.isIntegralNumber != true || body["version"].asLong() != 1L) conflict()
      val row =
        jdbc
          .queryForList(
            "SELECT * FROM coach_intervention_proposals WHERE owner_id=? AND workout_id=? AND proposal_id=? FOR UPDATE",
            identity.userId,
            workout,
            proposalId,
          )
          .firstOrNull() ?: missing()
      val previousReceipt =
        jdbc
          .query(
            "SELECT response::text FROM coach_intervention_receipts WHERE owner_id=? AND response->>'proposalId'=? LIMIT 1",
            { r, _ -> json.readTree(r.getString(1)) },
            identity.userId,
            proposalId.toString(),
          )
          .firstOrNull()
      if (
        previousReceipt != null &&
          (previousReceipt["status"].asString() != status ||
            (status == "APPLIED" && previousReceipt["resultRevision"] != body["resultRevision"]))
      )
        conflict()
      // A late APPLIED receipt acknowledges an already committed local transaction, even after
      // expiry.
      if (row["status"] !in setOf("PRESENTED", "EXPIRED", "STALE", status)) conflict()
      if (
        status == "APPLIED" &&
          (body["resultRevision"]?.isIntegralNumber != true ||
            body["resultRevision"].asLong() <= (row["base_revision"] as Number).toLong())
      )
        bad("Необходима ревизия локального применения")
      val reason = body["reason"]?.takeUnless { it.isNull }?.asString()
      if (reason != null && reason.length > 2000) bad("Некорректная причина")
      jdbc.update(
        "UPDATE coach_intervention_proposals SET status=? WHERE owner_id=? AND proposal_id=?",
        status,
        identity.userId,
        proposalId,
      )
      val payload = json.readTree(row["payload"].toString())
      val snapshot = current(identity.userId, workout).first
      val setIds = payload["proposal"]["operations"].mapNotNull { it["set_id"]?.asString() }.toSet()
      val sections =
        snapshot["exercises"].filter { e -> e["sets"].any { it["set_id"].asString() in setIds } }
      val memory =
        tree(
          mapOf(
            "proposalId" to proposalId,
            "status" to status,
            "reason" to reason,
            "summary" to payload["text"],
            "sectionIds" to sections.map { it["section_id"] },
            "exerciseIds" to sections.map { it["exercise_id"] },
          )
        )
      jdbc.update(
        "INSERT INTO coach_decisions(owner_id,workout_id,proposal_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
        identity.userId,
        workout,
        proposalId,
        json.writeValueAsString(memory),
      )
      val result =
        tree(
          mapOf(
            "proposalId" to proposalId,
            "status" to status,
            "resultRevision" to body["resultRevision"],
          )
        )
      emit(identity.userId, workout, "intervention_receipt", result)
      remember(identity.userId, receiptId, envelope, result)
    }!!

  fun refresh(owner: UUID, workout: UUID, snapshot: JsonNode) {
    val dep = dependencies(snapshot)
    for ((table, open) in
      listOf("coach_questions" to "OPEN", "coach_intervention_proposals" to "PRESENTED")) {
      val idColumn = if (table == "coach_questions") "question_id" else "proposal_id"
      val updated =
        jdbc.query(
          "UPDATE $table SET status=CASE WHEN expires_at<=? THEN 'EXPIRED' ELSE 'STALE' END WHERE owner_id=? AND workout_id=? AND status=? AND (expires_at<=? OR dependencies<>? OR base_revision<>?) RETURNING $idColumn,status",
          { r, _ -> r.getObject(1).toString() to r.getString(2) },
          clock.millis(),
          owner,
          workout,
          open,
          clock.millis(),
          dep,
          snapshot["revision"].asLong(),
        )
      updated.forEach { (id, status) ->
        emit(
          owner,
          workout,
          if (table == "coach_questions") "intervention_answer" else "intervention_receipt",
          tree(
            mapOf(
              if (table == "coach_questions") "questionId" to id else "proposalId" to id,
              "status" to status,
            )
          ),
        )
      }
    }
  }

  fun answeredFacts(owner: UUID, workout: UUID): JsonNode =
    tree(
      jdbc.query(
        "SELECT set_id,payload->>'answer',question_id FROM coach_questions WHERE owner_id=? AND workout_id=? AND status='ANSWERED' ORDER BY expires_at DESC LIMIT 30",
        { r, _ ->
          mapOf(
            "setId" to r.getObject(1).toString(),
            "reportedFeeling" to r.getString(2),
            "questionId" to r.getObject(3).toString(),
            "source" to "USER_ANSWER",
          )
        },
        owner,
        workout,
      )
    )

  fun projection(owner: UUID, workout: UUID): JsonNode {
    fun rows(table: String) =
      jdbc.query(
        "SELECT payload::text,status FROM $table WHERE owner_id=? AND workout_id=? ORDER BY expires_at DESC LIMIT 50",
        { r, _ -> (json.readTree(r.getString(1)) as ObjectNode).put("status", r.getString(2)) },
        owner,
        workout,
      )
    return tree(
      mapOf(
        "questions" to rows("coach_questions"),
        "proposals" to rows("coach_intervention_proposals"),
      )
    )
  }

  fun pending(owner: UUID, workout: UUID): Boolean =
    jdbc.queryForObject(
      "SELECT (SELECT count(*) FROM coach_questions WHERE owner_id=? AND workout_id=? AND status='OPEN' AND expires_at>?) + (SELECT count(*) FROM coach_intervention_proposals WHERE owner_id=? AND workout_id=? AND status='PRESENTED' AND expires_at>?)",
      Int::class.java,
      owner,
      workout,
      clock.millis(),
      owner,
      workout,
      clock.millis(),
    )!! > 0
}
