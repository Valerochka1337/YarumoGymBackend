package tech.valerochkagym.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class Crypto(@Value("\${gym.token-pepper}") pepper: String) {
  private val key =
    pepper.toByteArray().also {
      require(it.size >= 32) { "TOKEN_PEPPER must contain at least 32 bytes" }
    }
  private val random = SecureRandom()

  fun token(): String =
    ByteArray(32).also(random::nextBytes).let {
      Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

  fun code(): String = random.nextInt(100_000_000).toString().padStart(8, '0')

  fun hash(value: String): String =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

  fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  fun routineShareToken(authorId: java.util.UUID, operationId: java.util.UUID): String =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(doFinal("routine-share:$authorId:$operationId".toByteArray()))
    }
}
