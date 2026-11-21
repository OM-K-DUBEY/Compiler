package com.example.core.security

import java.io.File

data class ZipPolicyLimits(
  val maxEntries: Int = 5000,
  val maxTotalUncompressedBytes: Long = 128L * 1024L * 1024L,
  val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
  val maxCompressionRatio: Double = 100.0,
)

object ZipSecurityValidator {

  fun validateEntryName(rawName: String): String {
    if (rawName.isEmpty()) {
      throw SecurityException("Archive contains an empty entry name")
    }
    if (rawName.contains('\u0000')) {
      throw SecurityException("Archive entry contains null byte")
    }
    val normalized = rawName.replace('\\', '/')
    if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
      throw SecurityException("Archive entry uses an absolute path: $rawName")
    }
    val parts = normalized.split('/')
    for (part in parts) {
      if (part == "..") {
        throw SecurityException("ZIP path traversal detected in entry: $rawName")
      }
    }
    return normalized.trimEnd('/')
  }

  fun validateDestination(destDir: File, entryName: String): File {
    val safeName = validateEntryName(entryName)
    return PathSandbox.resolveSafe(destDir, safeName)
  }

  fun checkLimits(
    entryCount: Int,
    cumulativeBytes: Long,
    entryBytes: Long,
    compressedSize: Long,
    limits: ZipPolicyLimits = ZipPolicyLimits(),
  ) {
    if (entryCount > limits.maxEntries) {
      throw SecurityException("Archive exceeds maximum allowed entry count (${limits.maxEntries})")
    }
    if (entryBytes > limits.maxSingleFileBytes) {
      throw SecurityException("Archive entry exceeds single file size limit (${limits.maxSingleFileBytes} bytes)")
    }
    if (cumulativeBytes > limits.maxTotalUncompressedBytes) {
      throw SecurityException("Archive exceeds total uncompressed size limit (${limits.maxTotalUncompressedBytes} bytes)")
    }
    if (compressedSize > 0L && entryBytes > 1024L * 1024L) {
      val ratio = entryBytes.toDouble() / compressedSize.toDouble()
      if (ratio > limits.maxCompressionRatio) {
        throw SecurityException("Suspicious compression ratio ($ratio) indicates possible zip bomb")
      }
    }
  }
}
