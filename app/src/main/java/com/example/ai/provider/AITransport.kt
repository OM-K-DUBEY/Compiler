package com.example.ai.provider

import com.example.core.common.AppDispatchers
import com.example.core.common.RedactingLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class HttpJsonResult(
  val statusCode: Int,
  val body: String,
  val latencyMs: Long,
)

class AITransport(
  private val baseClient: OkHttpClient,
  private val dispatchers: AppDispatchers,
) {
  private fun clientForTimeout(timeoutSeconds: Int, isStreaming: Boolean): OkHttpClient {
    val clamped = timeoutSeconds.coerceIn(5, 300).toLong()
    return baseClient.newBuilder()
      .connectTimeout(clamped, TimeUnit.SECONDS)
      .writeTimeout(clamped, TimeUnit.SECONDS)
      .readTimeout(if (isStreaming) 0L else clamped, TimeUnit.SECONDS)
      .build()
  }

  suspend fun executeJson(
    request: Request,
    providerLabel: String,
    timeoutSeconds: Int = 30,
    secretsToRedact: Collection<String> = emptyList(),
  ): Pair<HttpJsonResult?, ProviderError?> =
    withContext(dispatchers.io) {
      val start = System.currentTimeMillis()
      val client = clientForTimeout(timeoutSeconds, isStreaming = false)
      val call = client.newCall(request)
      try {
        call.execute().use { response ->
          val latency = System.currentTimeMillis() - start
          val rawBody = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            val err =
              ProviderErrorHandler.fromHttpStatus(
                response.code,
                response.message,
                rawBody,
                providerLabel,
                secretsToRedact,
              )
            RedactingLogger.w("AITransport", err.message)
            return@withContext null to err
          }
          HttpJsonResult(response.code, rawBody, latency) to null
        }
      } catch (e: Exception) {
        val err = ProviderErrorHandler.fromException(e, providerLabel, call.isCanceled(), secretsToRedact)
        RedactingLogger.w("AITransport", err.message)
        null to err
      }
    }

  fun executeSse(
    request: Request,
    providerLabel: String,
    timeoutSeconds: Int = 60,
    secretsToRedact: Collection<String> = emptyList(),
    onCallCreated: ((CancellableRequest) -> Unit)? = null,
    onEventPayload: (eventName: String?, payload: String) -> Unit,
    onCompleted: () -> Unit,
    onError: (ProviderError) -> Unit,
  ): CancellableRequest {
    val client = clientForTimeout(timeoutSeconds, isStreaming = true)
    val call: Call = client.newCall(request)
    val handle = OkHttpCancellable(call)
    onCallCreated?.invoke(handle)
    try {
      call.execute().use { response ->
        if (!response.isSuccessful) {
          val errBody = response.body?.string().orEmpty()
          val err =
            ProviderErrorHandler.fromHttpStatus(
              response.code,
              response.message,
              errBody,
              providerLabel,
              secretsToRedact,
            )
          RedactingLogger.w("AITransport", err.message)
          onError(err)
          return OkHttpCancellable(call)
        }
        val body = response.body
        if (body == null) {
          onError(ProviderError(ErrorCategory.UNSUPPORTED_FORMAT, response.code, "Empty streaming response body from $providerLabel"))
          return OkHttpCancellable(call)
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
          // Some OpenAI-compatible endpoints return single JSON object even if stream=true or error payload
          val single = body.string()
          if (single.isNotBlank()) {
            onEventPayload(null, single)
          }
          onCompleted()
          return OkHttpCancellable(call)
        }

        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
          var currentEvent: String? = null
          val dataBuffer = StringBuilder()
          while (true) {
            val line = reader.readLine() ?: break
            when {
              line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
              line.startsWith("data:") -> {
                val part = line.removePrefix("data:").trim()
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(part)
              }
              line.isBlank() -> {
                if (dataBuffer.isNotEmpty()) {
                  val payload = dataBuffer.toString().trim()
                  dataBuffer.clear()
                  if (payload != "[DONE]" && payload.isNotEmpty()) {
                    onEventPayload(currentEvent, payload)
                  }
                }
                currentEvent = null
              }
            }
          }
          if (dataBuffer.isNotEmpty()) {
            val payload = dataBuffer.toString().trim()
            if (payload != "[DONE]" && payload.isNotEmpty()) {
              onEventPayload(currentEvent, payload)
            }
          }
        }
        onCompleted()
      }
    } catch (e: Exception) {
      val err = ProviderErrorHandler.fromException(e, providerLabel, call.isCanceled(), secretsToRedact)
      RedactingLogger.w("AITransport", err.message)
      onError(err)
    }
    return OkHttpCancellable(call)
  }

  private class OkHttpCancellable(private val call: Call) : CancellableRequest {
    override fun cancel() {
      call.cancel()
    }
  }

  companion object {
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    fun jsonBody(json: String) = json.toRequestBody(JSON_MEDIA_TYPE)
  }
}
