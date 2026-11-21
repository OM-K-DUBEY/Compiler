package com.example.ui.viewmodel

import com.example.ai.AiStreamEvent
import com.example.ai.AiTokenUsage
import com.example.ai.CodingAgentEngine
import com.example.ai.OnlineAiClient
import com.example.ai.ProjectContextManager
import com.example.ai.provider.AIMessage
import com.example.ai.provider.AIModel
import com.example.ai.provider.AIModelRegistry
import com.example.ai.provider.AIProviderConfig
import com.example.ai.provider.AIProviderRegistry
import com.example.ai.provider.AIToolCallRequest
import com.example.ai.provider.AIToolCallResult
import com.example.ai.provider.AIToolDefinition
import com.example.ai.provider.CapabilitySupport
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.AiMessageDao
import com.example.core.database.AiMessageEntity
import com.example.core.database.CheckpointDao
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.PreferencesRepository
import com.example.mcp.McpClientManager
import com.example.mcp.McpToolDefinition
import com.example.mcp.PendingMcpToolApproval
import com.example.mcp.ToolRiskLevel
import com.example.ui.state.AiUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResolvedModelSelection(
  val provider: AIProviderConfig,
  val model: AIModel?,
  val modelId: String,
)

class AiAndAgentController(
  private val scope: CoroutineScope,
  private val aiMessageDao: AiMessageDao,
  private val checkpointDao: CheckpointDao,
  private val onlineAiClient: OnlineAiClient,
  private val providerRegistry: AIProviderRegistry,
  private val modelRegistry: AIModelRegistry,
  private val mcpClientManager: McpClientManager,
  private val contextManager: ProjectContextManager,
  private val codingAgentEngine: CodingAgentEngine,
  private val preferencesRepository: PreferencesRepository,
  private val deviceInfoProvider: DeviceInfoProvider,
) {
  private val _state = MutableStateFlow(AiUiState())
  val state: StateFlow<AiUiState> = _state.asStateFlow()

  private var streamJob: Job? = null
  private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

  fun observeMessages(projectId: String?, conversationId: String = _state.value.conversationId): Flow<List<AiMessageEntity>> =
    aiMessageDao.observeThread(projectId, conversationId)

  fun observeConversations(projectId: String?): Flow<List<String>> =
    aiMessageDao.observeConversations(projectId)

  fun observeCheckpoints(projectId: String): Flow<List<CheckpointEntity>> =
    checkpointDao.observeForProject(projectId)

  fun setAgentMode(enabled: Boolean) {
    _state.update { it.copy(agentModeEnabled = enabled) }
  }

  fun selectConversation(conversationId: String) {
    val clean = conversationId.trim().ifEmpty { "default" }
    _state.update { it.copy(conversationId = clean) }
  }

  fun toggleContextFile(relativePath: String) {
    _state.update { s ->
      val updated =
        if (relativePath in s.selectedContextFiles) s.selectedContextFiles - relativePath
        else s.selectedContextFiles + relativePath
      s.copy(selectedContextFiles = updated)
    }
  }

  fun clearSelectedContextFiles() {
    _state.update { it.copy(selectedContextFiles = emptySet()) }
  }

  fun togglePatchFileSelection(relativePath: String) {
    _state.update { s ->
      val updated =
        if (relativePath in s.selectedPatchPaths) s.selectedPatchPaths - relativePath
        else s.selectedPatchPaths + relativePath
      s.copy(selectedPatchPaths = updated)
    }
  }

  fun selectAllPatchFiles() {
    _state.update { s ->
      s.copy(selectedPatchPaths = s.pendingPlan?.patches?.map { it.relativePath }?.toSet().orEmpty())
    }
  }

  fun deselectAllPatchFiles() {
    _state.update { it.copy(selectedPatchPaths = emptySet()) }
  }

  fun dismissConsentDialog() {
    _state.update { it.copy(needsOnlineConsentDialog = false, pendingPromptForConsent = null) }
  }

  fun resolvePendingToolApproval(approved: Boolean) {
    _state.update { it.copy(pendingToolApproval = null) }
    pendingApprovalDeferred?.complete(approved)
    pendingApprovalDeferred = null
  }

  fun confirmConsentAndSend(
    prompt: String,
    project: ProjectEntity?,
    activeFilePath: String?,
    recentBuildErrors: String?,
    prefs: IdePreferences,
  ) {
    scope.launch {
      preferencesRepository.setAllowUploadingProjectFilesToOnlineAi(true)
      _state.update { it.copy(needsOnlineConsentDialog = false, pendingPromptForConsent = null) }
      sendMessage(
        prompt = prompt,
        project = project,
        activeFilePath = activeFilePath,
        recentBuildErrors = recentBuildErrors,
        prefs = prefs.copy(allowUploadingProjectFilesToOnlineAi = true),
      )
    }
  }

  suspend fun resolveActiveSelection(
    project: ProjectEntity?,
    prefs: IdePreferences,
    agentMode: Boolean,
  ): Result<ResolvedModelSelection> {
    val providers = providerRegistry.getProviders().filter { it.isEnabled }
    if (providers.isEmpty()) {
      return Result.failure(
        IllegalStateException("No enabled AI providers configured. Open AI Providers to add a provider and API key.")
      )
    }

    val convKey = "${project?.id ?: "global"}::${_state.value.conversationId}"
    val preferredCompositeId =
      modelRegistry.getConversationModel(convKey)
        ?: if (agentMode) prefs.agentModelCompositeId else prefs.defaultChatModelCompositeId
        ?: prefs.defaultChatModelCompositeId

    if (!preferredCompositeId.isNullOrBlank()) {
      val model = modelRegistry.getModelByCompositeId(preferredCompositeId)
      if (model != null) {
        val prov = providers.firstOrNull { it.id == model.providerId }
        if (prov != null) {
          return Result.success(ResolvedModelSelection(prov, model, model.modelId))
        }
      } else if (preferredCompositeId.contains("::")) {
        val (pId, mId) = preferredCompositeId.split("::", limit = 2)
        val prov = providers.firstOrNull { it.id == pId }
        if (prov != null && mId.isNotBlank()) {
          return Result.success(ResolvedModelSelection(prov, null, mId))
        }
      }
    }

    val defaultProv =
      providers.firstOrNull { it.id == prefs.defaultProviderId }
        ?: providers.firstOrNull { it.hasApiKey }
        ?: providers.first()
    val modelId =
      defaultProv.defaultModelId.ifBlank { defaultProv.providerType.defaultModel }
    if (modelId.isBlank()) {
      return Result.failure(
        IllegalStateException("No model selected for provider '${defaultProv.displayName}'. Discover or add a model first.")
      )
    }
    val modelMeta = modelRegistry.getModelByCompositeId(AIModel.makeCompositeId(defaultProv.id, modelId))
    return Result.success(ResolvedModelSelection(defaultProv, modelMeta, modelId))
  }

  fun sendMessage(
    prompt: String,
    project: ProjectEntity?,
    activeFilePath: String?,
    recentBuildErrors: String?,
    prefs: IdePreferences,
  ) {
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty() || _state.value.isGenerating) return

    if (project != null && !prefs.allowUploadingProjectFilesToOnlineAi) {
      _state.update {
        it.copy(
          needsOnlineConsentDialog = true,
          pendingPromptForConsent = cleanPrompt,
        )
      }
      return
    }

    if (!deviceInfoProvider.isNetworkConnected()) {
      _state.update {
        it.copy(lastError = "No active network connection. Online AI providers require internet access.")
      }
      return
    }

    streamJob?.cancel()
    streamJob =
      scope.launch {
        val convId = _state.value.conversationId
        val agentMode = _state.value.agentModeEnabled
        val resolvedRes = resolveActiveSelection(project, prefs, agentMode)
        if (resolvedRes.isFailure) {
          _state.update {
            it.copy(lastError = resolvedRes.exceptionOrNull()?.message ?: "No AI provider available")
          }
          return@launch
        }
        val selection = resolvedRes.getOrThrow()

        if (selection.provider.authMethod != com.example.ai.provider.AuthMethod.NONE && !selection.provider.hasApiKey) {
          _state.update {
            it.copy(
              lastError = "No API key configured for ${selection.provider.displayName}. Configure it in AI Providers.",
            )
          }
          return@launch
        }

        val now = System.currentTimeMillis()
        aiMessageDao.insert(
          AiMessageEntity(
            projectId = project?.id,
            role = "user",
            content = cleanPrompt,
            providerLabel = "User",
            modelId = "",
            promptTokens = 0,
            completionTokens = 0,
            hasAgentPlan = false,
            planJson = null,
            timestampMillis = now,
            providerId = selection.provider.id,
            conversationId = convId,
          )
        )

        val contextSnapshot =
          project?.let {
            contextManager.buildContext(
              project = it,
              activeFilePath = activeFilePath,
              recentBuildErrors = recentBuildErrors,
              selectedFiles = _state.value.selectedContextFiles,
            )
          }

        val systemPrompt =
          if (contextSnapshot != null) {
            _state.update {
              it.copy(
                contextFileCount = contextSnapshot.includedFiles.size,
                contextEstimatedTokens = contextSnapshot.estimatedTokens,
              )
            }
            contextManager.formatSystemPrompt(
              snapshot = contextSnapshot,
              recentBuildErrors = recentBuildErrors,
              agentMode = agentMode,
            )
          } else {
            "You are a concise, senior Android & systems engineer inside DevForge AI."
          }

        // Check known context window before dispatching request
        val ctxWindow = selection.model?.contextWindow
        if (ctxWindow != null && contextSnapshot != null && contextSnapshot.estimatedTokens > ctxWindow) {
          _state.update {
            it.copy(
              statusBanner =
                "Warning: Estimated context (~${contextSnapshot.estimatedTokens} tokens) exceeds known window ($ctxWindow) for ${selection.modelId}. Select fewer context files if the provider rejects the request.",
            )
          }
        }

        val permittedMcp =
          if (agentMode) mcpClientManager.getPermittedAiTools() else emptyList()
        val adapter = providerRegistry.adapterFor(selection.provider.providerType)
        val modelSupportsTools =
          selection.model?.toolCallingCapability != CapabilitySupport.UNSUPPORTED &&
            adapter.capabilities.supportsToolCalling

        val toolsForAi: List<AIToolDefinition> =
          if (modelSupportsTools) permittedMcp.map { it.first } else emptyList()
        val toolLookup: Map<String, Pair<String, McpToolDefinition>> =
          permittedMcp.associate { (aiDef, target) -> aiDef.name to target }

        val historyMessages =
          aiMessageDao.observeThread(project?.id, convId).first().takeLast(14).map {
            AIMessage(it.role, it.content)
          }.toMutableList()

        _state.update {
          it.copy(
            isGenerating = true,
            streamingReply = "",
            lastError = null,
            pendingPlan = null,
            pendingPreviews = emptyList(),
            selectedPatchPaths = emptySet(),
            activeMcpToolCount = toolsForAi.size,
            statusBanner = null,
          )
        }

        executeTurnLoop(
          selection = selection,
          prefs = prefs,
          project = project,
          convId = convId,
          systemPrompt = systemPrompt,
          messages = historyMessages,
          tools = toolsForAi,
          toolLookup = toolLookup,
          agentMode = agentMode,
          allowFallback = prefs.autoFallbackOnFailure,
        )
      }
  }

  private suspend fun executeTurnLoop(
    selection: ResolvedModelSelection,
    prefs: IdePreferences,
    project: ProjectEntity?,
    convId: String,
    systemPrompt: String,
    messages: MutableList<AIMessage>,
    tools: List<AIToolDefinition>,
    toolLookup: Map<String, Pair<String, McpToolDefinition>>,
    agentMode: Boolean,
    allowFallback: Boolean,
  ) {
    var activeSelection = selection
    val finalTextBuilder = StringBuilder()
    var totalUsage = AiTokenUsage()
    var lastError: String? = null
    var toolRounds = 0

    while (toolRounds <= 5) {
      val turnText = StringBuilder()
      val turnToolCalls = mutableListOf<AIToolCallRequest>()
      var turnError: String? = null

      onlineAiClient
        .streamWithProvider(
          config = activeSelection.provider,
          modelId = activeSelection.modelId,
          systemPrompt = systemPrompt,
          messages = messages,
          tools = tools,
        )
        .collect { event ->
          when (event) {
            is AiStreamEvent.Token -> {
              turnText.append(event.text)
              _state.update { it.copy(streamingReply = finalTextBuilder.toString() + turnText.toString()) }
            }
            is AiStreamEvent.ToolCall -> turnToolCalls += event.call
            is AiStreamEvent.Usage -> {
              totalUsage =
                AiTokenUsage(
                  promptTokens = totalUsage.promptTokens + event.usage.promptTokens,
                  completionTokens = totalUsage.completionTokens + event.usage.completionTokens,
                )
              _state.update { it.copy(lastUsage = totalUsage) }
            }
            is AiStreamEvent.Error -> turnError = event.message
            AiStreamEvent.Completed -> Unit
          }
        }

      if (turnError != null && turnText.isEmpty() && turnToolCalls.isEmpty()) {
        // Explicit user-configured fallback ONLY when enabled
        if (allowFallback && toolRounds == 0) {
          val fallback =
            providerRegistry.getProviders().firstOrNull {
              it.isEnabled && it.hasApiKey && it.id != activeSelection.provider.id
            }
          if (fallback != null) {
            val fbModel = fallback.defaultModelId.ifBlank { fallback.providerType.defaultModel }
            if (fbModel.isNotBlank()) {
              _state.update {
                it.copy(
                  statusBanner =
                    "Primary provider '${activeSelection.provider.displayName}' failed ($turnError). Falling back to user-configured backup '${fallback.displayName}' ($fbModel).",
                )
              }
              activeSelection = ResolvedModelSelection(fallback, null, fbModel)
              continue
            }
          }
        }
        lastError = turnError
        break
      }

      if (turnText.isNotEmpty()) {
        if (finalTextBuilder.isNotEmpty()) finalTextBuilder.append("\n\n")
        finalTextBuilder.append(turnText)
      }

      if (turnToolCalls.isEmpty()) {
        lastError = turnError
        break
      }

      // Append assistant tool-call message
      messages += AIMessage(role = "assistant", content = turnText.toString(), toolCalls = turnToolCalls)

      for (call in turnToolCalls) {
        val target = toolLookup[call.name]
        if (target == null) {
          messages +=
            AIMessage(
              role = "tool",
              content = "",
              toolResult =
                AIToolCallResult(
                  callId = call.id,
                  toolName = call.name,
                  outputText = "Tool '${call.name}' is not registered or not allowed.",
                  isError = true,
                ),
            )
          continue
        }

        val (serverId, mcpTool) = target
        val validation = mcpClientManager.validateToolCall(serverId, mcpTool.name, call.argumentsJson)
        if (!validation.isValid) {
          messages +=
            AIMessage(
              role = "tool",
              content = "",
              toolResult =
                AIToolCallResult(
                  callId = call.id,
                  toolName = call.name,
                  outputText = validation.errorMessage ?: "Permission denied.",
                  isError = true,
                ),
            )
          continue
        }

        var approved = true
        if (validation.riskLevel == ToolRiskLevel.SENSITIVE) {
          val deferred = CompletableDeferred<Boolean>()
          pendingApprovalDeferred = deferred
          _state.update {
            it.copy(
              pendingToolApproval =
                PendingMcpToolApproval(
                  callId = call.id,
                  serverId = serverId,
                  serverName = mcpTool.serverName,
                  toolName = mcpTool.name,
                  argumentsJson = call.argumentsJson,
                  riskReason = validation.riskReason ?: "Sensitive MCP operation",
                ),
            )
          }
          approved = deferred.await()
        }

        if (!approved) {
          messages +=
            AIMessage(
              role = "tool",
              content = "",
              toolResult =
                AIToolCallResult(
                  callId = call.id,
                  toolName = call.name,
                  outputText = "Execution denied by user.",
                  isError = true,
                ),
            )
          continue
        }

        val execRes =
          mcpClientManager.executeTool(
            serverId = serverId,
            toolName = mcpTool.name,
            argumentsJson = call.argumentsJson,
            approvedSensitiveAction = approved,
          )
        val resultText =
          execRes.fold(
            onSuccess = { it.wrappedUntrustedText },
            onFailure = { "MCP tool error: ${it.message}" },
          )
        messages +=
          AIMessage(
            role = "tool",
            content = "",
            toolResult =
              AIToolCallResult(
                callId = call.id,
                toolName = call.name,
                outputText = resultText,
                isError = execRes.isFailure || execRes.getOrNull()?.isError == true,
              ),
          )
      }
      toolRounds++
    }

    val finalReply = finalTextBuilder.toString().trim()
    if (finalReply.isNotBlank()) {
      val plan =
        if (agentMode && project != null) {
          codingAgentEngine.extractAgentPlan(finalReply)
        } else {
          null
        }
      val previews =
        if (plan != null && project != null) {
          codingAgentEngine.buildPatchPreviews(project, plan)
        } else {
          emptyList()
        }

      aiMessageDao.insert(
        AiMessageEntity(
          projectId = project?.id,
          role = "assistant",
          content = finalReply,
          providerLabel = activeSelection.provider.displayName,
          modelId = activeSelection.modelId,
          promptTokens = totalUsage.promptTokens,
          completionTokens = totalUsage.completionTokens,
          hasAgentPlan = plan != null,
          planJson = null,
          timestampMillis = System.currentTimeMillis(),
          providerId = activeSelection.provider.id,
          conversationId = convId,
        )
      )

      _state.update {
        it.copy(
          isGenerating = false,
          streamingReply = "",
          lastError = lastError,
          pendingPlan = plan,
          pendingPreviews = previews,
          selectedPatchPaths = plan?.patches?.map { p -> p.relativePath }?.toSet().orEmpty(),
        )
      }
    } else {
      _state.update {
        it.copy(
          isGenerating = false,
          streamingReply = "",
          lastError = lastError ?: "No response received from ${activeSelection.provider.displayName}.",
        )
      }
    }
  }

  fun cancelGeneration() {
    onlineAiClient.cancelActiveRequest()
    pendingApprovalDeferred?.complete(false)
    pendingApprovalDeferred = null
    streamJob?.cancel()
    _state.update {
      it.copy(
        isGenerating = false,
        streamingReply = "",
        pendingToolApproval = null,
        statusBanner = "Generation cancelled",
      )
    }
  }

  fun approveAndApplyPendingPlan(project: ProjectEntity, applyAll: Boolean, onApplied: () -> Unit) {
    val plan = _state.value.pendingPlan ?: return
    val selected =
      if (applyAll) null
      else _state.value.selectedPatchPaths
    if (selected != null && selected.isEmpty()) {
      _state.update { it.copy(lastError = "Select at least one file to apply.") }
      return
    }
    scope.launch {
      runCatching {
        codingAgentEngine.applyPlanWithDiagnostics(project, plan, selected)
      }.onSuccess { outcome ->
        val diagSummary =
          if (outcome.errorCount > 0) " (${outcome.errorCount} static error(s) remain — rollback available)"
          else " (0 static errors)"
        _state.update {
          it.copy(
            pendingPlan = null,
            pendingPreviews = emptyList(),
            selectedPatchPaths = emptySet(),
            statusBanner =
              "Applied ${outcome.appliedCount} file change(s). Checkpoint ${outcome.checkpoint.id.take(8)} saved.$diagSummary",
          )
        }
        onApplied()
      }.onFailure { err ->
        _state.update { it.copy(lastError = "Failed to apply patch: ${err.message}") }
      }
    }
  }

  fun discardPendingPlan() {
    _state.update {
      it.copy(
        pendingPlan = null,
        pendingPreviews = emptyList(),
        selectedPatchPaths = emptySet(),
        statusBanner = "Proposed patch discarded",
      )
    }
  }

  fun rollbackCheckpoint(project: ProjectEntity, checkpointId: String, onRolledBack: () -> Unit) {
    scope.launch {
      val ok = codingAgentEngine.rollbackCheckpoint(project, checkpointId)
      _state.update {
        it.copy(
          statusBanner = if (ok) "Rolled back checkpoint ${checkpointId.take(8)}" else "Rollback failed",
        )
      }
      if (ok) onRolledBack()
    }
  }

  fun clearChat(projectId: String?) {
    scope.launch { aiMessageDao.clearThread(projectId, _state.value.conversationId) }
  }
}
