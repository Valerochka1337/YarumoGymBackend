package tech.valerochkagym.service.ai.coach

/**
 * Immutable fact snapshot passed to Coach tools. No authority, secret or reasoning is persisted.
 */
data class WorkoutSnapshot(
  val accountId: String,
  val workoutId: String,
  val revision: Long,
  val exercises: List<SnapshotExercise>,
  val currentSetId: String? = null,
  val previousSetId: String? = null,
  val nextSetId: String? = null,
  val rest: SnapshotRest? = null,
  val elapsedSeconds: Long = 0,
  val availableTimeMinutes: Int? = null,
  val excludedExerciseIds: Set<String> = emptySet(),
  val feelings: Set<String> = emptySet(),
  val pulse: SnapshotPulse? = null,
  val futureRestSeconds: Int? = null,
  val profile: CoachProfile = CoachProfile(),
  val observedAtMillis: Long = System.currentTimeMillis(),
  val autoregulationOptions: AutoregulationOptions = AutoregulationOptions(),
)

data class CoachProfile(
  val trainingGoal: String? = null,
  val experienceLevel: String? = null,
  val constraints: String? = null,
  val equipmentIds: Set<String> = emptySet(),
  val preferredRepMin: Int? = null,
  val preferredRepMax: Int? = null,
)

data class SnapshotExercise(
  val sectionId: String,
  val exerciseId: String,
  val exerciseSyncId: String = "",
  val name: String = "",
  val position: Int = 0,
  val muscleIds: Set<String> = emptySet(),
  val equipmentIds: Set<String> = emptySet(),
  val sets: List<SnapshotSet> = emptyList(),
  val history: List<SnapshotHistory> = emptyList(),
  val type: String? = null,
)

data class SnapshotSet(
  val syncId: String,
  val setIndex: Int,
  val completed: Boolean,
  val weightKg: Double?,
  val reps: Int?,
  val durationSec: Int?,
  val completedAt: Long? = null,
  val speedKmh: Double? = null,
  val inclinePct: Double? = null,
  val setType: String = "UNKNOWN",
  val originalWeightKg: Double? = null,
  val originalReps: Int? = null,
  val originalDurationSec: Int? = null,
  val originalSpeedKmh: Double? = null,
  val originalInclinePct: Double? = null,
  val targetWeightKg: Double? = null,
  val targetReps: Int? = null,
  val targetDurationSec: Int? = null,
  val targetSpeedKmh: Double? = null,
  val targetInclinePct: Double? = null,
  val actualWeightKg: Double? = null,
  val actualReps: Int? = null,
  val actualDurationSec: Int? = null,
  val actualSpeedKmh: Double? = null,
  val actualInclinePct: Double? = null,
  val reportedFeelings: Set<String> = emptySet(),
  val actualRir: Int? = null,
  val actualRirAtLeastFour: Boolean = false,
)

data class SnapshotHistory(
  val completedAt: Long,
  val setIndex: Int,
  val weightKg: Double?,
  val reps: Int?,
  val durationSec: Int?,
  val speedKmh: Double? = null,
  val inclinePct: Double? = null,
  val setType: String = "UNKNOWN",
  val workoutId: String = "",
  val setSyncId: String = "",
  val actualRir: Int? = null,
  val actualRirAtLeastFour: Boolean = false,
  val interrupted: Boolean = false,
)

data class SnapshotPulse(val bpm: Int, val measuredAtMillis: Long)

data class SnapshotRest(
  val startId: String,
  val plannedSeconds: Int?,
  val remainingSeconds: Int?,
  val startedAtMillis: Long,
  val endsAtMillis: Long? = null,
)

sealed interface WorkoutChangeSet {
  data class Packet(val operations: List<Operation>, val autoregulation: AutoregulationProof?)

  sealed interface Operation {
    data class EditSet(val setSyncId: String, val weightKg: Double? = null, val reps: Int? = null) :
      Operation

    data class DeleteSet(val setSyncId: String) : Operation

    data class Rest(val action: RestAction, val startId: String?, val seconds: Int) : Operation
  }
}

enum class RestAction {
  FUTURE_DURATION
}
