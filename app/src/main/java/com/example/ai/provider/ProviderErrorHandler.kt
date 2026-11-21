package com.example.ai.provider

import com.example.core.common.RedactingLogger
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.json.JSONException

enum class ErrorCategory {
  BAD_REQUEST,
  AUTH_FAILED,
  PERMISSION_DENIED,
  NOT_FOUND,
  TIMEOUT,
  PAYLOAD_TOO_LARGE,
  RATE_LIMITED,
  SERVER_ERROR,
  DNS_FAILURE,
  TLS_FAILURE,
  INVALID_JSON,
  UNSUPPORTED_FORMAT,
  NETWORK_ERROR,
  CANCELLED,
}

data class ProviderError(
  val category: ErrorCategory,
  val httpStatus: Int? = null,
  val message: String,
  val rawDetailRedacted: String = "",
  val isRetryable: Boolean = false,
)

object ProviderErrorHandler {
  fun fromHttpStatus(
    statusCode: Int,
    statusMessage: String,
    responseBody: String,
    providerLabel: String,
    secretsToRedact: Collection<String> = emptyList(),
  ): ProviderError {
    val cleanBody = RedactingLogger.redact(responseBody.take(600), secretsToRedact).trim()
    val detailSuffix = if (cleanBody.isNotBlank()) " — $cleanBody" else ""
    return when (statusCode) {
      400 ->
        ProviderError(
          ErrorCategory.BAD_REQUEST,
          400,
          "HTTP 400 Bad Request from $providerLabel (check model ID and request fields)$detailSuffix",
          cleanBody,
          isRetryable = false,
        )
      401 ->
        ProviderError(
          ErrorCategory.AUTH_FAILED,
          401,
          "HTTP 401 Authentication Failed for $providerLabel (invalid or expired API key)$detailSuffix",
          cleanBody,
          isRetryable = false,
        )
      403 ->
        ProviderError(
          ErrorCategory.PERMISSION_DENIED,
          403,
          "HTTP 403 Permission Denied from $providerLabel (key lacks access to this model or endpoint)$detailSuffix",
          cleanBody,
          isRetryable = false,
        )
      404 ->
        ProviderError(
          ErrorCategory.NOT_FOUND,
          404,
          "HTTP 404 Endpoint or Model Not Found at $providerLabel (verify Base URL path and Model ID)$detailSuffix",
          cleanBody,
          isRetryable = false,
        )
      408 ->
        ProviderError(
          ErrorCategory.TIMEOUT,
          408,
          "HTTP 408 Request Timeout from $providerLabel$detailSuffix",
          cleanBody,
          isRetryable = true,
        )
      413 ->
        ProviderError(
          ErrorCategory.PAYLOAD_TOO_LARGE,
          413,
          "HTTP 413 Payload Too Large for $providerLabel (reduce selected project context)$detailSuffix",
          cleanBody,
          isRetryable = false,
        )
      429 ->
        ProviderError(
          ErrorCategory.RATE_LIMITED,
          429,
          "HTTP 429 Rate Limit Exceeded on $providerLabel$detailSuffix",
          cleanBody,
          isRetryable = true,
        )
      500, 502, 503 ->
        ProviderError(
          ErrorCategory.SERVER_ERROR,
          statusCode,
          "HTTP $statusCode Server Error on $providerLabel (${statusMessage.ifBlank { "upstream unavailable" }})$detailSuffix",
          cleanBody,
          isRetryable = true,
        )
      else ->
        ProviderError(
          ErrorCategory.SERVER_ERROR,
          statusCode,
          "HTTP $statusCode from $providerLabel: ${statusMessage.ifBlank { "Request failed" }}$detailSuffix",
          cleanBody,
          isRetryable = statusCode >= 500,
        )
    }
  }

  fun fromException(
    throwable: Throwable,
    providerLabel: String,
    wasCancelled: Boolean = false,
    secretsToRedact: Collection<String> = emptyList(),
  ): ProviderError {
    if (wasCancelled) {
      return ProviderError(ErrorCategory.CANCELLED, message = "Request cancelled by user.", isRetryable = false)
    }
    val safeMsg = RedactingLogger.redact(throwable.message.orEmpty(), secretsToRedact)
    return when (throwable) {
      is UnknownHostException ->
        ProviderError(
          ErrorCategory.DNS_FAILURE,
          message = "DNS resolution failed for $providerLabel ($safeMsg). Check endpoint hostname and internet connection.",
          rawDetailRedacted = safeMsg,
          isRetryable = true,
        )
      is SSLHandshakeException, is SSLException ->
        ProviderError(
          ErrorCategory.TLS_FAILURE,
          message = "TLS/SSL handshake failed for $providerLabel ($safeMsg). Check certificate validity.",
          rawDetailRedacted = safeMsg,
          isRetryable = false,
        )
      is SocketTimeoutException, is InterruptedIOException ->
        ProviderError(
          ErrorCategory.TIMEOUT,
          message = "Connection or read timed out for $providerLabel ($safeMsg).",
          rawDetailRedacted = safeMsg,
          isRetryable = true,
        )
      is JSONException ->
        ProviderError(
          ErrorCategory.INVALID_JSON,
          message = "Invalid JSON received from $providerLabel: $safeMsg",
          rawDetailRedacted = safeMsg,
          isRetryable = false,
        )
      else ->
        ProviderError(
          ErrorCategory.NETWORK_ERROR,
          message = "Network error communicating with $providerLabel: ${safeMsg.ifBlank { throwable.javaClass.simpleName }}",
          rawDetailRedacted = safeMsg,
          isRetryable = true,
        )
    }
  }
}
