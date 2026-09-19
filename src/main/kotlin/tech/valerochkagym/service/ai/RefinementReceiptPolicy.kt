package tech.valerochkagym.service.ai

import java.time.Instant
import java.util.UUID

/**
 * Pure receipt-state contract for a calendar refinement reservation.
 *
 * A receipt-less expired row is terminal, rather than reusable: its first raw request binding
 * remains immutable even when the provider or projection validation fails.
 */
internal object RefinementReceiptPolicy {
  data class Binding(
    val proposalId: UUID,
    val expectedVersion: Int,
    val requestSha256: String,
    val rawRequest: ByteArray,
  )

  data class Existing(
    val proposalId: UUID,
    val expectedVersion: Int,
    val requestSha256: String,
    val rawRequest: ByteArray,
    val receipt: String?,
    val leaseUntil: Instant,
  )

  sealed interface Claim {
    data object Reserve : Claim

    data class Replay(val receipt: String) : Claim

    data object InProgress : Claim

    data object Interrupted : Claim

    data object Conflict : Claim
  }

  fun claim(existing: Existing?, binding: Binding, now: Instant): Claim {
    existing ?: return Claim.Reserve
    if (
      existing.requestSha256 != binding.requestSha256 ||
        !existing.rawRequest.contentEquals(binding.rawRequest) ||
        existing.proposalId != binding.proposalId ||
        existing.expectedVersion != binding.expectedVersion
    )
      return Claim.Conflict
    existing.receipt?.let {
      return Claim.Replay(it)
    }
    return if (existing.leaseUntil.isAfter(now)) Claim.InProgress else Claim.Interrupted
  }
}
