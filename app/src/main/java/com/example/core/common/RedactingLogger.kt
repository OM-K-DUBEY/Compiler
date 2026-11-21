package com.example.core.common

import android.util.Log

object RedactingLogger {
  private const val TAG = "DevForgeAI"
  private val secretPatterns =
    listOf(
      Regex("""(sk-[A-Za-z0-9\-_]{10,})"""),
      Regex("""(AIza[0-9A-Za-z\-_]{20,})"""),
      Regex("""(Bearer\s+[A-Za-z0-9\-._~+/]+=*)""", RegexOption.IGNORE_CASE),
      Regex("""(x-api-key\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
      Regex("""(password\s*[:=]\s*\S+)""", RegexOption.IGNORE_CASE),
    )

  fun redact(message: String): String {
    var sanitized = message
    for (pattern in secretPatterns) {
      sanitized = sanitized.replace(pattern, "[REDACTED]")
    }
    return sanitized
  }

  fun d(component: String, message: String) {
    Log.d("$TAG/$component", redact(message))
  }

  fun w(component: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.w("$TAG/$component", redact(message), throwable)
    } else {
      Log.w("$TAG/$component", redact(message))
    }
  }

  fun e(component: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.e("$TAG/$component", redact(message), throwable)
    } else {
      Log.e("$TAG/$component", redact(message))
    }
  }
}
