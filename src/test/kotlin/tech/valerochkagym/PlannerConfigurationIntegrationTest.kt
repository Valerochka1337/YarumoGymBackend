package tech.valerochkagym

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.service.ai.PlannerConfigurationService

@Testcontainers
@SpringBootTest(properties = ["gym.calendar-jobs.enabled=false"])
class PlannerConfigurationIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val postgres =
      PostgreSQLContainer(
        "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
      )

    @DynamicPropertySource
    @JvmStatic
    fun properties(registry: DynamicPropertyRegistry) {
      registry.add("spring.datasource.url", postgres::getJdbcUrl)
      registry.add("spring.datasource.username", postgres::getUsername)
      registry.add("spring.datasource.password", postgres::getPassword)
      registry.add("gym.token-pepper") { "test-pepper-with-at-least-thirty-two-bytes" }
    }
  }

  @Autowired lateinit var service: PlannerConfigurationService
  @Autowired lateinit var jdbc: JdbcTemplate

  @BeforeEach
  fun clearTestConfiguration() {
    jdbc.update("DELETE FROM planner_configuration WHERE id=1")
  }

  @Test
  fun `migration seeds five valid goal collections once and keeps admin edits`() {
    val initial = service.snapshot()
    assertEquals(5, initial.collections.size)
    assertEquals(32, initial.collections.sumOf { it.patterns.size })
    assertEquals(initial, service.save(initial))
    val edited =
      initial.copy(
        model = "chosen-model",
        collections =
          initial.collections.map {
            it.copy(patterns = it.patterns.map { p -> p.copy(description = "Edited") })
          },
      )
    service.save(edited)
    assertEquals(edited, service.snapshot())
    assertEquals(edited, service.snapshot())
    assertEquals("", initial.model)
    assertEquals(
      1,
      jdbc.queryForObject("SELECT count(*) FROM planner_configuration", Int::class.java),
    )
    assertEquals(
      2,
      jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.columns WHERE column_name='plan_config_json' AND table_name IN ('calendar_ai_attempts','calendar_planner_refinements')",
        Int::class.java,
      ),
    )
  }

  @Test
  fun `invalid windows duplicate goals and dangling sequence cannot replace saved configuration`() {
    val good = service.snapshot()
    val first = good.collections.first()
    val invalid =
      listOf(
        good.copy(historyDays = 29),
        good.copy(detailDays = 8),
        good.copy(weightStepKg = Double.NaN),
        good.copy(maxRounds = 2),
        good.copy(collections = good.collections + first),
        good.copy(collections = good.collections.map { it.copy(sequence = listOf("missing")) }),
        good.copy(collections = good.collections.filterNot { it.goal == "GENERAL_FITNESS" }),
      )
    invalid.forEach { candidate ->
      assertThrows<ApiException> { service.save(candidate) }
      assertEquals(good, service.snapshot())
    }
  }

  @Test
  fun `admin removed patterns are not recreated by a later snapshot`() {
    val original = service.snapshot()
    val edited =
      original.copy(
        collections =
          original.collections.map {
            it.copy(patterns = it.patterns.take(1), sequence = listOf(it.patterns.first().id))
          }
      )
    service.save(edited)
    assertEquals(edited, service.snapshot())
  }
}
