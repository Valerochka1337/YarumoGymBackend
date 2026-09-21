package tech.valerochkagym.controller.admin

import java.time.Clock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.valerochkagym.service.ai.AiDiagnosticCalendarQueue
import tech.valerochkagym.service.ai.AiDiagnosticDatabase
import tech.valerochkagym.service.ai.AiDiagnosticRetention
import tech.valerochkagym.service.ai.AiDiagnostics
import tech.valerochkagym.service.ai.AiDiagnosticsResponse

@RestController
@RequestMapping("/admin/api/ai-diagnostics")
class AiDiagnosticsController(
  private val diagnostics: AiDiagnostics,
  private val jdbc: JdbcTemplate,
  private val clock: Clock,
) {
  @GetMapping
  fun get(): AiDiagnosticsResponse {
    val database =
      try {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        AiDiagnosticDatabase("OK")
      } catch (_: Exception) {
        AiDiagnosticDatabase("ERROR")
      }
    val queue =
      try {
        val row =
          jdbc.queryForMap(
            "SELECT " +
              "COUNT(*) FILTER (WHERE state='QUEUED') AS queued, " +
              "COUNT(*) FILTER (WHERE state='RUNNING') AS running, " +
              "COUNT(*) FILTER (WHERE state='FAILED') AS failed, " +
              "COUNT(*) FILTER (WHERE state='READY') AS ready " +
              "FROM calendar_draft_jobs WHERE current_job"
          )
        AiDiagnosticCalendarQueue(
          "OK",
          (row["queued"] as Number).toLong(),
          (row["running"] as Number).toLong(),
          (row["failed"] as Number).toLong(),
          (row["ready"] as Number).toLong(),
        )
      } catch (_: Exception) {
        AiDiagnosticCalendarQueue("ERROR", null, null, null, null)
      }
    return AiDiagnosticsResponse(
      clock.instant(),
      AiDiagnosticRetention(AiDiagnostics.maxRuns, AiDiagnostics.maxAgeHours, true, true),
      database,
      queue,
      diagnostics.snapshot(),
      diagnostics.process,
    )
  }
}
