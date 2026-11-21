package com.example.ai.provider

import com.example.ai.AiTokenUsage
import com.example.core.preferences.OnlineProviderType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class GeminiProviderAdapter(
  private val transport: AITransport,
) : AIProvider {
  override val providerType: OnlineProviderType = OnlineProviderType.GEMINI

  override val capabilities =
    ProviderCapability(
      supportsStreaming = true,
      supportsModelDiscovery = true,
      supportsToolCalling = true,
      supportsVision = true,
      supportsSystemInstruction = true,
    )

  override suspend fun testConnection(config: AIProviderConfig, plainApiKey: String): ProviderTestResult {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) {
      return ProviderTestResult(false, message = urlCheck.errorMessage ?: "Invalid URL")
    }
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      return ProviderTestResult(false, message = "API key is required for ${config.displayName}.")
    }

    val modelsUrl = ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "models", listOf("/models"))
    val req = Request.Builder().url(modelsUrl).get().apply {
      ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey)
    }.build()

    val (res, err) = transport.executeJson(req, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
    if (err != null) {
      return ProviderTestResult(
        success = false,
        httpStatus = err.httpStatus,
        message = err.message,
        errorCategory = err.category,
        securityWarning = urlCheck.securityWarning,
      )
    }

    val models = runCatching { parseModelsResponse(config.id, res!!.body) }.getOrElse { ex ->
      return ProviderTestResult(
        success = false,
        httpStatus = res?.statusCode,
        latencyMs = res?.latencyMs ?: 0L,
        message = "Connected to host, but response was not valid Gemini model JSON: ${ex.message}",
        errorCategory = ErrorCategory.INVALID_JSON,
        securityWarning = urlCheck.securityWarning,
      )
    }

    // If a specific model ID was configured, verify a minimal generateContent call too
    val targetModel = config.defaultModelId.trim()
    if (targetModel.isNotEmpty()) {
      val modelVerify = verifyModel(config, plainApiKey, targetModel)
      if (!modelVerify.success) {
        return modelVerify.copy(discoveredModelCount = models.size, securityWarning = urlCheck.securityWarning)
      }
    }

    return ProviderTestResult(
      success = true,
      httpStatus = res?.statusCode ?: 200,
      latencyMs = res?.latencyMs ?: 0L,
      message = "Connected to ${config.displayName}. Discovered ${models.size} model(s).",
      discoveredModelCount = models.size,
      securityWarning = urlCheck.securityWarning,
    )
  }

  override suspend fun listModels(config: AIProviderConfig, plainApiKey: String): Result<List<AIModel>> {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) return Result.failure(IllegalArgumentException(urlCheck.errorMessage))
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      return Result.failure(IllegalStateException("API key is required to discover Gemini models."))
    }

    val collected = mutableListOf<AIModel>()
    var pageToken: String? = null
    var pages = 0

    while (pages < 10) {
      pages++
      val baseModelsUrl = ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "models", listOf("/models"))
      val url =
        if (pageToken.isNullOrBlank()) "$baseModelsUrl?pageSize=100"
        else "$baseModelsUrl?pageSize=100&pageToken=$pageToken"
      val req = Request.Builder().url(url).get().apply {
        ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey)
      }.build()

      val (res, err) = transport.executeJson(req, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
      if (err != null) return Result.failure(IllegalStateException(err.message))

      val parsed =
        runCatching {
          val json = JSONObject(res!!.body)
          pageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() }
          parseModelsResponse(config.id, res.body)
        }.getOrElse { ex ->
          return Result.failure(IllegalStateException("Invalid Gemini models JSON: ${ex.message}"))
        }
      collected += parsed
      if (pageToken == null) break
    }
    return Result.success(collected.distinctBy { it.modelId })
  }

  override suspend fun verifyModel(
    config: AIProviderConfig,
    plainApiKey: String,
    modelId: String,
  ): ProviderTestResult {
    val minimal =
      AIRequest(
        modelId = modelId,
        messages = listOf(AIMessage("user", "Reply with OK")),
        maxOutputTokens = 8,
        stream = false,
      )
    val start = System.currentTimeMillis()
    val res = generate(config, plainApiKey, minimal)
    val latency = System.currentTimeMillis() - start
    return res.fold(
      onSuccess = {
        ProviderTestResult(
          success = true,
          httpStatus = it.httpStatus,
          latencyMs = latency,
          message = "Model '$modelId' verified (${latency}ms).",
        )
      },
      onFailure = { err ->
        val pe = (err as? ProviderException)?.error
        ProviderTestResult(
          success = false,
          httpStatus = pe?.httpStatus,
          latencyMs = latency,
          message = err.message ?: "Model verification failed",
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
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      return Result.failure(IllegalStateException("API key is required for ${config.displayName}."))
    }
    val modelId = request.modelId.trim().removePrefix("models/")
    if (modelId.isEmpty()) return Result.failure(IllegalArgumentException("Model ID is empty."))

    val endpoint = "${urlCheck.normalizedUrl}/models/$modelId:generateContent"
    val bodyJson = buildRequestBody(request)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(bodyJson.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val (res, err) =
      transport.executeJson(httpReq, config.displayName, request.timeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching {
      val json = JSONObject(res!!.body)
      if (!json.has("candidates")) {
        throw IllegalStateException("Unsupported Gemini response format: missing 'candidates'")
      }
      val (text, calls, finish) = extractFromCandidateContainer(json)
      val usage = extractUsage(json)
      AIResponse(
        text = text,
        toolCalls = calls,
        usage = usage,
        finishReason = finish,
        modelId = modelId,
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
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      handler.onError(ProviderError(ErrorCategory.AUTH_FAILED, 401, "API key is not configured for ${config.displayName}."))
      return NoOpCancellable
    }
    val modelId = request.modelId.trim().removePrefix("models/")
    if (modelId.isEmpty()) {
      handler.onError(ProviderError(ErrorCategory.BAD_REQUEST, message = "Model ID is empty."))
      return NoOpCancellable
    }

    val endpoint = "${urlCheck.normalizedUrl}/models/$modelId:streamGenerateContent?alt=sse"
    val bodyJson = buildRequestBody(request)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(bodyJson.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    var lastFinish = ""
    return transport.executeSse(
      request = httpReq,
      providerLabel = config.displayName,
      timeoutSeconds = request.timeoutSeconds,
      secretsToRedact = listOf(plainApiKey),
      onCallCreated = onCallCreated,
      onEventPayload = { _, payload ->
        runCatching {
          val json = JSONObject(payload)
          val (text, calls, finish) = extractFromCandidateContainer(json)
          if (finish.isNotEmpty()) lastFinish = finish
          if (text.isNotEmpty()) handler.onToken(text)
          calls.forEach { handler.onToolCall(it) }
          extractUsage(json).let { usage ->
            if (usage.promptTokens > 0 || usage.completionTokens > 0) handler.onUsage(usage)
          }
        }.onFailure { ex ->
          handler.onError(ProviderError(ErrorCategory.INVALID_JSON, message = "Malformed Gemini stream chunk: ${ex.message}"))
        }
      },
      onCompleted = { handler.onCompleted(lastFinish) },
      onError = { handler.onError(it) },
    )
  }

  private fun parseModelsResponse(providerId: String, rawBody: String): List<AIModel> {
    val json = JSONObject(rawBody)
    val arr = json.optJSONArray("models") ?: throw IllegalStateException("Missing 'models' array in Gemini response")
    val list = mutableListOf<AIModel>()
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      val rawName = obj.optString("name", "").trim()
      if (rawName.isEmpty()) continue
      val modelId = rawName.removePrefix("models/")
      val methods = mutableListOf<String>()
      obj.optJSONArray("supportedGenerationMethods")?.let { mArr ->
        for (j in 0 until mArr.length()) methods += mArr.optString(j)
      }
      if (methods.isNotEmpty() && "generateContent" !in methods) continue

      val displayName = obj.optString("displayName", modelId).ifBlank { modelId }
      val inputLimit = if (obj.has("inputTokenLimit")) obj.optInt("inputTokenLimit") else null
      val outputLimit = if (obj.has("outputTokenLimit")) obj.optInt("outputTokenLimit") else null
      val streamingCap =
        if ("streamGenerateContent" in methods) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN

      list +=
        AIModel(
          compositeId = AIModel.makeCompositeId(providerId, modelId),
          providerId = providerId,
          modelId = modelId,
          displayName = displayName,
          contextWindow = inputLimit,
          maxOutputTokens = outputLimit,
          inputModalities = "UNKNOWN",
          outputModalities = "text",
          visionCapability = CapabilitySupport.UNKNOWN,
          toolCallingCapability = CapabilitySupport.UNKNOWN,
          streamingCapability = streamingCap,
          availabilityStatus = ModelAvailabilityStatus.DISCOVERED,
          isManual = false,
        )
    }
    return list
  }

  private fun buildRequestBody(request: AIRequest): JSONObject {
    val contents = JSONArray()
    for (msg in request.messages) {
      if (msg.toolResult != null) {
        val tr = msg.toolResult
        val respObj =
          JSONObject().apply {
            put(
              "functionResponse",
              JSONObject().apply {
                put("name", tr.toolName)
                put(
                  "response",
                  JSONObject().put("content", tr.outputText).put("isError", tr.isError),
                )
              },
            )
          }
        contents.put(
          JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(respObj))
          }
        )
        continue
      }

      val parts = JSONArray()
      if (msg.content.isNotBlank()) {
        parts.put(JSONObject().put("text", msg.content))
      }
      for (tc in msg.toolCalls) {
        val argsObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
        parts.put(
          JSONObject().put(
            "functionCall",
            JSONObject().put("name", tc.name).put("args", argsObj),
          )
        )
      }
      if (parts.length() > 0) {
        val role = if (msg.role == "assistant" || msg.role == "model") "model" else "user"
        contents.put(JSONObject().put("role", role).put("parts", parts))
      }
    }

    return JSONObject().apply {
      if (request.systemPrompt.isNotBlank()) {
        put(
          "systemInstruction",
          JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))),
        )
      }
      put("contents", contents)
      put(
        "generationConfig",
        JSONObject().apply {
          put("maxOutputTokens", request.maxOutputTokens.coerceIn(1, 65536))
          request.temperature?.let { put("temperature", it) }
        },
      )
      if (request.tools.isNotEmpty()) {
        val decls = JSONArray()
        for (tool in request.tools) {
          val schemaObj =
            runCatching { JSONObject(tool.inputSchemaJson) }
              .getOrElse { JSONObject().put("type", "object") }
          decls.put(
            JSONObject().apply {
              put("name", tool.name)
              put("description", tool.description)
              put("parameters", schemaObj)
            }
          )
        }
        put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
      }
    }
  }

  private fun extractFromCandidateContainer(json: JSONObject): Triple<String, List<AIToolCallRequest>, String> {
    val candidates = json.optJSONArray("candidates") ?: return Triple("", emptyList(), "")
    if (candidates.length() == 0) return Triple("", emptyList(), "")
    val first = candidates.getJSONObject(0)
    val finish = first.optString("finishReason", "")
    val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return Triple("", emptyList(), finish)
    val textSb = StringBuilder()
    val calls = mutableListOf<AIToolCallRequest>()
    for (i in 0 until parts.length()) {
      val part = parts.getJSONObject(i)
      val t = part.optString("text", "")
      if (t.isNotEmpty()) textSb.append(t)
      val fc = part.optJSONObject("functionCall")
      if (fc != null) {
        val name = fc.optString("name", "").trim()
        if (name.isNotEmpty()) {
          val argsJson = fc.optJSONObject("args")?.toString() ?: "{}"
          calls += AIToolCallRequest(id = "gemini_${System.nanoTime()}_$i", name = name, argumentsJson = argsJson)
        }
      }
    }
    return Triple(textSb.toString(), calls, finish)
  }

  private fun extractUsage(json: JSONObject): AiTokenUsage {
    val u = json.optJSONObject("usageMetadata") ?: return AiTokenUsage()
    return AiTokenUsage(
      promptTokens = u.optInt("promptTokenCount", 0),
      completionTokens = u.optInt("candidatesTokenCount", 0),
    )
  }
}

class ProviderException(val error: ProviderError) : IllegalStateException(error.message)

internal object NoOpCancellable : CancellableRequest {
  override fun cancel() = Unit
}
