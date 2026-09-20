package tech.valerochkagym.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.valerochkagym.service.ai.coach.*

class CoachBehaviorPolicyTest {
  private val event =
    BehaviorEvent(WorkoutLifecycle.ACTIVE, WorkoutPhase.RESTING, emptySet(), true, false, true)

  @Test
  fun `every phase accepts a new concern regardless of ordinary gates`() {
    for (phase in WorkoutPhase.entries) for (lifecycle in WorkoutLifecycle.entries) {
      val input =
        event.copy(
          phase = phase,
          lifecycle = lifecycle,
          concerns = setOf("s:PAIN"),
          online = false,
          initiativeEnabled = false,
          pendingInteraction = true,
        )
      val decision = CoachBehaviorPolicy.reduce(BehaviorState(), input)
      assertEquals(BehaviorAction.CONCERN, decision.action)
      assertEquals(phase, decision.state.phase)
      assertEquals(BehaviorAction.SILENT, CoachBehaviorPolicy.reduce(decision.state, input).action)
      assertEquals(
        setOf("s:PAIN"),
        CoachBehaviorPolicy.reduce(decision.state, event).state.openConcerns,
      )
    }
  }

  @Test
  fun `unknown and active set defer but ready permits evaluation`() {
    for (phase in listOf(WorkoutPhase.UNKNOWN, WorkoutPhase.IN_SET)) assertEquals(
      BehaviorAction.DEFER,
      CoachBehaviorPolicy.reduce(BehaviorState(), event.copy(phase = phase)).action,
    )
    assertEquals(
      BehaviorAction.EVALUATE,
      CoachBehaviorPolicy.reduce(BehaviorState(), event.copy(phase = WorkoutPhase.READY)).action,
    )
  }

  @Test
  fun `only explicit resolution closes concern without repeating original report`() {
    val open =
      CoachBehaviorPolicy.reduce(BehaviorState(), event.copy(concerns = setOf("s:PAIN"))).state
    val resolved =
      CoachBehaviorPolicy.reduce(
        open,
        event.copy(concerns = setOf("s:PAIN"), resolvedConcerns = setOf("s:PAIN")),
      )
    assertTrue(resolved.state.openConcerns.isEmpty())
    assertEquals(BehaviorAction.EVALUATE, resolved.action)
  }
}
