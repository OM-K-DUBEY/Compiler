package com.example.ai.provider

import com.example.ai.AiTokenUsage
import com.example.core.preferences.OnlineProviderType

enum class AuthMethod(val displayName: String) {
  BEARER("Bearer Token"),
  X_API_KEY("x-api-key Header"),
  X_GOOG_API_KEY("x-goog-api-key Header"),
  CUSTOM_HEADER("Custom Header"),
  NONE("No Authentication"),
}

enum class CapabilitySupport {
  SUPPORTED,
  UNSUPPORTED,
  UNKNOWN,
}

enum class ModelAvailabilityStatus {
  DISCOVERED,
  VERIFIED,
  UNAVAILABLE,
  MANUAL,
  REMOVED,
}

enum class ConnectionStatus {
  UNKNOWN,
  CONNECTED,
  ERROR,
}

data class ProviderCapability(
  val supportsStreaming: Boolean,
  val supportsModelDiscovery: Boolean,
  val supportsToolCalling: Boolean,
  val supportsVision: Boolean,
  val supportsSystemInstruction: Boolean,
)

data class AIProviderConfig(
  val id: String,
  val displayName: String,
  val providerType: OnlineProviderType,
  val baseUrl: String,
  val encryptedApiKey: String,
  val apiVersion: String = "",
  val authMethod: AuthMethod = defaultAuthFor(providerType),
  val customAuthHeaderName: String = "",
  val defaultModelId: String = providerType.defaultModel,
  val requestTimeoutSeconds: Int = 60,
  val maxOutputTokens: Int = 4096,
  val customHeaders: Map<String, String> = emptyMap(),
  val customConfig: Map<String, String> = emptyMap(),
  val supportsStreaming: Boolean = true,
  val isEnabled: Boolean = true,
  val lastConnectionStatus: ConnectionStatus = ConnectionStatus.UNKNOWN,
  val lastConnectionMessage: String = "",
  val lastTestedAtMillis: Long = 0L,
) {
  val hasApiKey: Boolean get() = encryptedApiKey.isNotBlank()

  override fun toString(): String =
    "AIProviderConfig(id=$id, name=$displayName, type=$providerType, baseUrl=$baseUrl, hasKey=$hasApiKey)"

  companion object {
    fun defaultAuthFor(type: OnlineProviderType): AuthMethod =
      when (type) {
        OnlineProviderType.GEMINI -> AuthMethod.X_GOOG_API_KEY
        OnlineProviderType.OPENAI, OnlineProviderType.CUSTOM_OPENAI -> AuthMethod.BEARER
        OnlineProviderType.ANTHROPIC -> AuthMethod.X_API_KEY
        OnlineProviderType.CUSTOM_ADAPTER -> AuthMethod.BEARER
      }
  }
}

data class AIModel(
  val compositeId: String,
  val providerId: String,
  val modelId: String,
  val displayName: String,
  val contextWindow: Int? = null,
  val maxOutputTokens: Int? = null,
  val inputModalities: String = "UNKNOWN",
  val outputModalities: String = "UNKNOWN",
  val visionCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
  val toolCallingCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
  val streamingCapability: CapabilitySupport = CapabilitySupport.UNKNOWN,
  val availabilityStatus: ModelAvailabilityStatus = ModelAvailabilityStatus.DISCOVERED,
  val isManual: Boolean = false,
  val lastVerifiedAtMillis: Long = 0L,
) {
  companion object {
    fun makeCompositeId(providerId: String, modelId: String): String = "$providerId::$modelId"
  }
}

data class AIToolDefinition(
  val name: String,
  val description: String,
  val inputSchemaJson: String,
  val serverId: String = "",
)

data class AIToolCallRequest(
  val id: String,
  val name: String,
  val argumentsJson: String,
)

data class AIToolCallResult(
  val callId: String,
  val toolName: String,
  val outputText: String,
  val isError: Boolean = false,
)

data class AIMessage(
  val role: String,
  val content: String,
  val toolCalls: List<AIToolCallRequest> = emptyList(),
  val toolResult: AIToolCallResult? = null,
)

data class AIRequest(
  val modelId: String,
  val systemPrompt: String = "",
  val messages: List<AIMessage>,
  val maxOutputTokens: Int = 4096,
  val temperature: Double? = null,
  val stream: Boolean = true,
  val tools: List<AIToolDefinition> = emptyList(),
  val customHeaders: Map<String, String> = emptyMap(),
  val timeoutSeconds: Int = 60,
)

data class AIResponse(
  val text: String,
  val toolCalls: List<AIToolCallRequest> = emptyList(),
  val usage: AiTokenUsage = AiTokenUsage(),
  val finishReason: String = "",
  val modelId: String = "",
  val httpStatus: Int = 200,
)

data class ProviderTestResult(
  val success: Boolean,
  val httpStatus: Int? = null,
  val latencyMs: Long = 0L,
  val message: String,
  val errorCategory: ErrorCategory? = null,
  val discoveredModelCount: Int? = null,
  val securityWarning: String? = null,
)

interface AIStreamHandler {
  fun onToken(text: String)
  fun onToolCall(toolCall: AIToolCallRequest)
  fun onUsage(usage: AiTokenUsage)
  fun onCompleted(finishReason: String = "")
  fun onError(error: ProviderError)
}

interface AIProvider {
  val providerType: OnlineProviderType
  val capabilities: ProviderCapability

  suspend fun testConnection(config: AIProviderConfig, plainApiKey: String): ProviderTestResult

  suspend fun listModels(config: AIProviderConfig, plainApiKey: String): Result<List<AIModel>>

  suspend fun verifyModel(
    config: AIProviderConfig,
    plainApiKey: String,
    modelId: String,
  ): ProviderTestResult

  suspend fun generate(
    config: AIProviderConfig,
    plainApiKey: String,
    request: AIRequest,
  ): Result<AIResponse>

  fun stream(
    config: AIProviderConfig,
    plainApiKey: String,
    request: AIRequest,
    handler: AIStreamHandler,
    onCallCreated: ((CancellableRequest) -> Unit)? = null,
  ): CancellableRequest
}

interface CancellableRequest {
  fun cancel()
}
