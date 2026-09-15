package tech.valerochkagym.service.auth

import java.time.Clock
import org.springframework.stereotype.Service
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.service.model.Identity

/** Locks the authenticated account and current session before a durable account mutation. */
@Service
class IdentitySessionGuard(
  private val users: UserRepository,
  private val sessions: SessionRepository,
  private val clock: Clock,
) {
  fun lock(identity: Identity) {
    users.lock(identity.userId) ?: unauthorized()
    val session = sessions.lock(identity.sessionId) ?: unauthorized()
    val now = clock.instant()
    if (
      session.userId != identity.userId ||
        session.revokedAt != null ||
        !session.accessExpiresAt.isAfter(now) ||
        !session.refreshExpiresAt.isAfter(now)
    )
      unauthorized()
  }
}
