package tech.valerochkagym.service.ai

import org.springframework.stereotype.Component

/** Deterministic test seams around the durable calendar attempt boundary. */
interface CalendarAiExecutionHooks {
  fun afterReserve() = Unit

  fun afterCapture() = Unit

  fun beforeFinalLock() = Unit

  fun beforeProposalInsert() = Unit

  fun beforeRefinementCommit() = Unit
}

@Component class NoopCalendarAiExecutionHooks : CalendarAiExecutionHooks
