package com.example.ai.provider

import com.example.ai.AiTokenUsage
import com.example.core.preferences.OnlineProviderType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class AnthropicProviderAdapter(
  private val transport: AITransport,
) : AIProvider {
  override val providerType: OnlineProviderType = OnlineProviderType.ANTHROPIC

  override val capabilities =
    ProviderCapability(
      supportsStreaming = true,
      supportsModelDiscovery = true,
      supportsToolCalling = true,
      supportsVision = true,
      supportsSystemInstruction = true,
    )

  private fun apiVersion(config: AIProviderConfig): String =
    config.apiVersion.trim().ifEmpty { "2023-06-01" }

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
    val req =
      Request.Builder()
        .url(modelsUrl)
        .get()
        .header("anthropic-version", apiVersion(config))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey) }
        .build()

    val (res, err) =
      transport.executeJson(req, config.displayName, config.requestTimeoutSeconds, listOf(plainApiKey))
    if (err != null) {
      if (err.httpStatus == 404 && config.defaultModelId.isNotBlank()) {
        return verifyModel(config, plainApiKey, config.defaultModelId).copy(securityWarning = urlCheck.securityWarning)
      }
      return ProviderTestResult(
        success = false,
        httpStatus = err.httpStatus,
        message = err.message,
        errorCategory = err.category,
        securityWarning = urlCheck.securityWarning,
      )
    }

    val models =
      runCatching { parseModelsResponse(config.id, res!!.body) }.getOrElse { ex ->
        return ProviderTestResult(
          success = false,
          httpStatus = res?.statusCode,
          latencyMs = res?.latencyMs ?: 0L,
          message = "Invalid Anthropic models JSON: ${ex.message}",
          errorCategory = ErrorCategory.INVALID_JSON,
          securityWarning = urlCheck.securityWarning,
        )
      }

    if (config.defaultModelId.isNotBlank()) {
      val mv = verifyModel(config, plainApiKey, config.defaultModelId)
      if (!mv.success) {
        return mv.copy(discoveredModelCount = models.size, securityWarning = urlCheck.securityWarning)
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
      return Result.failure(IllegalStateException("API key is required to list Anthropic models."))
    }

    val modelsUrl =
      ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "models", listOf("/models"))
    val req =
      Request.Builder()
        .url(modelsUrl)
        .get()
        .header("anthropic-version", apiVersion(config))
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
      ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "messages", listOf("/messages"))
    val body = buildPayload(request, stream = false)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .header("anthropic-version", apiVersion(config))
        .post(AITransport.jsonBody(body.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    val (res, err) =
      transport.executeJson(httpReq, config.displayName, request.timeoutSeconds, listOf(plainApiKey))
    if (err != null) return Result.failure(ProviderException(err))

    return runCatching {
      val json = JSONObject(res!!.body)
      val contentArr =
        json.optJSONArray("content")
          ?: throw IllegalStateException("Unsupported Anthropic response format: missing 'content'")
      val textSb = StringBuilder()
      val calls = mutableListOf<AIToolCallRequest>()
      for (i in 0 until contentArr.length()) {
        val block = contentArr.getJSONObject(i)
        when (block.optString("type")) {
          "text" -> textSb.append(block.optString("text", ""))
          "tool_use" -> {
            val name = block.optString("name", "").trim()
            if (name.isNotEmpty()) {
              calls +=
                AIToolCallRequest(
                  id = block.optString("id", "toolu_$i"),
                  name = name,
                  argumentsJson = block.optJSONObject("input")?.toString() ?: "{}",
                )
            }
          }
        }
      }
      val usageObj = json.optJSONObject("usage")
      val usage =
        if (usageObj != null) {
          AiTokenUsage(
            promptTokens = usageObj.optInt("input_tokens", 0),
            completionTokens = usageObj.optInt("output_tokens", 0),
          )
        } else {
          AiTokenUsage()
        }
      AIResponse(
        text = textSb.toString(),
        toolCalls = calls,
        usage = usage,
        finishReason = json.optString("stop_reason", ""),
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
      ProviderUrlValidator.resolveEndpoint(urlCheck.normalizedUrl, "messages", listOf("/messages"))
    val body = buildPayload(request, stream = true)
    val httpReq =
      Request.Builder()
        .url(endpoint)
        .header("anthropic-version", apiVersion(config))
        .post(AITransport.jsonBody(body.toString()))
        .apply { ProviderAuthHelper.applyAuthAndHeaders(this, config, plainApiKey, request.customHeaders) }
        .build()

    var inputTokens = 0
    var outputTokens = 0
    var stopReason = ""
    val toolBlocks = linkedMapOf<Int, Triple<String, String, StringBuilder>>()

    return transport.executeSse(
      request = httpReq,
      providerLabel = config.displayName,
      timeoutSeconds = request.timeoutSeconds,
      secretsToRedact = listOf(plainApiKey),
      onCallCreated = onCallCreated,
      onEventPayload = { _, payload ->
        runCatching {
          val json = JSONObject(payload)
          when (json.optString("type")) {
            "message_start" -> {
              json.optJSONObject("message")?.optJSONObject("usage")?.let { u ->
                inputTokens = u.optInt("input_tokens", inputTokens)
              }
            }
            "content_block_start" -> {
              val idx = json.optInt("index", 0)
              val block = json.optJSONObject("content_block")
              if (block?.optString("type") == "tool_use") {
                val id = block.optString("id", "toolu_$idx")
                val name = block.optString("name", "")
                toolBlocks[idx] = Triple(id, name, StringBuilder())
              }
            }
            "content_block_delta" -> {
              val idx = json.optInt("index", 0)
              val delta = json.optJSONObject("delta")
              when (delta?.optString("type")) {
                "text_delta" -> {
                  val text = delta.optString("text", "")
                  if (text.isNotEmpty()) handler.onToken(text)
                }
                "input_json_delta" -> {
                  val partial = delta.optString("partial_json", "")
                  toolBlocks[idx]?.third?.append(partial)
                }
              }
            }
            "content_block_stop" -> {
              val idx = json.optInt("index", 0)
              toolBlocks.remove(idx)?.let { (id, name, sb) ->
                if (name.isNotBlank()) {
                  handler.onToolCall(AIToolCallRequest(id, name, sb.toString().ifBlank { "{}" }))
                }
              }
            }
            "message_delta" -> {
              stopReason = json.optJSONObject("delta")?.optString("stop_reason", "") ?: stopReason
              json.optJSONObject("usage")?.let { u ->
                if (u.has("input_tokens")) inputTokens = u.optInt("input_tokens", inputTokens)
                outputTokens = u.optInt("output_tokens", outputTokens)
                handler.onUsage(AiTokenUsage(inputTokens, outputTokens))
              }
            }
            "error" -> {
              val msg = json.optJSONObject("error")?.optString("message", "Anthropic stream error") ?: "Anthropic stream error"
              handler.onError(ProviderError(ErrorCategory.SERVER_ERROR, message = msg))
            }
          }
        }.onFailure { ex ->
          handler.onError(ProviderError(ErrorCategory.INVALID_JSON, message = "Malformed Anthropic SSE chunk: ${ex.message}"))
        }
      },
      onCompleted = {
        if (inputTokens > 0 || outputTokens > 0) {
          handler.onUsage(AiTokenUsage(inputTokens, outputTokens))
        }
        handler.onCompleted(stopReason)
      },
      onError = { handler.onError(it) },
    )
  }

  private fun parseModelsResponse(providerId: String, rawBody: String): List<AIModel> {
    val json = JSONObject(rawBody)
    val arr = json.optJSONArray("data") ?: throw IllegalStateException("Missing 'data' array in Anthropic models response")
    val out = mutableListOf<AIModel>()
    for (i in 0 until arr.length()) {
      val obj = arr.getJSONObject(i)
      val id = obj.optString("id", "").trim()
      if (id.isEmpty()) continue
      val displayName = obj.optString("display_name", "").ifBlank { id }
      out +=
        AIModel(
          compositeId = AIModel.makeCompositeId(providerId, id),
          providerId = providerId,
          modelId = id,
          displayName = displayName,
          contextWindow = null,
          maxOutputTokens = null,
          inputModalities = "UNKNOWN",
          outputModalities = "UNKNOWN",
          visionCapability = CapabilitySupport.UNKNOWN,
          toolCallingCapability = CapabilitySupport.UNKNOWN,
          streamingCapability = CapabilitySupport.SUPPORTED,
          availabilityStatus = ModelAvailabilityStatus.DISCOVERED,
          isManual = false,
        )
    }
    return out
  }

  private fun buildPayload(request: AIRequest, stream: Boolean): JSONObject {
    val messages = JSONArray()
    for (msg in request.messages) {
      if (msg.toolResult != null) {
        val tr = msg.toolResult
        val block =
          JSONObject().apply {
            put("type", "tool_result")
            put("tool_use_id", tr.callId)
            put("content", tr.outputText)
            put("is_error", tr.isError)
          }
        messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(block)))
        continue
      }

      val role = if (msg.role == "assistant") "assistant" else "user"
      if (msg.toolCalls.isEmpty()) {
        messages.put(JSONObject().put("role", role).put("content", msg.content))
      } else {
        val blocks = JSONArray()
        if (msg.content.isNotBlank()) {
          blocks.put(JSONObject().put("type", "text").put("text", msg.content))
        }
        for (tc in msg.toolCalls) {
          val inputObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
          blocks.put(
            JSONObject().apply {
              put("type", "tool_use")
              put("id", tc.id)
              put("name", tc.name)
              put("input", inputObj)
            }
          )
        }
        messages.put(JSONObject().put("role", role).put("content", blocks))
      }
    }

    return JSONObject().apply {
      put("model", request.modelId)
      put("max_tokens", request.maxOutputTokens.coerceIn(1, 32768))
      if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
      request.temperature?.let { put("temperature", it) }
      put("messages", messages)
      put("stream", stream)
      if (request.tools.isNotEmpty()) {
        val toolsArr = JSONArray()
        for (tool in request.tools) {
          val schemaObj =
            runCatching { JSONObject(tool.inputSchemaJson) }.getOrElse { JSONObject().put("type", "object") }
          toolsArr.put(
            JSONObject().apply {
              put("name", tool.name)
              put("description", tool.description)
              put("input_schema", schemaObj)
            }
          )
        }
        put("tools", toolsArr)
      }
    }
  }
}
