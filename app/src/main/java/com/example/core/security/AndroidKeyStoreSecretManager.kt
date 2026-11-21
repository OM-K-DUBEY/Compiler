package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.core.common.RedactingLogger
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
  fun encrypt(plaintext: String): String
  fun decrypt(ciphertext: String): String
}

class AndroidKeyStoreSecretManager(
  private val keyAlias: String = "devforge_ai_master_secret_key",
) : SecretCipher {

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(keyAlias, null) as? SecretKey
    if (existing != null) return existing

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          keyAlias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    generator.init(spec)
    return generator.generateKey()
  }

  override fun encrypt(plaintext: String): String {
    if (plaintext.isEmpty()) return ""
    val key = getOrCreateKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val packed =
      ByteBuffer.allocate(4 + iv.size + encrypted.size)
        .putInt(iv.size)
        .put(iv)
        .put(encrypted)
        .array()
    return Base64.encodeToString(packed, Base64.NO_WRAP)
  }

  override fun decrypt(ciphertext: String): String {
    if (ciphertext.isEmpty()) return ""
    return try {
      val packed = Base64.decode(ciphertext, Base64.NO_WRAP)
      val buffer = ByteBuffer.wrap(packed)
      val ivLen = buffer.int
      if (ivLen !in 12..16 || ivLen > buffer.remaining()) {
        throw SecurityException("Invalid IV length in encrypted payload")
      }
      val iv = ByteArray(ivLen)
      buffer.get(iv)
      val encrypted = ByteArray(buffer.remaining())
      buffer.get(encrypted)

      val key = getOrCreateKey()
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
      RedactingLogger.e("SecretManager", "Failed to decrypt secret payload", e)
      ""
    }
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
  }
}
