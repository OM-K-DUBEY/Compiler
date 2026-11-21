package com.example.ai.provider

import okhttp3.Request

internal object ProviderAuthHelper {
  fun applyAuthAndHeaders(
    builder: Request.Builder,
    config: AIProviderConfig,
    plainApiKey: String,
    extraHeaders: Map<String, String> = emptyMap(),
  ) {
    val cleanKey = plainApiKey.trim()
    when (config.authMethod) {
      AuthMethod.BEARER ->
        if (cleanKey.isNotEmpty()) builder.header("Authorization", "Bearer $cleanKey")
      AuthMethod.X_API_KEY ->
        if (cleanKey.isNotEmpty()) builder.header("x-api-key", cleanKey)
      AuthMethod.X_GOOG_API_KEY ->
        if (cleanKey.isNotEmpty()) builder.header("x-goog-api-key", cleanKey)
      AuthMethod.CUSTOM_HEADER -> {
        val headerName = sanitizeHeaderName(config.customAuthHeaderName)
        if (headerName.isNotEmpty() && cleanKey.isNotEmpty()) {
          builder.header(headerName, cleanKey)
        }
      }
      AuthMethod.NONE -> Unit
    }

    (config.customHeaders + extraHeaders).forEach { (rawName, rawVal) ->
      val name = sanitizeHeaderName(rawName)
      val value = rawVal.replace("\r", "").replace("\n", "").trim()
      if (name.isNotEmpty() && value.isNotEmpty()) {
        builder.header(name, value)
      }
    }
  }

  private fun sanitizeHeaderName(raw: String): String =
    raw.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}
