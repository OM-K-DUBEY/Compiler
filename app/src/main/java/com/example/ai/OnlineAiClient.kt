package com.example.ai

import com.example.core.common.AppDispatchers
import com.example.core.common.RedactingLogger
import com.example.core.preferences.OnlineProviderType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ChatTurn(
  val role: String,
  val content: String,
)

class OnlineAiClient(
  private val okHttpClient: OkHttpClient,
  private val dispatchers: AppDispatchers,
) {
  private val activeCall = AtomicReference<Call?>(null)

  fun cancelActiveRequest() {
    activeCall.getAndSet(null)?.cancel()
  }

  fun streamChat(
    provider: OnlineProviderType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    history: List<ChatTurn>,
  ): Flow<AiStreamEvent> =
    callbackFlow {
      if (apiKey.isBlank()) {
        trySend(AiStreamEvent.Error("API key is not configured for ${provider.displayName}. Open Settings to add your key."))
        close()
        return@callbackFlow
      }

      val cleanBase = baseUrl.trimEnd('/')
      val request =
        try {
          when (provider) {
            OnlineProviderType.GEMINI -> buildGeminiRequest(cleanBase, modelId, apiKey, systemPrompt, history)
            OnlineProviderType.OPENAI, OnlineProviderType.CUSTOM_OPENAI ->
              buildOpenAiRequest(cleanBase, modelId, apiKey, systemPrompt, history)
            OnlineProviderType.ANTHROPIC -> buildAnthropicRequest(cleanBase, modelId, apiKey, systemPrompt, history)
          }
        } catch (e: Exception) {
          trySend(AiStreamEvent.Error("Failed to build request: ${e.message}"))
          close()
          return@callbackFlow
        }

      val call = okHttpClient.newCall(request)
      activeCall.set(call)

      try {
        call.execute().use { response ->
          if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(500).orEmpty()
            val msg = "HTTP ${response.code} from ${provider.displayName}: ${errBody.ifBlank { response.message }}"
            RedactingLogger.w("OnlineAI", msg)
            trySend(AiStreamEvent.Error(RedactingLogger.redact(msg)))
            close()
            return@callbackFlow
          }

          val body = response.body
          if (body == null) {
            trySend(AiStreamEvent.Error("Empty response body from ${provider.displayName}"))
            close()
            return@callbackFlow
          }

          BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
            while (true) {
              val line = reader.readLine() ?: break
              if (!line.startsWith("data:")) continue
              val payload = line.removePrefix("data:").trim()
              if (payload.isEmpty() || payload == "[DONE]") continue

              parseSsePayload(provider, payload)?.forEach { event ->
                trySend(event)
              }
            }
          }
          trySend(AiStreamEvent.Completed)
        }
      } catch (e: Exception) {
        if (call.isCanceled()) {
          trySend(AiStreamEvent.Error("Request cancelled"))
        } else {
          RedactingLogger.e("OnlineAI", "Streaming failed", e)
          trySend(AiStreamEvent.Error("Network error: ${e.message ?: "Unknown failure"}"))
        }
      } finally {
        activeCall.compareAndSet(call, null)
        close()
      }

      awaitClose { call.cancel() }
    }.flowOn(dispatchers.io)

  private fun buildGeminiRequest(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    history: List<ChatTurn>,
  ): Request {
    val contents = JSONArray()
    for (turn in history) {
      val geminiRole = if (turn.role == "assistant") "model" else "user"
      contents.put(
        JSONObject().apply {
          put("role", geminiRole)
          put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
        }
      )
    }
    val payload =
      JSONObject().apply {
        if (systemPrompt.isNotBlank()) {
          put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
          )
        }
        put("contents", contents)
      }
    val url = "$baseUrl/models/$modelId:streamGenerateContent?alt=sse&key=$apiKey"
    return Request.Builder()
      .url(url)
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
  }

  private fun buildOpenAiRequest(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    history: List<ChatTurn>,
  ): Request {
    val messages = JSONArray()
    if (systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
    }
    for (turn in history) {
      messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
    }
    val payload =
      JSONObject().apply {
        put("model", modelId)
        put("messages", messages)
        put("stream", true)
        put("stream_options", JSONObject().put("include_usage", true))
      }
    return Request.Builder()
      .url("$baseUrl/chat/completions")
      .header("Authorization", "Bearer $apiKey")
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
  }

  private fun buildAnthropicRequest(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    history: List<ChatTurn>,
  ): Request {
    val messages = JSONArray()
    for (turn in history) {
      val role = if (turn.role == "assistant") "assistant" else "user"
      messages.put(JSONObject().put("role", role).put("content", turn.content))
    }
    val payload =
      JSONObject().apply {
        put("model", modelId)
        put("max_tokens", 4096)
        if (systemPrompt.isNotBlank()) put("system", systemPrompt)
        put("messages", messages)
        put("stream", true)
      }
    return Request.Builder()
      .url("$baseUrl/messages")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
  }

  private fun parseSsePayload(provider: OnlineProviderType, payload: String): List<AiStreamEvent>? {
    return runCatching {
      val json = JSONObject(payload)
      val events = mutableListOf<AiStreamEvent>()
      when (provider) {
        OnlineProviderType.GEMINI -> {
          val candidates = json.optJSONArray("candidates")
          if (candidates != null && candidates.length() > 0) {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
              for (i in 0 until parts.length()) {
                val text = parts.getJSONObject(i).optString("text", "")
                if (text.isNotEmpty()) events += AiStreamEvent.Token(text)
              }
            }
          }
          json.optJSONObject("usageMetadata")?.let { usage ->
            events +=
              AiStreamEvent.Usage(
                AiTokenUsage(
                  promptTokens = usage.optInt("promptTokenCount", 0),
                  completionTokens = usage.optInt("candidatesTokenCount", 0),
                )
              )
          }
        }
        OnlineProviderType.OPENAI, OnlineProviderType.CUSTOM_OPENAI -> {
          val choices = json.optJSONArray("choices")
          if (choices != null && choices.length() > 0) {
            val delta = choices.getJSONObject(0).optJSONObject("delta")
            val text = delta?.optString("content", "").orEmpty()
            if (text.isNotEmpty()) events += AiStreamEvent.Token(text)
          }
          json.optJSONObject("usage")?.let { usage ->
            events +=
              AiStreamEvent.Usage(
                AiTokenUsage(
                  promptTokens = usage.optInt("prompt_tokens", 0),
                  completionTokens = usage.optInt("completion_tokens", 0),
                )
              )
          }
        }
        OnlineProviderType.ANTHROPIC -> {
          val type = json.optString("type")
          if (type == "content_block_delta") {
            val text = json.optJSONObject("delta")?.optString("text", "").orEmpty()
            if (text.isNotEmpty()) events += AiStreamEvent.Token(text)
          } else if (type == "message_delta") {
            json.optJSONObject("usage")?.let { usage ->
              events +=
                AiStreamEvent.Usage(
                  AiTokenUsage(
                    promptTokens = usage.optInt("input_tokens", 0),
                    completionTokens = usage.optInt("output_tokens", 0),
                  )
                )
            }
          }
        }
      }
      events
    }.getOrNull()
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}
