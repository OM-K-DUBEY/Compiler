package com.example.core.common

import android.util.Log

object RedactingLogger {
  private const val TAG = "DevForgeAI"
  private val secretPatterns =
    listOf(
      Regex("""(sk-[A-Za-z0-9\-_]{10,})"""),
      Regex("""(AIza[0-9A-Za-z\-_]{20,})"""),
      Regex("""(ghp_[A-Za-z0-9]{20,})"""),
      Regex("""(github_pat_[A-Za-z0-9_]{20,})"""),
      Regex("""(Bearer\s+[A-Za-z0-9\-._~+/]+=*)""", RegexOption.IGNORE_CASE),
      Regex("""(x-api-key\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
      Regex("""(x-goog-api-key\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
      Regex("""(authorization\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
      Regex("""(password\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
      Regex("""([?&](?:key|api_key|token|access_token)=[^&\s]+)""", RegexOption.IGNORE_CASE),
    )

  fun redact(message: String, extraSecrets: Collection<String> = emptyList()): String {
    var sanitized = message
    for (secret in extraSecrets) {
      val trimmed = secret.trim()
      if (trimmed.length >= 4) {
        sanitized = sanitized.replace(trimmed, "[REDACTED]")
      }
    }
    for (pattern in secretPatterns) {
      sanitized =
        sanitized.replace(pattern) { match ->
          val text = match.value
          if (text.startsWith("?") || text.startsWith("&")) {
            val eqIdx = text.indexOf('=')
            if (eqIdx > 0) text.substring(0, eqIdx + 1) + "[REDACTED]" else "[REDACTED]"
          } else {
            "[REDACTED]"
          }
        }
    }
    return sanitized
  }

  fun redactHeaders(headers: Map<String, String>): Map<String, String> {
    val sensitiveKeys = setOf("authorization", "x-api-key", "x-goog-api-key", "proxy-authorization", "cookie", "set-cookie")
    return headers.mapValues { (k, v) ->
      if (k.lowercase() in sensitiveKeys || k.lowercase().contains("token") || k.lowercase().contains("secret") || k.lowercase().contains("key")) {
        "[REDACTED]"
      } else {
        redact(v)
      }
    }
  }

  fun d(component: String, message: String) {
    Log.d("$TAG/$component", redact(message))
  }

  fun w(component: String, message: String, throwable: Throwable? = null) {
    val safeMsg = redact(message)
    if (throwable != null) {
      Log.w("$TAG/$component", safeMsg, Throwable(redact(throwable.message.orEmpty())))
    } else {
      Log.w("$TAG/$component", safeMsg)
    }
  }

  fun e(component: String, message: String, throwable: Throwable? = null) {
    val safeMsg = redact(message)
    if (throwable != null) {
      Log.e("$TAG/$component", safeMsg, Throwable(redact(throwable.message.orEmpty())))
    } else {
      Log.e("$TAG/$component", safeMsg)
    }
  }
}
