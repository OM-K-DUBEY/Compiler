package com.example.ai.provider

import com.example.ai.AiTokenUsage
import com.example.core.preferences.OnlineProviderType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class OpenAiProviderAdapter(
  override val providerType: OnlineProviderType,
  private val transport: AITransport,
) : AIProvider {

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

    val modelsUrl =
      ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "models", listOf("/models"))
    val modelsReq =
      Request.Builder()
        .url(modelsUrl)
        .get()
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey) }
        .build()

    val (res, err) =
      transport.executeJson(modelsReq, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
    if (err != null) {
      // Some OpenAI-compatible relays disable /models (404) but support /chat/completions
      val targetModel = config.defaultModelId.trim()
      if (err.httpStatus == 404 && targetModel.isNotEmpty()) {
        return verifyModel(config, plainApiKey, targetModel).copy(securityWarning = urlCheck.securityWarning)
      }
      return ProviderTestResult(
        success = false,
        httpStatus = err.httpStatus,
        message = err.message,
        errorCategory = err.category,
        securityWarning = urlCheck.securityWarning,
      )
    }

    val parsedCount =
      runCatching { parseModelsResponse(config.id, res!!.body).size }.getOrElse { ex ->
        return ProviderTestResult(
          success = false,
          httpStatus = res?.statusCode,
          latencyMs = res?.latencyMs ?: 0L,
          message = "Endpoint returned HTTP ${res?.statusCode}, but body is not valid OpenAI models JSON: ${ex.message}",
          errorCategory = ErrorCategory.INVALID_JSON,
          securityWarning = urlCheck.securityWarning,
        )
      }

    val targetModel = config.defaultModelId.trim()
    if (targetModel.isNotEmpty()) {
      val modelVerify = verifyModel(config, plainApiKey, targetModel)
      if (!modelVerify.success) {
        return modelVerify.copy(
          discoveredModelCount = parsedCount,
          securityWarning = urlCheck.securityWarning,
        )
      }
    }

    return ProviderTestResult(
      success = true,
      httpStatus = res?.statusCode ?: 200,
      latencyMs = res?.latencyMs ?: 0L,
      message = "Connected to ${config.displayName}. Discovered $parsedCount model(s).",
      discoveredModelCount = parsedCount,
      securityWarning = urlCheck.securityWarning,
    )
  }

  override suspend fun listModels(config: AIProviderConfig, plainApiKey: String): Result<List<AIModel>> {
    val urlCheck = ProviderUrlValidator.validate(config.baseUrl)
    if (!urlCheck.isValid) return Result.failure(IllegalArgumentException(urlCheck.errorMessage))
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      return Result.failure(IllegalStateException("API key is required to discover models."))
    }

    val modelsUrl =
      ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "models", listOf("/models"))
    val req =
      Request.Builder()
        .url(modelsUrl)
        .get()
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey) }
        .build()

    val (res, err) =
      transport.executeJson(req, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching { parseModelsResponse(config.id, res!!.body) }
  }

  override suspend fun verifyModel(
    config: AIProviderConfig,
    plainApiKey: String,
    modelId: String,
  ): ProviderTestResult {
    val minimal =
      AIRequest(
        modelId = modelId,
        messages = listOf(AIMessage("user", "Reply OK")),
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
    if (request.modelId.isBlank()) return Result.failure(IllegalArgumentException("Model ID is empty."))

    val endpoint =
      ProviderUrlValidator.resolveEndpoint(
        urlCheck.normalizedUrl,
        "chat/completions",
        listOf("/chat/completions"),
      )
    val body = buildPayload(request, stream = false)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(body.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val (res, err) =
      transport.executeJson(httpReq, config.displayName, request.timeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching {
      val json = JSONObject(res!!.body)
      val choices =
        json.optJSONArray("choices")
          ?: throw IllegalStateException("Unsupported OpenAI response format: missing 'choices'")
      if (choices.length() == 0) throw IllegalStateException("Empty 'choices' array in response")
      val first = choices.getJSONObject(0)
      val msg = first.optJSONObject("message") ?: first.optJSONObject("delta") ?: JSONObject()
      val text = msg.optString("content", "")
      val finish = first.optString("finish_reason", "")
      val calls = mutableListOf<AIToolCallRequest>()
      msg.optJSONArray("tool_calls")?.let { arr ->
        for (i in 0 until arr.length()) {
          val tc = arr.getJSONObject(i)
          val fn = tc.optJSONObject("function") ?: continue
          val name = fn.optString("name", "").trim()
          if (name.isNotEmpty()) {
            calls +=
              AIToolCallRequest(
                id = tc.optString("id", "call_$i"),
                name = name,
                argumentsJson = fn.optString("arguments", "{}"),
              )
          }
        }
      }
      val usageObj = json.optJSONObject("usage")
      val usage =
        if (usageObj != null) {
          AiTokenUsage(
            promptTokens = usageObj.optInt("prompt_tokens", 0),
            completionTokens = usageObj.optInt("completion_tokens", 0),
          )
        } else {
          AiTokenUsage()
        }
      AIResponse(
        text = text,
        toolCalls = calls,
        usage = usage,
        finishReason = finish,
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
    if (config.authMethod != AuthMethod.NONE && plainApiKey.isBlank()) {
      handler.onError(ProviderError(ErrorCategory.AUTH_FAILED, 401, "API key is not configured for ${config.displayName}."))
      return NoOpCancellable
    }
    if (request.modelId.isBlank()) {
      handler.onError(ProviderError(ErrorCategory.BAD_REQUEST, message = "Model ID is empty."))
      return NoOpCancellable
    }

    val endpoint =
      ProviderUrlValidator.resolveEndpoint(
        urlCheck.normalizedUrl,
        "chat/completions",
        listOf("/chat/completions"),
      )
    val body = buildPayload(request, stream = true)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .post(AITransport.jsonBody(body.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val partialToolCalls = linkedMapOf<Int, Triple<String, StringBuilder, StringBuilder>>()
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
          val choices = json.optJSONArray("choices")
          if (choices != null && choices.length() > 0) {
            val first = choices.getJSONObject(0)
            val finish = first.optString("finish_reason", "")
            if (finish.isNotEmpty() && finish != "null") lastFinish = finish
            val delta = first.optJSONObject("delta") ?: first.optJSONObject("message")
            val text = delta?.optString("content", "").orEmpty()
            if (text.isNotEmpty() && text != "null") handler.onToken(text)

            delta?.optJSONArray("tool_calls")?.let { arr ->
              for (i in 0 until arr.length()) {
                val tc = arr.getJSONObject(i)
                val idx = tc.optInt("index", i)
                val id = tc.optString("id", "")
                val fn = tc.optJSONObject("function")
                val namePart = fn?.optString("name", "").orEmpty()
                val argsPart = fn?.optString("arguments", "").orEmpty()
                val existing = partialToolCalls[idx]
                if (existing == null) {
                  partialToolCalls[idx] =
                    Triple(
                      id.ifBlank { "call_$idx" },
                      StringBuilder(namePart),
                      StringBuilder(argsPart),
                    )
                } else {
                  if (namePart.isNotEmpty()) existing.second.append(namePart)
                  if (argsPart.isNotEmpty()) existing.third.append(argsPart)
                }
              }
            }
          }
          json.optJSONObject("usage")?.let { u ->
            handler.onUsage(
              AiTokenUsage(
                promptTokens = u.optInt("prompt_tokens", 0),
                completionTokens = u.optInt("completion_tokens", 0),
              )
            )
          }
        }.onFailure { ex ->
          handler.onError(ProviderError(ErrorCategory.INVALID_JSON, message = "Malformed OpenAI stream chunk: ${ex.message}"))
        }
      },
      onCompleted = {
        partialToolCalls.values.forEach { (id, nameSb, argsSb) ->
          val name = nameSb.toString().trim()
          if (name.isNotEmpty()) {
            handler.onToolCall(AIToolCallRequest(id, name, argsSb.toString().ifBlank { "{}" }))
          }
        }
        handler.onCompleted(lastFinish)
      },
      onError = { handler.onError(it) },
    )
  }

  private fun parseModelsResponse(providerId: String, rawBody: String): List<AIModel> {
    val json = JSONObject(rawBody)
    val arr = json.optJSONArray("data") ?: throw IllegalStateException("Missing 'data' array in models response")
    val out = mutableListOf<AIModel>()
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      val id = obj.optString("id", "").trim()
      if (id.isEmpty()) continue
      val displayName = obj.optString("name", "").ifBlank { id }
      val ctx =
        when {
          obj.has("context_length") && !obj.isNull("context_length") -> obj.optInt("context_length")
          obj.has("context_window") && !obj.isNull("context_window") -> obj.optInt("context_window")
          else -> null
        }
      val maxOut =
        if (obj.has("max_output_tokens") && !obj.isNull("max_output_tokens")) obj.optInt("max_output_tokens")
        else null

      out +=
        AIModel(
          compositeId = AIModel.makeCompositeId(providerId, id),
          providerId = providerId,
          modelId = id,
          displayName = displayName,
          contextWindow = ctx,
          maxOutputTokens = maxOut,
          inputModalities = "UNKNOWN",
          outputModalities = "UNKNOWN",
          visionCapability = CapabilitySupport.UNKNOWN,
          toolCallingCapability = CapabilitySupport.UNKNOWN,
          streamingCapability = CapabilitySupport.UNKNOWN,
          availabilityStatus = ModelAvailabilityStatus.DISCOVERED,
          isManual = false,
        )
    }
    return out
  }

  private fun buildPayload(request: AIRequest, stream: Boolean): JSONObject {
    val messages = JSONArray()
    if (request.systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
    }
    for (msg in request.messages) {
      if (msg.toolResult != null) {
        messages.put(
          JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", msg.toolResult.callId)
            put("content", msg.toolResult.outputText)
          }
        )
        continue
      }
      val obj = JSONObject().put("role", msg.role)
      if (msg.content.isNotEmpty() || msg.toolCalls.isEmpty()) {
        obj.put("content", msg.content)
      }
      if (msg.toolCalls.isNotEmpty()) {
        val tcArr = JSONArray()
        for (tc in msg.toolCalls) {
          tcArr.put(
            JSONObject().apply {
              put("id", tc.id)
              put("type", "function")
              put(
                "function",
                JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson),
              )
            }
          )
        }
        obj.put("tool_calls", tcArr)
      }
      messages.put(obj)
    }

    return JSONObject().apply {
      put("model", request.modelId)
      put("messages", messages)
      if (request.maxOutputTokens > 0) put("max_tokens", request.maxOutputTokens)
      request.temperature?.let { put("temperature", it) }
      put("stream", stream)
      if (stream) {
        put("stream_options", JSONObject().put("include_usage", true))
      }
      if (request.tools.isNotEmpty()) {
        val toolsArr = JSONArray()
        for (tool in request.tools) {
          val paramsObj =
            runCatching { JSONObject(tool.inputSchemaJson) }.getOrElse { JSONObject().put("type", "object") }
          toolsArr.put(
            JSONObject().apply {
              put("type", "function")
              put(
                "function",
                JSONObject().apply {
                  put("name", tool.name)
                  put("description", tool.description)
                  put("parameters", paramsObj)
                },
              )
            }
          )
        }
        put("tools", toolsArr)
      }
    }
  }
}
