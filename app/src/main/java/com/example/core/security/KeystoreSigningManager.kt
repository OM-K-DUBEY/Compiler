package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class SigningKeyInfo(
  val alias: String,
  val algorithm: String,
  val sha256Fingerprint: String,
  val createdAtMillis: Long,
)

class KeystoreSigningManager {

  fun ensureSigningKey(alias: String = DEFAULT_ALIAS): SigningKeyInfo {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existingEntry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
    if (existingEntry != null) {
      val pubBytes = existingEntry.certificate.publicKey.encoded
      return SigningKeyInfo(
        alias = alias,
        algorithm = existingEntry.privateKey.algorithm,
        sha256Fingerprint = sha256Hex(pubBytes),
        createdAtMillis = System.currentTimeMillis(),
      )
    }

    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
        .setKeySize(2048)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        .build()
    kpg.initialize(spec)
    val kp = kpg.generateKeyPair()
    return SigningKeyInfo(
      alias = alias,
      algorithm = "RSA-2048",
      sha256Fingerprint = sha256Hex(kp.public.encoded),
      createdAtMillis = System.currentTimeMillis(),
    )
  }

  /**
   * Computes real SHA-256 digests for archive entries, generates META-INF/MANIFEST.MF,
   * and signs the manifest digest using the AndroidKeyStore RSA-2048 private key.
   */
  fun signJarArchive(inputZip: File, outputSignedZip: File, alias: String = DEFAULT_ALIAS): SigningKeyInfo {
    val keyInfo = ensureSigningKey(alias)
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val privateKey =
      (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.privateKey
        ?: throw IllegalStateException("Signing key '$alias' not found in AndroidKeyStore")

    val manifestBuilder = StringBuilder("Manifest-Version: 1.0\r\nCreated-By: DevForge AI Signer\r\n\r\n")
    ZipFile(inputZip).use { zf ->
      ZipOutputStream(outputSignedZip.outputStream().buffered()).use { zos ->
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          if (entry.name.startsWith("META-INF/")) continue
          val bytes = zf.getInputStream(entry).use { it.readBytes() }
          if (!entry.isDirectory) {
            val digestB64 = Base64.encodeToString(sha256Bytes(bytes), Base64.NO_WRAP)
            manifestBuilder
              .append("Name: ").append(entry.name).append("\r\n")
              .append("SHA-256-Digest: ").append(digestB64).append("\r\n\r\n")
          }
          val outEntry = ZipEntry(entry.name)
          zos.putNextEntry(outEntry)
          if (!entry.isDirectory) zos.write(bytes)
          zos.closeEntry()
        }

        val manifestBytes = manifestBuilder.toString().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zos.write(manifestBytes)
        zos.closeEntry()

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(manifestBytes)
        val signatureBytes = sig.sign()

        zos.putNextEntry(ZipEntry("META-INF/DEVFORGE.SIG"))
        zos.write(signatureBytes)
        zos.closeEntry()
      }
    }
    return keyInfo
  }

  fun sha256Bytes(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

  fun sha256Hex(data: ByteArray): String =
    sha256Bytes(data).joinToString(":") { "%02X".format(it) }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val DEFAULT_ALIAS = "devforge_release_signing_key"
  }
}
