package com.example

import com.example.core.security.SecretCipher
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure JVM AES-256-GCM [SecretCipher] for unit/Robolectric tests where AndroidKeyStore hardware
 * keystore is unavailable on the host JVM. Uses real javax.crypto AES-GCM encryption.
 */
class JvmAesTestSecretCipher : SecretCipher {
  private val key: SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()

  override fun encrypt(plaintext: String): String {
    if (plaintext.isEmpty()) return ""
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val packed =
      ByteBuffer.allocate(4 + iv.size + encrypted.size)
        .putInt(iv.size)
        .put(iv)
        .put(encrypted)
        .array()
    return Base64.getEncoder().encodeToString(packed)
  }

  override fun decrypt(ciphertext: String): String {
    if (ciphertext.isEmpty()) return ""
    return runCatching {
      val packed = Base64.getDecoder().decode(ciphertext)
      val buffer = ByteBuffer.wrap(packed)
      val ivLen = buffer.int
      val iv = ByteArray(ivLen).also { buffer.get(it) }
      val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
      String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrDefault("")
  }
}
