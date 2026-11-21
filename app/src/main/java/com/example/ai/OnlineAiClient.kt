package com.example.ai

import com.example.ai.provider.AIMessage
import com.example.ai.provider.AIProviderConfig
import com.example.ai.provider.AIProviderRegistry
import com.example.ai.provider.AIRequest
import com.example.ai.provider.AIResponse
import com.example.ai.provider.AIStreamHandler
import com.example.ai.provider.AIToolCallRequest
import com.example.ai.provider.AIToolDefinition
import com.example.ai.provider.CancellableRequest
import com.example.ai.provider.ProviderError
import com.example.core.common.AppDispatchers
import com.example.core.preferences.OnlineProviderType
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

data class ChatTurn(
  val role: String,
  val content: String,
)

class OnlineAiClient(
  private val providerRegistry: AIProviderRegistry,
  private val dispatchers: AppDispatchers,
) {
  private val activeCall = AtomicReference<CancellableRequest?>(null)

  fun cancelActiveRequest() {
    activeCall.getAndSet(null)?.cancel()
  }

  /**
   * Streams a response from a saved [AIProviderConfig], delegating formatting & auth to the
   * corresponding [com.example.ai.provider.AIProvider] adapter.
   */
  fun streamWithProvider(
    config: AIProviderConfig,
    modelId: String,
    systemPrompt: String,
    messages: List<AIMessage>,
    tools: List<AIToolDefinition> = emptyList(),
  ): Flow<AiStreamEvent> =
    callbackFlow {
      if (!config.isEnabled) {
        trySend(AiStreamEvent.Error("Provider '${config.displayName}' is disabled. Enable it or select another provider."))
        close()
        return@callbackFlow
      }
      val plainApiKey = providerRegistry.decryptKey(config.encryptedApiKey)
      val adapter = providerRegistry.adapterFor(config.providerType)
      val resolvedModel = modelId.ifBlank { config.defaultModelId }
      val request =
        AIRequest(
          modelId = resolvedModel,
          systemPrompt = systemPrompt,
          messages = messages,
          maxOutputTokens = config.maxOutputTokens,
          stream = config.supportsStreaming,
          tools = tools,
          timeoutSeconds = config.requestTimeoutSeconds,
        )

      val handler =
        object : AIStreamHandler {
          override fun onToken(text: String) {
            trySend(AiStreamEvent.Token(text))
          }

          override fun onToolCall(toolCall: AIToolCallRequest) {
            trySend(AiStreamEvent.ToolCall(toolCall))
          }

          override fun onUsage(usage: AiTokenUsage) {
            trySend(AiStreamEvent.Usage(usage))
          }

          override fun onCompleted(finishReason: String) {
            trySend(AiStreamEvent.Completed)
          }

          override fun onError(error: ProviderError) {
            trySend(AiStreamEvent.Error(error.message))
          }
        }

      if (!config.supportsStreaming) {
        val res = adapter.generate(config, plainApiKey, request.copy(stream = false))
        res.onSuccess { resp ->
          if (resp.text.isNotEmpty()) handler.onToken(resp.text)
          resp.toolCalls.forEach { handler.onToolCall(it) }
          handler.onUsage(resp.usage)
          handler.onCompleted(resp.finishReason)
        }.onFailure { err ->
          trySend(AiStreamEvent.Error(err.message ?: "Generation failed"))
        }
        close()
        return@callbackFlow
      }

      val cancellable =
        adapter.stream(
          config = config,
          plainApiKey = plainApiKey,
          request = request,
          handler = handler,
          onCallCreated = { activeCall.set(it) },
        )
      activeCall.set(cancellable)
      activeCall.compareAndSet(cancellable, null)
      close()
      awaitClose { cancellable.cancel() }
    }.flowOn(dispatchers.io)

  suspend fun generateWithProvider(
    config: AIProviderConfig,
    modelId: String,
    systemPrompt: String,
    messages: List<AIMessage>,
    tools: List<AIToolDefinition> = emptyList(),
  ): Result<AIResponse> {
    if (!config.isEnabled) {
      return Result.failure(IllegalStateException("Provider '${config.displayName}' is disabled."))
    }
    val plainApiKey = providerRegistry.decryptKey(config.encryptedApiKey)
    val adapter = providerRegistry.adapterFor(config.providerType)
    val request =
      AIRequest(
        modelId = modelId.ifBlank { config.defaultModelId },
        systemPrompt = systemPrompt,
        messages = messages,
        maxOutputTokens = config.maxOutputTokens,
        stream = false,
        tools = tools,
        timeoutSeconds = config.requestTimeoutSeconds,
      )
    return adapter.generate(config, plainApiKey, request)
  }

  /**
   * Convenience overload for legacy/ad-hoc calls when no Room provider row exists yet.
   */
  fun streamChat(
    provider: OnlineProviderType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    history: List<ChatTurn>,
  ): Flow<AiStreamEvent> =
    callbackFlow {
      val transientConfig =
        AIProviderConfig(
          id = "transient",
          displayName = provider.displayName,
          providerType = provider,
          baseUrl = baseUrl,
          encryptedApiKey = "",
          defaultModelId = modelId,
        )
      val adapter = providerRegistry.adapterFor(provider)
      val request =
        AIRequest(
          modelId = modelId,
          systemPrompt = systemPrompt,
          messages = history.map { AIMessage(it.role, it.content) },
          stream = true,
        )
      val cancellable =
        adapter.stream(
          config = transientConfig,
          plainApiKey = apiKey,
          request = request,
          handler =
            object : AIStreamHandler {
              override fun onToken(text: String) {
                trySend(AiStreamEvent.Token(text))
              }

              override fun onToolCall(toolCall: AIToolCallRequest) {
                trySend(AiStreamEvent.ToolCall(toolCall))
              }

              override fun onUsage(usage: AiTokenUsage) {
                trySend(AiStreamEvent.Usage(usage))
              }

              override fun onCompleted(finishReason: String) {
                trySend(AiStreamEvent.Completed)
              }

              override fun onError(error: ProviderError) {
                trySend(AiStreamEvent.Error(error.message))
              }
            },
          onCallCreated = { activeCall.set(it) },
        )
      activeCall.set(cancellable)
      activeCall.compareAndSet(cancellable, null)
      close()
      awaitClose { cancellable.cancel() }
    }.flowOn(dispatchers.io)
}
