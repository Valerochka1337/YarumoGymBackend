package tech.valerochkagym.service.ai

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefinementReceiptPolicyTest {
  private val now = Instant.parse("2026-09-19T00:00:00Z")
  private val proposalId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val request = "original request".encodeToByteArray()
  private val binding = RefinementReceiptPolicy.Binding(proposalId, 1, "a".repeat(64), request)

  @Test
  fun `expired unfinished receipt keeps the original binding terminal`() {
    val existing =
      RefinementReceiptPolicy.Existing(proposalId, 1, "a".repeat(64), request, null, now)

    assertEquals(
      RefinementReceiptPolicy.Claim.Interrupted,
      RefinementReceiptPolicy.claim(existing, binding, now),
    )
    assertEquals(
      RefinementReceiptPolicy.Claim.Conflict,
      RefinementReceiptPolicy.claim(
        existing,
        binding.copy(
          rawRequest = "rebound request".encodeToByteArray(),
          requestSha256 = "b".repeat(64),
        ),
        now,
      ),
    )
  }
}
