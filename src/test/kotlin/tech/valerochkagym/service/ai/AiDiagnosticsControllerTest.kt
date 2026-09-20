package tech.valerochkagym.service.ai

import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.time.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import tech.valerochkagym.controller.admin.AiDiagnosticsController

class AiDiagnosticsControllerTest {
  @Test
  fun `database failure reports unknown queue counts without leaking connection errors`() {
    val source =
      object : AbstractDataSource() {
        override fun getConnection(): Connection =
          throw SQLTransientConnectionException("secret-db-host-password")

        override fun getConnection(username: String, password: String): Connection = getConnection()
      }
    val report =
      AiDiagnosticsController(AiDiagnostics(), JdbcTemplate(source), Clock.systemUTC()).get()
    assertEquals("ERROR", report.database.status)
    assertEquals("ERROR", report.calendarQueue.status)
    assertNull(report.calendarQueue.queued)
    assertNull(report.calendarQueue.running)
    assertNull(report.calendarQueue.failed)
    assertNull(report.calendarQueue.ready)
    assertFalse(report.toString().contains("secret-db-host-password"))
  }
}
