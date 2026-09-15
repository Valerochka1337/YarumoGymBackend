package tech.valerochkagym.service.auth

import java.time.Clock
import java.util.Locale
import java.util.UUID
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tech.valerochkagym.controller.advice.ApiException
import tech.valerochkagym.controller.advice.bad
import tech.valerochkagym.controller.advice.unauthorized
import tech.valerochkagym.controller.model.SessionInfo
import tech.valerochkagym.controller.model.Tokens
import tech.valerochkagym.repository.auth.ChallengeRepository
import tech.valerochkagym.repository.auth.CredentialRepository
import tech.valerochkagym.repository.auth.NonceRepository
import tech.valerochkagym.repository.auth.PostgresAuthRepository
import tech.valerochkagym.repository.auth.RefreshRepository
import tech.valerochkagym.repository.auth.SessionRepository
import tech.valerochkagym.repository.auth.UserRepository
import tech.valerochkagym.repository.model.ChallengeEntity
import tech.valerochkagym.repository.model.RefreshEntity
import tech.valerochkagym.repository.model.SessionEntity
import tech.valerochkagym.repository.model.UserEntity
import tech.valerochkagym.repository.trainingproposal.TrainingProposalAccountCleanup
import tech.valerochkagym.service.model.Identity
import tech.valerochkagym.utils.Crypto

@Service
class AuthService(
  private val users: UserRepository,
  private val sessionRows: SessionRepository,
  private val refreshRows: RefreshRepository,
  private val challenges: ChallengeRepository,
  private val nonces: NonceRepository,
  private val credentials: CredentialRepository,
  private val postgres: PostgresAuthRepository,
  private val tx: TransactionTemplate,
  private val crypto: Crypto,
  private val mailer: Mailer,
  private val clock: Clock,
  private val healthCleanup: tech.valerochkagym.repository.health.HealthAccountCleanup,
  private val proposalCleanup: TrainingProposalAccountCleanup,
) {
  private val passwords = Argon2PasswordEncoder(16, 32, 1, 19456, 2)
  private val dummyHash = passwords.encode(crypto.token())

  private fun now() = clock.instant()

  fun email(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).also {
      if (it.length > 254 || !it.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
        bad("Некорректный email")
    }

  private fun password(raw: String) {
    if (raw.length !in 12..128) bad("Пароль должен содержать от 12 до 128 символов")
  }

  fun register(rawEmail: String, password: String) {
    val email = email(rawEmail)
    password(password)
    val hash = passwords.encode(password)
    tx.executeWithoutResult {
      postgres.createUser(UUID.randomUUID(), email, requireNotNull(hash))
      if (users.lockEmail(email)?.emailVerified == false) challenge(email, "verify")
    }
  }

  fun requestCode(rawEmail: String, purpose: String) {
    val email = email(rawEmail)
    tx.executeWithoutResult {
      val user = users.lockEmail(email)
      if (user != null && (purpose != "verify" || !user.emailVerified)) challenge(email, purpose)
    }
  }

  private fun challenge(email: String, purpose: String) {
    val code = crypto.code()
    val row = challenges.lock(email, purpose) ?: ChallengeEntity(email = email, purpose = purpose)
    row.codeHash = crypto.hash("$email:$purpose:$code")
    row.expiresAt = now().plusSeconds(600)
    row.attempts = 0
    challenges.save(row)
    mailer.sendCode(email, purpose, code)
  }

  // The caller commits this transaction before turning false into an HTTP error.
  private fun consume(email: String, purpose: String, code: String): Boolean {
    val row = challenges.lock(email, purpose) ?: return false
    val previous = row.attempts++
    if (
      previous >= 5 ||
        !row.expiresAt.isAfter(now()) ||
        row.codeHash != crypto.hash("$email:$purpose:$code")
    )
      return false
    challenges.delete(row)
    return true
  }

  fun verify(rawEmail: String, code: String) {
    val email = email(rawEmail)
    val ok =
      tx.execute {
        val user = users.lockEmail(email)
        if (user == null || !consume(email, "verify", code)) false
        else {
          user.emailVerified = true
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  fun reset(rawEmail: String, code: String, newPassword: String) {
    val email = email(rawEmail)
    password(newPassword)
    val hash = passwords.encode(newPassword)
    val ok =
      tx.execute {
        val user = users.lockEmail(email)
        if (user == null || !consume(email, "reset", code)) false
        else {
          user.passwordHash = hash
          user.emailVerified = true
          sessionRows.revoke(user.id, null, now())
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  fun login(rawEmail: String, password: String, device: String): Tokens {
    val email = email(rawEmail)
    if (password.length > 128) unauthorized()
    return tx.execute {
      val user = users.lockEmail(email)
      val matched = passwords.matches(password, user?.passwordHash ?: dummyHash)
      if (!matched || user == null) unauthorized()
      if (!user.emailVerified)
        throw ApiException(403, "email_unverified", "Подтвердите email кодом из письма")
      issue(user.id, email, device)
    }!!
  }

  fun loginAdmin(username: String, password: String): Tokens {
    if (username.length !in 1..64 || password.length !in 12..128)
      throw ApiException(401, "invalid_credentials", "Неверный логин или пароль")
    return tx.execute {
      val credential = credentials.findByUsername(username)
      val user = credential?.let { users.lock(it.userId) }
      val matched = passwords.matches(password, credential?.passwordHash ?: dummyHash)
      if (!matched || user == null || !user.isAdmin || !user.emailVerified)
        throw ApiException(401, "invalid_credentials", "Неверный логин или пароль")
      issue(user.id, user.email, "Админка · браузер")
    }!!
  }

  fun google(subject: String, rawEmail: String, device: String, link: Identity? = null): Tokens {
    val email = email(rawEmail)
    return tx.execute {
      postgres.lockIdentityCreation()
      val bySubject = users.findByGoogleSubject(subject)
      if (link != null) {
        if (bySubject != null && bySubject.id != link.userId)
          throw ApiException(409, "identity_in_use", "Google уже связан с другим аккаунтом")
        val user = users.lock(link.userId) ?: unauthorized()
        if (user.googleSubject != null && user.googleSubject != subject)
          throw ApiException(
            409,
            "identity_in_use",
            "К аккаунту уже подключён другой Google-профиль",
          )
        user.googleSubject = subject
        return@execute issue(link.userId, link.email, device)
      }
      if (bySubject != null) return@execute issue(bySubject.id, bySubject.email, device)
      if (users.findByEmail(email) != null)
        throw ApiException(
          409,
          "link_required",
          "Войдите с паролем и подключите Google в настройках аккаунта",
        )
      val user =
        users.saveAndFlush(UserEntity(email = email, emailVerified = true, googleSubject = subject))
      issue(user.id, email, device)
    }!!
  }

  private fun issue(
    user: UUID,
    email: String,
    device: String,
    existing: SessionEntity? = null,
  ): Tokens {
    if (device.length !in 1..100) bad("Некорректное название устройства")
    val access = crypto.token()
    val refresh = crypto.token()
    val session =
      existing
        ?: SessionEntity(
          userId = user,
          deviceName = device,
          refreshExpiresAt = now().plusSeconds(2592000),
        )
    session.accessHash = crypto.hash(access)
    session.accessExpiresAt = now().plusSeconds(900)
    sessionRows.saveAndFlush(session)
    refreshRows.save(RefreshEntity(tokenHash = crypto.hash(refresh), sessionId = session.id))
    return Tokens(user, email, access, refresh)
  }

  fun refresh(token: String): Tokens {
    if (token.length > 100) unauthorized()
    val result =
      tx.execute {
        val hash = crypto.hash(token)
        // Lock the session first for all generations of refresh tokens. Then read the token
        // under its own lock so a concurrent successful rotation is observed.
        val sessionId = refreshRows.sessionId(hash) ?: return@execute null
        val session = sessionRows.lock(sessionId) ?: return@execute null
        val row = refreshRows.lock(hash) ?: return@execute null
        if (session.revokedAt != null || !session.refreshExpiresAt.isAfter(now()))
          return@execute null
        if (row.usedAt != null) {
          session.revokedAt = now()
          return@execute null
        }
        row.usedAt = now()
        val user = users.findById(session.userId).orElse(null) ?: return@execute null
        issue(user.id, user.email, session.deviceName, session)
      }
    return result ?: unauthorized()
  }

  fun authenticate(token: String): Identity? {
    if (token.length !in 40..100) return null
    val session = sessionRows.findByAccessHash(crypto.hash(token)) ?: return null
    if (
      session.revokedAt != null ||
        !session.accessExpiresAt.isAfter(now()) ||
        !session.refreshExpiresAt.isAfter(now())
    )
      return null
    val user = users.findById(session.userId).orElse(null) ?: return null
    return Identity(user.id, session.id, user.email)
  }

  fun sessions(identity: Identity): List<SessionInfo> =
    sessionRows
      .findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfterOrderByCreatedAtDesc(
        identity.userId,
        now(),
      )
      .map { SessionInfo(it.id, it.deviceName, it.createdAt, it.id == identity.sessionId) }

  fun logout(identity: Identity, session: UUID = identity.sessionId) {
    tx.executeWithoutResult { sessionRows.revoke(identity.userId, session, now()) }
  }

  fun logoutAll(identity: Identity) {
    tx.executeWithoutResult { sessionRows.revoke(identity.userId, null, now()) }
  }

  fun delete(identity: Identity, code: String) {
    val ok =
      tx.execute {
        proposalCleanup.preflightAccountDeletion(identity.userId)
        healthCleanup.preflight(identity.userId)
        users.lock(identity.userId)
        healthCleanup.revalidate(identity)
        if (!consume(identity.email, "delete", code)) false
        else {
          challenges.removeEmail(identity.email)
          proposalCleanup.removeRecipientLocked(identity.userId)
          healthCleanup.removeLocked(identity.userId)
          users.remove(identity.userId)
          true
        }
      }
    if (ok != true) throw ApiException(400, "invalid_code", "Код неверен или истёк")
  }

  @Scheduled(fixedDelay = 3600000)
  fun cleanup() {
    tx.executeWithoutResult {
      sessionRows.cleanup(now())
      challenges.cleanup(now())
      nonces.cleanup(now())
      postgres.cleanup(now().minusSeconds(3600))
    }
  }
}
