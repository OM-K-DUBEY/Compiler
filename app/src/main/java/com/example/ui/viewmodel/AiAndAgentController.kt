package com.example.ui.viewmodel

import com.example.ai.AiStreamEvent
import com.example.ai.ChatTurn
import com.example.ai.CodingAgentEngine
import com.example.ai.OnlineAiClient
import com.example.ai.ProjectContextManager
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.AiMessageDao
import com.example.core.database.AiMessageEntity
import com.example.core.database.CheckpointDao
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.PreferencesRepository
import com.example.ui.state.AiUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiAndAgentController(
  private val scope: CoroutineScope,
  private val aiMessageDao: AiMessageDao,
  private val checkpointDao: CheckpointDao,
  private val onlineAiClient: OnlineAiClient,
  private val contextManager: ProjectContextManager,
  private val codingAgentEngine: CodingAgentEngine,
  private val preferencesRepository: PreferencesRepository,
  private val deviceInfoProvider: DeviceInfoProvider,
) {
  private val _state = MutableStateFlow(AiUiState())
  val state: StateFlow<AiUiState> = _state.asStateFlow()

  private var streamJob: Job? = null

  fun observeMessages(projectId: String?): Flow<List<AiMessageEntity>> =
    aiMessageDao.observeThread(projectId)

  fun observeCheckpoints(projectId: String): Flow<List<CheckpointEntity>> =
    checkpointDao.observeForProject(projectId)

  fun setAgentMode(enabled: Boolean) {
    _state.update { it.copy(agentModeEnabled = enabled) }
  }

  fun dismissConsentDialog() {
    _state.update { it.copy(needsOnlineConsentDialog = false, pendingPromptForConsent = null) }
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
          )
        )

        val apiKey = preferencesRepository.decryptApiKey(prefs.encryptedApiKey)
        if (apiKey.isBlank()) {
          _state.update {
            it.copy(
              lastError = "No API key configured for ${prefs.onlineProvider.displayName}. Configure a provider and API key in Settings or AI Providers.",
            )
          }
          return@launch
        }

        val contextSnapshot =
          project?.let {
            contextManager.buildContext(it, activeFilePath, recentBuildErrors)
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
              agentMode = _state.value.agentModeEnabled,
            )
          } else {
            "You are a concise, senior Android & systems engineer inside DevForge AI."
          }

        val history =
          aiMessageDao.observeThread(project?.id).first().takeLast(12).map {
            ChatTurn(it.role, it.content)
          }

        _state.update {
          it.copy(
            isGenerating = true,
            streamingReply = "",
            lastError = null,
            pendingPlan = null,
            pendingPreviews = emptyList(),
            statusBanner = null,
          )
        }

        val replyBuilder = StringBuilder()
        var usage = com.example.ai.AiTokenUsage()
        var encounteredError: String? = null

        onlineAiClient
          .streamChat(
            provider = prefs.onlineProvider,
            baseUrl = prefs.customEndpointUrl.ifBlank { prefs.onlineProvider.defaultBaseUrl },
            modelId = prefs.onlineModelId.ifBlank { prefs.onlineProvider.defaultModel },
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            history = history,
          )
          .collect { event ->
            when (event) {
              is AiStreamEvent.Token -> {
                replyBuilder.append(event.text)
                _state.update { it.copy(streamingReply = replyBuilder.toString()) }
              }
              is AiStreamEvent.Usage -> {
                usage = event.usage
                _state.update { it.copy(lastUsage = event.usage) }
              }
              is AiStreamEvent.Error -> {
                encounteredError = event.message
              }
              AiStreamEvent.Completed -> Unit
            }
          }

        val finalReply = replyBuilder.toString()
        if (finalReply.isNotBlank()) {
          val plan =
            if (_state.value.agentModeEnabled && project != null) {
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
              providerLabel = prefs.onlineProvider.displayName,
              modelId = prefs.onlineModelId,
              promptTokens = usage.promptTokens,
              completionTokens = usage.completionTokens,
              hasAgentPlan = plan != null,
              planJson = null,
              timestampMillis = System.currentTimeMillis(),
            )
          )

          _state.update {
            it.copy(
              isGenerating = false,
              streamingReply = "",
              lastError = encounteredError,
              pendingPlan = plan,
              pendingPreviews = previews,
            )
          }
        } else {
          _state.update {
            it.copy(
              isGenerating = false,
              streamingReply = "",
              lastError = encounteredError ?: "No response received",
            )
          }
        }
      }
  }

  fun cancelGeneration() {
    onlineAiClient.cancelActiveRequest()
    streamJob?.cancel()
    _state.update { it.copy(isGenerating = false, streamingReply = "", statusBanner = "Generation cancelled") }
  }

  fun approveAndApplyPendingPlan(project: ProjectEntity, onApplied: () -> Unit) {
    val plan = _state.value.pendingPlan ?: return
    scope.launch {
      runCatching {
        codingAgentEngine.applyPlanWithCheckpoint(project, plan)
      }.onSuccess { checkpoint ->
        _state.update {
          it.copy(
            pendingPlan = null,
            pendingPreviews = emptyList(),
            statusBanner = "Applied ${plan.patches.size} file change(s). Checkpoint created (${checkpoint.id.take(8)}).",
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
      it.copy(pendingPlan = null, pendingPreviews = emptyList(), statusBanner = "Proposed patch discarded")
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
    scope.launch { aiMessageDao.clearThread(projectId) }
  }
}
