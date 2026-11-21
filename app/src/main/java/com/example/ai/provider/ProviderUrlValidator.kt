package com.example.ai.provider

import java.net.URI

data class UrlValidationResult(
  val isValid: Boolean,
  val normalizedUrl: String,
  val isLocalEndpoint: Boolean = false,
  val errorMessage: String? = null,
  val securityWarning: String? = null,
)

object ProviderUrlValidator {
  private val privateIpv4Regex =
    Regex("""^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3})$""")

  fun validate(rawUrl: String): UrlValidationResult {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
      return UrlValidationResult(false, "", errorMessage = "Endpoint URL cannot be empty.")
    }
    val uri =
      try {
        URI(trimmed)
      } catch (e: Exception) {
        return UrlValidationResult(false, trimmed, errorMessage = "Invalid URL syntax: ${e.message}")
      }

    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme != "https" && scheme != "http") {
      return UrlValidationResult(
        false,
        trimmed,
        errorMessage = "Unsupported URL scheme '$scheme'. Only HTTPS (or local-dev HTTP) is supported.",
      )
    }

    val host = uri.host?.lowercase().orEmpty()
    if (host.isBlank()) {
      return UrlValidationResult(false, trimmed, errorMessage = "URL is missing a valid hostname.")
    }

    val isLocal = isLocalOrTrustedHost(host)
    if (scheme == "http" && !isLocal) {
      return UrlValidationResult(
        false,
        trimmed,
        isLocalEndpoint = false,
        errorMessage = "Cleartext HTTP is blocked for public endpoints. Use HTTPS or a local development host (localhost / 127.0.0.1 / LAN).",
      )
    }

    val warning =
      if (scheme == "http" && isLocal) {
        "Security warning: Cleartext HTTP to local endpoint '$host' transmits credentials unencrypted on the local network."
      } else {
        null
      }

    val normalized = trimmed.trimEnd('/')
    return UrlValidationResult(
      isValid = true,
      normalizedUrl = normalized,
      isLocalEndpoint = isLocal,
      securityWarning = warning,
    )
  }

  fun isLocalOrTrustedHost(host: String): Boolean {
    val h = host.lowercase().removeSurrounding("[", "]")
    return h == "localhost" ||
      h == "::1" ||
      h == "10.0.2.2" ||
      h.endsWith(".local") ||
      privateIpv4Regex.matches(h)
  }

  /**
   * Appends [suffix] only if [baseUrl] does not already end with one of [alreadyFullEndings].
   * Prevents broken URLs like https://api.example.com/v1/chat/completions/chat/completions.
   */
  fun resolveEndpoint(
    baseUrl: String,
    suffix: String,
    alreadyFullEndings: List<String> = emptyList(),
  ): String {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val lower = cleanBase.lowercase()
    for (ending in alreadyFullEndings) {
      if (lower.endsWith(ending.lowercase())) {
        return cleanBase
      }
    }
    val cleanSuffix = suffix.trimStart('/')
    return if (cleanSuffix.isEmpty()) cleanBase else "$cleanBase/$cleanSuffix"
  }
}
