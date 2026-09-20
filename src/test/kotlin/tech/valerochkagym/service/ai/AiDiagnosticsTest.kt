package tech.valerochkagym.service.ai

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiDiagnosticsTest {
  private class MutableClock(private var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId) = this

    fun advance(hours: Long) {
      now = now.plusSeconds(hours * 3_600)
    }
  }

  @Test
  fun `retention keeps two hundred latest runs and expires a twenty four hour old snapshot`() {
    val clock = MutableClock(Instant.parse("2026-09-20T00:00:00Z"))
    val diagnostics = AiDiagnostics(clock)

    repeat(201) { diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {} }

    assertEquals(200, diagnostics.snapshot().size)
    clock.advance(25)
    assertTrue(diagnostics.snapshot().isEmpty())
  }

  @Test
  fun `nested stages keep opening order counters and a single terminal run`() {
    val diagnostics =
      AiDiagnostics(Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC))

    diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE, "planner-model") {
      diagnostics.recordRound()
      diagnostics.observe(AiDiagnosticStage.PLANNER_TURN) {
        diagnostics.recordToolCall()
        diagnostics.observe(AiDiagnosticStage.PLANNER_TOOL) {}
      }
    }

    val run = diagnostics.snapshot().single()
    assertEquals(AiDiagnosticOutcome.SUCCESS, run.outcome)
    assertEquals(AiDiagnosticFailureCategory.NONE, run.failureCategory)
    assertEquals("planner-model", run.model)
    assertEquals(1, run.rounds)
    assertEquals(1, run.toolCalls)
    assertEquals(
      listOf(
        AiDiagnosticStage.CALENDAR_CREATE,
        AiDiagnosticStage.PLANNER_TURN,
        AiDiagnosticStage.PLANNER_TOOL,
      ),
      run.stages.map { it.stage },
    )
    assertTrue(run.stages.all { it.outcome == AiDiagnosticOutcome.SUCCESS })
  }

  @Test
  fun `scopes restore thread local state and concurrent observations do not share a run`() {
    val diagnostics = AiDiagnostics()
    diagnostics.observe(AiDiagnosticStage.CALENDAR_CREATE) {
      diagnostics.observe(AiDiagnosticStage.PLANNER_TURN) {}
    }
    diagnostics.observe(AiDiagnosticStage.PROVIDER_HTTP) {}
    val sequential = diagnostics.snapshot()
    assertEquals(2, sequential.size)
    assertNotEquals(sequential[0].id, sequential[1].id)
    assertEquals(listOf(AiDiagnosticStage.PROVIDER_HTTP), sequential[1].stages.map { it.stage })

    val pool = Executors.newFixedThreadPool(2)
    try {
      val ids =
        listOf(AiDiagnosticStage.CALENDAR_JOB, AiDiagnosticStage.CALENDAR_REFINE)
          .map { stage ->
            pool.submit<String> {
              diagnostics.observe(stage) {}
              diagnostics.snapshot().last { it.stages.first().stage == stage }.id
            }
          }
          .map { it.get() }
          .toSet()
      assertEquals(2, ids.size)
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `allowlisted metadata is retained while unknown values and observer errors cannot leak`() {
    val diagnostics = AiDiagnostics()
    diagnostics.observe(AiDiagnosticStage.PROVIDER_HTTP, "bad\nmodel") {
      diagnostics.recordHttp(
        400,
        "invalid_function_parameters",
        "invalid_request_error",
        "tools[0].function",
      )
    }
    val retained = diagnostics.snapshot().single()
    assertEquals("invalid_function_parameters", retained.upstreamCode)
    assertEquals("invalid_request_error", retained.upstreamType)
    assertEquals("tools", retained.upstreamParam)
    assertNull(retained.model)

    assertThrows<IOException> {
      diagnostics.observe(AiDiagnosticStage.PROVIDER_HTTP) {
        diagnostics.recordHttp(503, "secret-upstream-code", "secret-type", "secret-param")
        throw IOException("secret provider response")
      }
    }
    val failed = diagnostics.snapshot().last()
    assertEquals(AiDiagnosticFailureCategory.UPSTREAM_UNAVAILABLE, failed.failureCategory)
    assertNull(failed.upstreamCode)
    assertNull(failed.upstreamType)
    assertNull(failed.upstreamParam)
    assertFalse(failed.toString().contains("secret"))
  }
}
