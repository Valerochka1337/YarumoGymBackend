package tech.valerochkagym.service.ai

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.AiContextRevision
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.repository.catalog.StandardRepository
import tech.valerochkagym.repository.data.HeadRepository
import tech.valerochkagym.repository.data.RecordRepository
import tech.valerochkagym.service.model.Identity
import tools.jackson.databind.ObjectMapper

data class AiCapturedContext(
  val revision: AiContextRevision,
  val catalog: String,
  val allowedIds: Set<String>,
  val profile: AiProfileContext? = null,
)

data class CalendarCapturedContext(
  val revision: AiContextRevision,
  val candidates: List<CalendarCandidateSource>,
  val gyms: List<CalendarCandidateSource>,
  val profile: AiProfileContext?,
  val facts: List<CalendarFact>,
  val mass: Map<String, Any>?,
  val notes: List<Map<String, Any>>,
  val sourceRows: List<CalendarSourceRow>,
  val dataQuality: List<String>,
  val workouts: List<CalendarWorkout> = emptyList(),
  val olderFacts: List<CalendarFact> = emptyList(),
  val capturedAtMillis: Long = 0,
  val windowStartMillis: Long = 0,
)

data class CalendarCandidateSource(val id: String, val payload: tools.jackson.databind.JsonNode)

data class CalendarFact(
  val exerciseId: String,
  val factTimeMillis: Long,
  val workoutId: String,
  val sectionId: String,
  val setIndex: Int,
  val actualWeightPresent: Boolean,
  val actualWeightKg: Double?,
  val legacyWeightKg: Double?,
  val results: Map<String, Double?> = emptyMap(),
  val legacyFields: List<String> = emptyList(),
  val setType: String? = null,
  val timeSource: String = "COMPLETED_AT",
)

data class CalendarWorkout(val id: String, val startedAtMillis: Long, val finishedAtMillis: Long)

data class CalendarSourceRow(val kind: String, val key: String, val utf8Bytes: Int)

@Service
class AiContextReader(
  private val tx: TransactionTemplate,
  private val catalog: CatalogStateRepository,
  private val heads: HeadRepository,
  private val records: RecordRepository,
  private val standard: StandardRepository,
  private val users: UserRepository,
  private val sessions: SessionRepository,
  private val json: ObjectMapper,
  private val clock: Clock,
  private val relations: tech.valerochkagym.repository.coachrelation.CoachRelationRepositories,
  private val jdbc: JdbcTemplate,
) {
  fun verifyCalendarAdmission(identity: Identity, revision: Long, catalogRevision: Long) {
    tx.executeWithoutResult {
      relations.guards(identity.userId)
      val common = catalog.readLock()
      if (!heads.existsById(identity.userId)) {
        if (!users.existsById(identity.userId)) unauthorized()
        throw aiError("ai_context_stale")
      }
      val head = heads.readLock(identity.userId)
      relations.session(identity)
      if (head.revision != revision || common.revision != catalogRevision)
        throw aiError("ai_context_stale")
    }
  }

  /** Bounded calendar capture never uses the legacy all-owner reader. */
  fun captureCalendar(
    identity: Identity,
    revision: Long,
    catalogRevision: Long,
    zoneId: String,
    includeNotes: Boolean,
    requestedGymIds: List<String>,
  ): CalendarCapturedContext =
    tx.execute {
      relations.guards(identity.userId)
      val common = catalog.readLock()
      if (!heads.existsById(identity.userId)) {
        if (!users.existsById(identity.userId)) unauthorized()
        throw aiError("ai_context_stale")
      }
      val head = heads.readLock(identity.userId)
      relations.session(identity)
      val now = Instant.now(clock)
      val sourceRows = mutableListOf<CalendarSourceRow>()
      fun account(kind: String, id: java.util.UUID, bytes: Int) {
        if (bytes > 1_048_576) throw aiError("ai_context_too_large")
        sourceRows += CalendarSourceRow(kind, id.toString(), bytes)
        if (sourceRows.sumOf { it.utf8Bytes } > 8_388_608) throw aiError("ai_context_too_large")
      }
      if (head.revision != revision || common.revision != catalogRevision)
        throw aiError("ai_context_stale")
      data class Key(val id: java.util.UUID, val bytes: Int)
      fun keys(sql: String, vararg args: Any): List<Key> =
        jdbc.query(
          sql,
          { rs, _ -> Key(rs.getObject(1, java.util.UUID::class.java), rs.getInt(2)) },
          *args,
        )
      val personalKeys =
        keys(
          "SELECT id,octet_length(payload::text) FROM records WHERE user_id=? AND kind='exercise' AND NOT deleted ORDER BY id LIMIT 1001",
          identity.userId,
        )
      val standardKeys =
        if (common.active)
          keys(
            "SELECT id,octet_length(payload::text) FROM standard_records WHERE kind='exercise' AND NOT archived ORDER BY id LIMIT 1001"
          )
        else emptyList()
      val effectiveKeys = linkedMapOf<java.util.UUID, Key>()
      standardKeys.forEach { effectiveKeys[it.id] = it }
      personalKeys.forEach { effectiveKeys[it.id] = it }
      val personalIds = personalKeys.mapTo(mutableSetOf()) { it.id }
      val effectiveStandardKeys = standardKeys.filterNot { it.id in personalIds }
      effectiveKeys.values.forEach { account("exercise", it.id, it.bytes) }
      if (
        personalKeys.size > 1000 ||
          standardKeys.size > 1000 ||
          effectiveKeys.values.any { it.bytes > 1_048_576 } ||
          effectiveKeys.values.sumOf { it.bytes } > 8_388_608
      )
        throw aiError("ai_context_too_large")
      // Fetch only rows which passed key/count/byte accounting. A personal record overrides a
      // catalog one.
      fun rows(sql: String, vararg args: Any): List<CalendarCandidateSource> =
        jdbc.query(
          sql,
          { rs, _ ->
            CalendarCandidateSource(
              rs.getObject(1, java.util.UUID::class.java).toString(),
              json.readTree(rs.getString(2)),
            )
          },
          *args,
        )
      val standardRows =
        if (effectiveStandardKeys.isEmpty()) emptyList()
        else
          rows(
            "SELECT id,payload::text FROM standard_records WHERE kind='exercise' AND NOT archived AND id IN (${effectiveStandardKeys.joinToString(",") { "?" }}) ORDER BY id",
            *effectiveStandardKeys.map { it.id }.toTypedArray(),
          )
      val personalRows =
        if (personalKeys.isEmpty()) emptyList()
        else
          rows(
            "SELECT id,payload::text FROM records WHERE user_id=? AND kind='exercise' AND NOT deleted ORDER BY id LIMIT 1001",
            identity.userId,
          )
      val candidates =
        linkedMapOf<String, CalendarCandidateSource>()
          .apply {
            standardRows.forEach { put(it.id, it) }
            personalRows.forEach { put(it.id, it) }
          }
          .values
          .toList()
      if (candidates.size > 1000) throw aiError("ai_context_too_large")
      // Resolve requested gyms using the same personal-over-standard precedence as exercises
      // and proposal approval. Built-in gyms are not copied into the owner's records.
      fun gymRows(sql: String, vararg args: Any): List<CalendarCandidateSource> =
        jdbc.query(
          sql,
          { rs, _ ->
            val id = rs.getObject(1, java.util.UUID::class.java)
            account("gym", id, rs.getInt(3))
            CalendarCandidateSource(id.toString(), json.readTree(rs.getString(2)))
          },
          *args,
        )
      val personalGyms =
        if (requestedGymIds.isEmpty()) emptyList()
        else
          gymRows(
            "SELECT id,payload::text,octet_length(payload::text) FROM records WHERE user_id=? AND kind='gym' AND NOT deleted AND id IN (${requestedGymIds.joinToString(",") { "?" }}) ORDER BY id LIMIT 1001",
            *(arrayOf(identity.userId) + requestedGymIds.map(java.util.UUID::fromString)),
          )
      val personalGymIds = personalGyms.mapTo(mutableSetOf()) { it.id }
      val missingGymIds = requestedGymIds.filterNot { it in personalGymIds }
      val standardGyms =
        if (!common.active || missingGymIds.isEmpty()) emptyList()
        else
          gymRows(
            "SELECT id,payload::text,octet_length(payload::text) FROM standard_records WHERE kind='gym' AND NOT archived AND id IN (${missingGymIds.joinToString(",") { "?" }}) ORDER BY id LIMIT 1001",
            *missingGymIds.map(java.util.UUID::fromString).toTypedArray(),
          )
      val gyms = (personalGyms + standardGyms).sortedBy { it.id }
      if (requestedGymIds.isNotEmpty() && gyms.map { it.id }.toSet() != requestedGymIds.toSet())
        throw aiError("ai_context_stale")
      val profileRows =
        jdbc.query(
          "SELECT payload::text FROM records WHERE user_id=? AND kind='profile' AND NOT deleted ORDER BY id LIMIT 2",
          { rs, _ -> rs.getString(1) },
          identity.userId,
        )
      if (profileRows.size > 1) throw aiError("ai_context_too_large")
      profileRows.singleOrNull()?.let {
        account("profile", java.util.UUID(0, 0), it.toByteArray(Charsets.UTF_8).size)
      }
      if (profileRows.any { it.toByteArray(Charsets.UTF_8).size > 1_048_576 })
        throw aiError("ai_context_too_large")
      val capturedAt = now.toEpochMilli()
      val windowStart =
        now
          .atZone(ZoneId.of(zoneId))
          .toLocalDate()
          .minusDays(27)
          .atStartOfDay(ZoneId.of(zoneId))
          .toInstant()
          .toEpochMilli()
      data class WorkoutKey(val id: java.util.UUID, val bytes: Int)
      val workoutKeys =
        jdbc.query(
          "SELECT id,octet_length(payload::text) FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number' AND ((payload->>'finishedAt')::numeric)>=? AND ((payload->>'finishedAt')::numeric)<=? ORDER BY ((payload->>'finishedAt')::numeric) DESC,id ASC LIMIT 65",
          { rs, _ -> WorkoutKey(rs.getObject(1, java.util.UUID::class.java), rs.getInt(2)) },
          identity.userId,
          windowStart,
          capturedAt,
        )
      if (workoutKeys.size > 64) throw aiError("ai_context_too_large")
      // Only three older parents, using the same indexed owner/time predicate. No all-history scan.
      val olderKeys =
        jdbc.query(
          "SELECT id,octet_length(payload::text) FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND jsonb_typeof(payload->'finishedAt')='number' AND ((payload->>'finishedAt')::numeric)<? ORDER BY ((payload->>'finishedAt')::numeric) DESC,id ASC LIMIT 3",
          { rs, _ -> WorkoutKey(rs.getObject(1, java.util.UUID::class.java), rs.getInt(2)) },
          identity.userId,
          windowStart,
        )
      val historyKeys = workoutKeys + olderKeys
      historyKeys.forEach { account("workout", it.id, it.bytes) }
      val workoutRows =
        if (historyKeys.isEmpty()) emptyList()
        else
          jdbc.query(
            "SELECT id,payload::text FROM records WHERE user_id=? AND kind='workout' AND NOT deleted AND id IN (${historyKeys.joinToString(",") { "?" }}) ORDER BY ((payload->>'finishedAt')::numeric) DESC,id ASC",
            { rs, _ ->
              rs.getObject(1, java.util.UUID::class.java) to json.readTree(rs.getString(2))
            },
            *(arrayOf(identity.userId) + historyKeys.map { it.id }),
          )
      val quality = mutableListOf<String>()
      val facts = mutableListOf<CalendarFact>()
      val olderFacts = mutableListOf<CalendarFact>()
      val workouts = mutableListOf<CalendarWorkout>()
      val notes = mutableListOf<Map<String, Any>>()
      workoutRows.forEach { (workoutId, payload) ->
        val started = payload["startedAt"]?.takeUnless { it.isNull }?.asLong()
        val finished = payload["finishedAt"]?.takeUnless { it.isNull }?.asLong()
        val validParent =
          started != null &&
            finished != null &&
            started >= 0 &&
            started <= finished &&
            finished <= capturedAt
        if (!validParent) {
          quality += "INVALID_TEMPORAL_FACT_EXCLUDED"
          return@forEach
        }
        workouts += CalendarWorkout(workoutId.toString(), started, finished)
        if (includeNotes)
          payload["note"]
            ?.takeUnless { it.isNull }
            ?.asString()
            ?.takeIf { it.isNotBlank() && finished >= windowStart }
            ?.let {
              notes +=
                mapOf(
                  "sourceTimeMillis" to finished,
                  "kind" to "WORKOUT_NOTE",
                  "canonicalId" to workoutId.toString(),
                  "text" to it,
                )
            }
        payload["exercises"]?.toList().orEmpty().forEach { section ->
          val exerciseId = section["exerciseId"]?.asString() ?: return@forEach
          val sectionId = section["sectionId"]?.asString() ?: return@forEach
          section["sets"]?.toList().orEmpty().forEachIndexed { index, set ->
            if (set["isCompleted"]?.asBoolean() != true) return@forEachIndexed
            val factTime = set["completedAt"]?.takeUnless { it.isNull }?.asLong() ?: started
            if (factTime < started || factTime > finished || factTime > capturedAt) {
              quality += "INVALID_TEMPORAL_FACT_EXCLUDED"
              return@forEachIndexed
            }
            val destination = if (factTime >= windowStart) facts else olderFacts
            if (destination.size == 8192) throw aiError("ai_context_too_large")
            val legacyFields = mutableListOf<String>()
            val results =
              listOf("weightKg", "reps", "durationSec", "speedKmh", "inclinePct").associateWith {
                key ->
                val actualKey = "actual" + key.replaceFirstChar { it.uppercase() }
                val value =
                  if (set.has(actualKey)) set[actualKey]
                  else {
                    legacyFields += key
                    set[key]
                  }
                value?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() }
              }
            destination +=
              CalendarFact(
                exerciseId,
                factTime,
                workoutId.toString(),
                sectionId,
                index,
                set.has("actualWeightKg"),
                set["actualWeightKg"]?.takeUnless { it.isNull }?.asDouble(),
                set["weightKg"]?.takeUnless { it.isNull }?.asDouble(),
                results,
                legacyFields,
                set["setType"]?.takeUnless { it.isNull }?.asString(),
                if (set["completedAt"]?.isNumber == true) "COMPLETED_AT"
                else "WORKOUT_START_FALLBACK",
              )
            if (includeNotes && factTime >= windowStart)
              set["note"]
                ?.takeUnless { it.isNull }
                ?.asString()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                  notes +=
                    mapOf(
                      "sourceTimeMillis" to factTime,
                      "kind" to "SET_NOTE",
                      "canonicalId" to sectionId,
                      "text" to it,
                    )
                }
          }
        }
      }
      val massRows =
        jdbc.query(
          "SELECT id,payload::text,octet_length(payload::text) FROM records WHERE user_id=? AND kind='measurement' AND NOT deleted AND jsonb_typeof(payload->'measuredAt')='number' AND ((payload->>'measuredAt')::numeric)<=? ORDER BY ((payload->>'measuredAt')::numeric) DESC,id ASC LIMIT 1",
          { rs, _ ->
            Triple(
              rs.getObject(1, java.util.UUID::class.java),
              json.readTree(rs.getString(2)),
              rs.getInt(3),
            )
          },
          identity.userId,
          capturedAt,
        )
      val mass =
        massRows.singleOrNull()?.let { (id, value, bytes) ->
          account("measurement", id, bytes)
          value["weightKg"]
            ?.takeUnless { it.isNull }
            ?.let {
              mapOf("measuredAtMillis" to value["measuredAt"].asLong(), "kg" to it.asDouble())
            }
        }
      if (includeNotes) {
        val candidateIds = candidates.map { java.util.UUID.fromString(it.id) }
        val hints =
          if (candidateIds.isEmpty()) emptyList()
          else
            jdbc.query(
              "SELECT id,payload::text,octet_length(payload::text) FROM records WHERE user_id=? AND kind='exercise_hint' AND NOT deleted AND id IN (${candidateIds.joinToString(",") { "?" }}) ORDER BY id LIMIT 1001",
              { rs, _ ->
                Triple(
                  rs.getObject(1, java.util.UUID::class.java),
                  json.readTree(rs.getString(2)),
                  rs.getInt(3),
                )
              },
              *(arrayOf(identity.userId) + candidateIds),
            )
        if (hints.size > 1000) throw aiError("ai_context_too_large")
        hints.forEach { (id, payload, bytes) ->
          account("exercise_hint", id, bytes)
          val time = payload["updatedAt"]?.asLong() ?: return@forEach
          val text = payload["text"]?.asString()?.takeIf { it.isNotBlank() } ?: return@forEach
          if (time in windowStart..capturedAt)
            notes +=
              mapOf(
                "sourceTimeMillis" to time,
                "kind" to "EXERCISE_HINT",
                "canonicalId" to id.toString(),
                "text" to text,
              )
        }
      }
      val boundedNotes =
        if (includeNotes)
          notes
            .sortedWith(
              compareByDescending<Map<String, Any>> { it["sourceTimeMillis"] as Long }
                .thenBy { it["kind"] as String }
                .thenBy { it["canonicalId"] as String }
            )
            .fold(mutableListOf<Map<String, Any>>() to 0) { acc, note ->
              val textBytes = (note["text"] as String).toByteArray(Charsets.UTF_8).size
              if (acc.first.size < 20 && acc.second + textBytes <= 16384)
                acc.first.apply { add(note) } to acc.second + textBytes
              else acc
            }
            .first
        else emptyList()
      CalendarCapturedContext(
        AiContextRevision(revision, catalogRevision),
        candidates,
        gyms,
        profileRows.singleOrNull()?.let { AiProfileContext.fromSaved(json.readTree(it), clock) },
        facts,
        mass,
        boundedNotes,
        sourceRows,
        quality.distinct(),
        workouts,
        olderFacts,
        capturedAt,
        windowStart,
      )
    }!!

  fun capture(
    identity: Identity,
    revision: Long,
    catalogRevision: Long,
    includeExercises: Boolean,
  ): AiCapturedContext =
    tx.execute {
      relations.guards(identity.userId)
      val common = catalog.readLock()
      // A client with no acknowledged owner head is not sync-ready. Do not create one here.
      if (!heads.existsById(identity.userId)) throw aiError("ai_context_stale")
      val head = heads.readLock(identity.userId)
      val session = sessions.findById(identity.sessionId).orElse(null) ?: unauthorized()
      val now = Instant.now()
      if (
        session.userId != identity.userId ||
          session.revokedAt != null ||
          !session.accessExpiresAt.isAfter(now) ||
          !session.refreshExpiresAt.isAfter(now) ||
          !users.existsById(identity.userId)
      )
        unauthorized()
      if (head.revision != revision || common.revision != catalogRevision)
        throw aiError("ai_context_stale")
      val personal =
        if (includeExercises) records.findByUserIdOrderByKindAscIdAsc(identity.userId)
        else emptyList()
      val profile =
        personal
          .singleOrNull { it.kind == "profile" && !it.deleted }
          ?.let { AiProfileContext.fromSaved(json.readTree(it.payload!!), clock) }
      val rows =
        if (includeExercises)
          personal
            .filter { it.kind == "exercise" && !it.deleted }
            .map { mapOf("id" to it.id.toString(), "payload" to json.readTree(it.payload!!)) } +
            standard
              .findAllByOrderByKindAscIdAsc()
              .filter { common.active && it.kind == "exercise" && !it.archived }
              .map { mapOf("id" to it.id.toString(), "payload" to json.readTree(it.payload)) }
        else emptyList()
      val serialized = json.writeValueAsString(rows)
      if (serialized.toByteArray(Charsets.UTF_8).size > 1024 * 1024)
        throw aiError("ai_context_too_large")
      AiCapturedContext(
        AiContextRevision(revision, catalogRevision),
        serialized,
        rows.map { it["id"] as String }.toSet(),
        profile,
      )
    }!!
}
