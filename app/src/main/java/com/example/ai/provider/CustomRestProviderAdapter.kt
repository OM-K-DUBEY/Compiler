package com.example.ai.provider

import com.example.ai.AiTokenUsage
import com.example.core.preferences.OnlineProviderType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flexible REST adapter for third-party / self-hosted inference gateways whose schema differs from
 * strict OpenAI, Gemini, or Anthropic defaults.
 *
 * Supported keys in [AIProviderConfig.customConfig]:
 * - `chatPath`: relative path appended to baseUrl (e.g. "generate" or "chat") — blank uses baseUrl directly.
 * - `modelsPath`: optional relative path for model discovery (e.g. "models" or "tags").
 * - `responseTextKey`: JSON dot-path or key for text output (e.g. "response", "text", "output", "message.content").
 */
class CustomRestProviderAdapter(
  private val transport: AITransport,
) : AIProvider {
  override val providerType: OnlineProviderType = OnlineProviderType.CUSTOM_ADAPTER

  override val capabilities =
    ProviderCapability(
      supportsStreaming = true,
      supportsModelDiscovery = true,
      supportsToolCalling = false,
      supportsVision = false,
      supportsSystemInstruction = true,
    )

  override suspend fun testConnection(config: AIProviderConfig, plainApiKey: String): ProviderTestResult {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) {
      return ProviderTestResult(false, message = urlCheck.errorMessage ?: "Invalid URL")
    }
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      return ProviderTestResult(false, message = "Credentials required for ${config.displayName} (or set auth method to NONE).")
    }

    val modelsPath = config.customConfig["modelsPath"].orEmpty().trim()
    if (modelsPath.isNotEmpty()) {
      val listRes = listModels(config, plainApiKey)
      listRes.onSuccess { models ->
        if (config.defaultModelId.isNotBlank()) {
          val mv = verifyModel(config, plainApiKey, config.defaultModelId)
          return mv.copy(discoveredModelCount = models.size, securityWarning = urlCheck.securityWarning)
        }
        return ProviderTestResult(
          success = true,
          httpStatus = 200,
          message = "Connected to ${config.displayName}. Discovered ${models.size} model(s).",
          discoveredModelCount = models.size,
          securityWarning = urlCheck.securityWarning,
        )
      }
    }

    val modelToVerify = config.defaultModelId.trim().ifEmpty { "default" }
    return verifyModel(config, plainApiKey, modelToVerify).copy(securityWarning = urlCheck.securityWarning)
  }

  override suspend fun listModels(config: AIProviderConfig, plainApiKey: String): Result<List<AIModel>> {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) return Result.failure(IllegalArgumentException(urlCheck.errorMessage))
    val modelsPath = config.customConfig["modelsPath"].orEmpty().trim()
    if (modelsPath.isEmpty()) {
      return Result.failure(UnsupportedOperationException("No 'modelsPath' configured on Custom REST Adapter; add models manually."))
    }

    val endpoint = ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, modelsPath)
    val req =
      Request.Builder()
        .url(endpoint)
        .get()
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey) }
        .build()

    val (res, err) =
      transport.executeJson(req, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching {
      val json = JSONObject(res!!.body)
      val arr =
        json.optJSONArray("data")
          ?: json.optJSONArray("models")
          ?: throw IllegalStateException("Expected 'data' or 'models' JSON array in models response")
      val list = mutableListOf<AIModel>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "").ifBlank { obj.optString("name", "") }.trim()
        if (id.isEmpty()) continue
        list +=
          AIModel(
            compositeId = AIModel.makeCompositeId(config.id, id),
            providerId = config.id,
            modelId = id,
            displayName = obj.optString("display_name", "").ifBlank { obj.optString("name", id) },
            contextWindow = null,
            maxOutputTokens = null,
            visionCapability = CapabilitySupport.UNKNOWN,
            toolCallingCapability = CapabilitySupport.UNKNOWN,
            streamingCapability = CapabilitySupport.UNKNOWN,
            availabilityStatus = ModelAvailabilityStatus.DISCOVERED,
            isManual = false,
          )
      }
      list
    }
  }

  override suspend fun verifyModel(
    config: AIProviderConfig,
    plainApiKey: String,
    modelId: String,
  ): ProviderTestResult {
    val req =
      AIRequest(
        modelId = modelId,
        messages = listOf(AIMessage("user", "Reply OK")),
        maxOutputTokens = 8,
        stream = false,
      )
    val start = System.currentTimeMillis()
    val res = generate(config, plainApiKey, req)
    val latency = System.currentTimeMillis() - start
    return res.fold(
      onSuccess = {
        ProviderTestResult(
          success = true,
          httpStatus = it.httpStatus,
          latencyMs = latency,
          message = "Custom adapter verified with model '$modelId' (${latency}ms).",
        )
      },
      onFailure = { err ->
        val pe = (err as? ProviderException)?.error
        ProviderTestResult(
          success = false,
          httpStatus = pe?.httpStatus,
          latencyMs = latency,
          message = err.message ?: "Verification failed",
          errorCategory = pe?.category ?: ErrorCategory.UNSUPPORTED_FORMAT,
        )
      },
    )
  }

  override suspend fun generate(
    config: AIProviderConfig,
    plainApiKey: String,
    request: AIRequest,
  ): Result<AIResponse> {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) return Result.failure(IllegalArgumentException(urlCheck.errorMessage))

    val chatPath = config.customConfig["chatPath"].orEmpty().trim()
    val endpoint =
      if (chatPath.isNotEmpty()) ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, chatPath, listOf("/$chatPath"))
      else urlCheck.normalizedUrl

    val payload = buildCustomPayload(config, request, stream = false)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(payload.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val (res, err) =
      transport.executeJson(httpReq, config.displayName, request.timeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching {
      val json = JSONObject(res!!.body)
      val text = extractCustomText(json, config.customConfig["responseTextKey"])
        ?: throw IllegalStateException("Unsupported custom adapter response format")
      AIResponse(
        text = text,
        usage = AiTokenUsage(),
        modelId = request.modelId,
        httpStatus = res.statusCode,
      )
    }
  }

  override fun stream(
    config: AIProviderConfig,
    plainApiKey: String,
    request: AIRequest,
    handler: AIStreamHandler,
    onCallCreated: ((CancellableRequest) -> Unit)?,
  ): CancellableRequest {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) {
      handler.onError(ProviderError(ErrorCategory.BAD_REQUEST, message = urlCheck.errorMessage ?: "Invalid URL"))
      return NoOpCancellable
    }

    val chatPath = config.customConfig["chatPath"].orEmpty().trim()
    val endpoint =
      if (chatPath.isNotEmpty()) ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, chatPath, listOf("/$chatPath"))
      else urlCheck.normalizedUrl
    val payload = buildCustomPayload(config, request, stream = config.supportsStreaming && request.stream)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(payload.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val responseKey = config.customConfig["responseTextKey"]
    return transport.executeSse(
      request = httpReq,
      providerLabel = config.displayName,
      timeoutSeconds = request.timeoutSeconds,
      secretsToRedact = listOf(plainApiKey),
      onCallCreated = onCallCreated,
      onEventPayload = { _, raw ->
        runCatching {
          val json = JSONObject(raw)
          val text = extractCustomText(json, responseKey)
          if (!text.isNullOrEmpty()) handler.onToken(text)
        }.onFailure { ex ->
          handler.onError(ProviderError(ErrorCategory.INVALID_JSON, message = "Malformed custom response JSON: ${ex.message}"))
        }
      },
      onCompleted = { handler.onCompleted() },
      onError = { handler.onError(it) },
    )
  }

  private fun buildCustomPayload(config: AIProviderConfig, request: AIRequest, stream: Boolean): JSONObject {
    val messages = JSONArray()
    if (request.systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
    }
    request.messages.forEach { msg ->
      messages.put(JSONObject().put("role", msg.role).put("content", msg.content))
    }
    return JSONObject().apply {
      if (request.modelId.isNotBlank()) put("model", request.modelId)
      put("messages", messages)
      put("max_tokens", request.maxOutputTokens)
      put("stream", stream)
      if (config.customConfig["requestFormat"] == "prompt") {
        put("prompt", request.messages.lastOrNull()?.content.orEmpty())
      }
    }
  }

  private fun extractCustomText(json: JSONObject, configuredKey: String?): String? {
    if (!configuredKey.isNullOrBlank()) {
      val path = configuredKey.split('.')
      var current: Any? = json
      for (segment in path) {
        current = (current as? JSONObject)?.opt(segment)
      }
      if (current is String && current.isNotEmpty()) return current
    }
    json.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
      choice.optJSONObject("delta")?.optString("content", "")?.takeIf { it.isNotEmpty() }?.let { return it }
      choice.optJSONObject("message")?.optString("content", "")?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return listOf("response", "text", "output", "content")
      .firstNotNullOfOrNull { k -> json.optString(k, "").takeIf { it.isNotEmpty() } }
  }
}
