package tech.valerochkagym.service.auth

import java.time.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.repository.auth.*
import tech.valerochkagym.repository.health.HealthAccountCleanup
import tech.valerochkagym.repository.model.UserEntity
import tech.valerochkagym.repository.routineshare.RoutineShareAccountCleanup
import tech.valerochkagym.repository.trainingproposal.TrainingProposalAccountCleanup
import tech.valerochkagym.utils.Crypto

class GoogleRegistrationTest {
  private val users = mock(UserRepository::class.java)
  private val sessions = mock(SessionRepository::class.java)
  private val refresh = mock(RefreshRepository::class.java)
  private val manager =
    mock(PlatformTransactionManager::class.java).also {
      `when`(it.getTransaction(any(TransactionDefinition::class.java)))
        .thenReturn(mock(TransactionStatus::class.java))
    }
  private val auth =
    AuthService(
      users,
      sessions,
      refresh,
      mock(ChallengeRepository::class.java),
      mock(NonceRepository::class.java),
      mock(CredentialRepository::class.java),
      mock(PostgresAuthRepository::class.java),
      TransactionTemplate(manager),
      Crypto("test-pepper-".repeat(4)),
      mock(Mailer::class.java),
      Clock.systemUTC(),
      mock(HealthAccountCleanup::class.java),
      mock(TrainingProposalAccountCleanup::class.java),
      mock(RoutineShareAccountCleanup::class.java),
    )

  @Test
  fun `browser rejects new Google account without saving user or session`() {
    val error =
      assertThrows(ApiException::class.java) {
        auth.google("subject", "test@example.com", "Web", allowRegistration = false)
      }
    assertEquals(403, error.status)
    verify(users, never()).saveAndFlush(any(UserEntity::class.java))
    verifyNoInteractions(sessions, refresh)
  }

  @Test
  fun `browser allows existing Google account to sign in`() {
    val user = UserEntity(email = "test@example.com", googleSubject = "subject")
    `when`(users.findByGoogleSubject("subject")).thenReturn(user)
    val tokens = auth.google("subject", user.email, "Web", allowRegistration = false)
    assertEquals(user.id, tokens.userId)
    assertTrue(tokens.accessToken.isNotBlank())
    verify(users, never()).saveAndFlush(any(UserEntity::class.java))
  }

  @Test
  fun `app can still create Google account by default`() {
    `when`(users.saveAndFlush(any(UserEntity::class.java))).thenAnswer { it.arguments[0] }
    val tokens = auth.google("subject", "test@example.com", "Android")
    assertEquals("test@example.com", tokens.email)
    verify(users).saveAndFlush(any(UserEntity::class.java))
  }
}
