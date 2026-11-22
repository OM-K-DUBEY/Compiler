package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DevForgeApp
import com.example.ai.provider.AIModel
import com.example.ai.provider.AIProviderConfig
import com.example.ai.provider.AuthMethod
import com.example.ai.provider.CapabilitySupport
import com.example.ai.provider.ProviderTestResult
import com.example.compiler.BuildExecutionResult
import com.example.compiler.BuildSystemType
import com.example.compiler.BuildVariant
import com.example.compiler.BuildWorker
import com.example.compiler.ProjectDetector
import com.example.core.common.DeviceHardwareSnapshot
import com.example.core.database.AiMessageEntity
import com.example.core.database.BuildHistoryEntity
import com.example.core.database.BuildProtectionReportEntity
import com.example.core.database.CheckpointEntity
import com.example.core.database.MappingVaultEntity
import com.example.core.database.McpAuditLogEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.OnlineProviderType
import com.example.core.security.SigningKeyInfo
import com.example.core.storage.ProjectTemplateType
import com.example.mcp.McpAuthType
import com.example.mcp.McpServerConfig
import com.example.mcp.McpToolDefinition
import com.example.mcp.McpTransportType
import com.example.protection.ProtectionConfig
import com.example.protection.ProtectionPlan
import com.example.protection.ProtectionProfileMode
import com.example.protection.SavedProtectionProfile
import com.example.sdk.SdkPackageSpec
import com.example.sdk.SdkPackageUiState
import com.example.terminal.TerminalLine
import com.example.ui.state.AiUiState
import com.example.ui.state.BuildUiState
import com.example.ui.state.DecryptedMappingPreview
import com.example.ui.state.EditorUiState
import com.example.ui.state.OperationFeedback
import com.example.ui.state.ProjectsUiState
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainIdeViewModel(application: Application) : AndroidViewModel(application) {
  private val container = (application as DevForgeApp).container

  val preferences: StateFlow<IdePreferences> =
    container.preferencesRepository.preferences.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = IdePreferences(),
    )

  val projects: StateFlow<List<ProjectEntity>> =
    container.workspaceManager.observeProjects().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val activeProject: StateFlow<ProjectEntity?> =
    combine(projects, preferences) { list, prefs ->
      list.firstOrNull { it.id == prefs.activeProjectId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val editorController =
    EditorController(
      scope = viewModelScope,
      workspaceManager = container.workspaceManager,
      editorTabDao = container.database.editorTabDao(),
    )

  val editorState: StateFlow<EditorUiState> = editorController.state

  val aiController =
    AiAndAgentController(
      scope = viewModelScope,
      aiMessageDao = container.database.aiMessageDao(),
      checkpointDao = container.database.checkpointDao(),
      onlineAiClient = container.onlineAiClient,
      providerRegistry = container.aiProviderRegistry,
      modelRegistry = container.aiModelRegistry,
      mcpClientManager = container.mcpClientManager,
      contextManager = container.projectContextManager,
      codingAgentEngine = container.codingAgentEngine,
      preferencesRepository = container.preferencesRepository,
      deviceInfoProvider = container.deviceInfoProvider,
    )

  val aiState: StateFlow<AiUiState> = aiController.state

  val aiMessages: StateFlow<List<AiMessageEntity>> =
    combine(activeProject, aiState) { proj, state -> proj?.id to state.conversationId }
      .flatMapLatest { (projectId, convId) -> aiController.observeMessages(projectId, convId) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val aiConversations: StateFlow<List<String>> =
    activeProject
      .flatMapLatest { proj -> aiController.observeConversations(proj?.id) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("default"))

  val checkpoints: StateFlow<List<CheckpointEntity>> =
    activeProject
      .flatMapLatest { proj ->
        if (proj == null) flowOf(emptyList()) else aiController.observeCheckpoints(proj.id)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // Multi-provider AI & Model Discovery
  val aiProviders: StateFlow<List<AIProviderConfig>> =
    container.aiProviderRegistry.observeProviders()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val aiModels: StateFlow<List<AIModel>> =
    container.aiModelRegistry.observeAllModels()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val conversationModels: StateFlow<Map<String, String>> = container.aiModelRegistry.conversationModels

  private val _providerFeedback = MutableStateFlow<OperationFeedback?>(null)
  val providerFeedback: StateFlow<OperationFeedback?> = _providerFeedback.asStateFlow()

  private val _providerTestResults = MutableStateFlow<Map<String, ProviderTestResult>>(emptyMap())
  val providerTestResults: StateFlow<Map<String, ProviderTestResult>> = _providerTestResults.asStateFlow()

  private val _modelFeedback = MutableStateFlow<OperationFeedback?>(null)
  val modelFeedback: StateFlow<OperationFeedback?> = _modelFeedback.asStateFlow()

  // MCP Server Manager
  val mcpServers: StateFlow<List<McpServerConfig>> =
    container.mcpClientManager.observeServers()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val mcpDiscoveredTools: StateFlow<Map<String, List<McpToolDefinition>>> =
    container.mcpClientManager.discoveredTools

  val mcpServerLogs: StateFlow<Map<String, List<String>>> =
    container.mcpClientManager.serverLogs

  val mcpAuditLogs: StateFlow<List<McpAuditLogEntity>> =
    container.mcpClientManager.observeAuditLogs()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _mcpFeedback = MutableStateFlow<OperationFeedback?>(null)
  val mcpFeedback: StateFlow<OperationFeedback?> = _mcpFeedback.asStateFlow()

  // Code Protection & Mapping Vault
  val activeProtectionProfile: StateFlow<SavedProtectionProfile?> =
    activeProject
      .flatMapLatest { proj ->
        if (proj == null) flowOf(null)
        else container.protectionPipeline.profileManager.observeProfile(proj.id)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val protectionReports: StateFlow<List<BuildProtectionReportEntity>> =
    activeProject
      .flatMapLatest { proj ->
        if (proj == null) flowOf(emptyList())
        else container.protectionPipeline.observeReports(proj.id)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val mappingVaultEntries: StateFlow<List<MappingVaultEntity>> =
    container.symbolAndMappingVault.observeAll()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _protectionPlan = MutableStateFlow<ProtectionPlan?>(null)
  val protectionPlan: StateFlow<ProtectionPlan?> = _protectionPlan.asStateFlow()

  private val _protectionFeedback = MutableStateFlow<OperationFeedback?>(null)
  val protectionFeedback: StateFlow<OperationFeedback?> = _protectionFeedback.asStateFlow()

  private val _decryptedMappingPreview = MutableStateFlow<DecryptedMappingPreview?>(null)
  val decryptedMappingPreview: StateFlow<DecryptedMappingPreview?> = _decryptedMappingPreview.asStateFlow()

  // Builds & SDKs
  val buildHistory: StateFlow<List<BuildHistoryEntity>> =
    container.buildManager.observeHistory(null).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val isBuilding: StateFlow<Boolean> = container.buildManager.isBuilding
  val liveBuildLogs: StateFlow<String> = container.buildManager.liveLogs

  private val _buildState = MutableStateFlow(BuildUiState())
  val buildState: StateFlow<BuildUiState> = _buildState.asStateFlow()

  private val _projectsUiState = MutableStateFlow(ProjectsUiState())
  val projectsUiState: StateFlow<ProjectsUiState> = _projectsUiState.asStateFlow()

  val sdkPackages: StateFlow<List<SdkPackageUiState>> =
    preferences
      .flatMapLatest { prefs -> container.sdkManagerRepository.observePackages(prefs.customToolchainPath) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _sdkReachability = MutableStateFlow<Map<String, String>>(emptyMap())
  val sdkReachability: StateFlow<Map<String, String>> = _sdkReachability.asStateFlow()

  private val _sdkStorageBytes = MutableStateFlow(0L)
  val sdkStorageBytes: StateFlow<Long> = _sdkStorageBytes.asStateFlow()

  val terminalLines: StateFlow<List<TerminalLine>> = container.terminalSessionManager.lines
  val terminalWorkingDir: StateFlow<File> = container.terminalSessionManager.workingDir
  val terminalRunning: StateFlow<Boolean> = container.terminalSessionManager.isRunning
  val terminalHistory: StateFlow<List<String>> = container.terminalSessionManager.history
  val terminalEnv: StateFlow<Map<String, String>> = container.terminalSessionManager.envVars

  private val _hardwareSnapshot = MutableStateFlow(container.deviceInfoProvider.snapshot())
  val hardwareSnapshot: StateFlow<DeviceHardwareSnapshot> = _hardwareSnapshot.asStateFlow()

  private val _signingKeyInfo = MutableStateFlow<SigningKeyInfo?>(null)
  val signingKeyInfo: StateFlow<SigningKeyInfo?> = _signingKeyInfo.asStateFlow()

  init {
    viewModelScope.launch {
      container.aiProviderRegistry.migrateLegacyPreferencesIfNeeded()
      _sdkStorageBytes.value = container.sdkManagerRepository.calculateTotalSdkStorageBytes()
    }
    viewModelScope.launch {
      var loadedProjectId: String? = null
      activeProject.collect { proj ->
        if (proj?.id != loadedProjectId) {
          loadedProjectId = proj?.id
          _buildState.update { it.copy(lastResult = null, webPreviewEntryPath = null) }
          _protectionFeedback.value = null
          _decryptedMappingPreview.value = null
          editorController.loadProject(proj)
          refreshBuildDetection(proj)
          refreshProtectionPlan(proj)
          if (proj != null) {
            container.terminalSessionManager.setWorkingDirectory(File(proj.rootPath))
            loadBackups(proj.id)
          }
        }
      }
    }
    viewModelScope.launch {
      preferences.collect { prefs ->
        container.terminalSessionManager.setCustomPathPrefix(prefs.customToolchainPath)
        editorController.setAutoSaveEnabled(prefs.autoSaveEnabled)
      }
    }
  }

  fun refreshHardwareSnapshot() {
    _hardwareSnapshot.value = container.deviceInfoProvider.snapshot()
    _sdkStorageBytes.value = container.sdkManagerRepository.calculateTotalSdkStorageBytes()
  }

  fun openProject(projectId: String) {
    viewModelScope.launch {
      container.workspaceManager.markOpened(projectId)
      container.preferencesRepository.setActiveProjectId(projectId)
    }
  }

  fun createProject(name: String, packageName: String, template: ProjectTemplateType) {
    viewModelScope.launch {
      _projectsUiState.update { it.copy(isLoading = true, errorMessage = null) }
      runCatching {
        val created = container.workspaceManager.createProject(name, packageName, template)
        container.preferencesRepository.setActiveProjectId(created.id)
        created
      }.onSuccess { created ->
        _projectsUiState.update {
          it.copy(isLoading = false, statusMessage = "Created project '${created.name}'")
        }
      }.onFailure { err ->
        _projectsUiState.update {
          it.copy(isLoading = false, errorMessage = err.message ?: "Failed to create project")
        }
      }
    }
  }

  fun importProjectZip(uri: Uri, displayNameHint: String) {
    viewModelScope.launch {
      _projectsUiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
      runCatching {
        val input =
          getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open selected ZIP archive")
        val extracted = input.use { container.zipArchiveManager.importZip(it) }
        val detected = ProjectDetector.detectBuildSystems(extracted.workspaceDir)
        val projectType = detected.firstOrNull()?.name ?: "IMPORTED"
        val cleanName =
          displayNameHint.substringBeforeLast('.').ifBlank { "Imported Project" }
        val entity =
          container.workspaceManager.registerImportedProject(
            name = cleanName,
            rootDir = extracted.workspaceDir,
            detectedType = projectType,
          )
        container.preferencesRepository.setActiveProjectId(entity.id)
        entity to extracted.extractedFiles
      }.onSuccess { (entity, count) ->
        _projectsUiState.update {
          it.copy(
            isLoading = false,
            statusMessage = "Imported '${entity.name}' ($count files verified)",
          )
        }
      }.onFailure { err ->
        _projectsUiState.update {
          it.copy(
            isLoading = false,
            errorMessage = "ZIP import blocked: ${err.message}",
          )
        }
      }
    }
  }

  fun exportProjectZip(project: ProjectEntity, destUri: Uri) {
    viewModelScope.launch {
      runCatching {
        val out =
          getApplication<Application>().contentResolver.openOutputStream(destUri)
            ?: throw IllegalStateException("Cannot open output destination")
        out.use { container.zipArchiveManager.exportProjectToStream(File(project.rootPath), it) }
      }.onSuccess { count ->
        _projectsUiState.update {
          it.copy(statusMessage = "Exported ${project.name} ($count files) to ZIP")
        }
      }.onFailure { err ->
        _projectsUiState.update { it.copy(errorMessage = "Export failed: ${err.message}") }
      }
    }
  }

  fun renameProject(projectId: String, newName: String) {
    viewModelScope.launch {
      container.workspaceManager.renameProject(projectId, newName)
      _projectsUiState.update { it.copy(statusMessage = "Renamed project to '$newName'") }
    }
  }

  fun duplicateProject(projectId: String) {
    viewModelScope.launch {
      val copy = container.workspaceManager.duplicateProject(projectId)
      if (copy != null) {
        _projectsUiState.update { it.copy(statusMessage = "Duplicated as '${copy.name}'") }
      }
    }
  }

  fun deleteProject(projectId: String) {
    viewModelScope.launch {
      container.workspaceManager.deleteProject(projectId)
      container.database.editorTabDao().deleteForProject(projectId)
      if (preferences.value.activeProjectId == projectId) {
        container.preferencesRepository.setActiveProjectId(null)
      }
      _projectsUiState.update { it.copy(statusMessage = "Project deleted") }
    }
  }

  fun createBackup(project: ProjectEntity) {
    viewModelScope.launch {
      runCatching {
        container.zipArchiveManager.createBackup(project.id, File(project.rootPath))
      }.onSuccess { rec ->
        loadBackups(project.id)
        _projectsUiState.update {
          it.copy(statusMessage = "Backup created (${rec.sha256.take(12)}...)")
        }
      }.onFailure { err ->
        _projectsUiState.update { it.copy(errorMessage = "Backup failed: ${err.message}") }
      }
    }
  }

  fun restoreBackup(project: ProjectEntity, backupFile: File) {
    viewModelScope.launch {
      runCatching {
        container.zipArchiveManager.restoreBackup(backupFile, File(project.rootPath))
      }.onSuccess { files ->
        container.workspaceManager.refreshMetadata(project.id)
        editorController.refreshTree()
        refreshBuildDetection(project)
        refreshProtectionPlan(project)
        _projectsUiState.update {
          it.copy(statusMessage = "Restored backup ($files files verified via SHA-256)")
        }
      }.onFailure { err ->
        _projectsUiState.update { it.copy(errorMessage = "Restore failed: ${err.message}") }
      }
    }
  }

  fun loadBackups(projectId: String) {
    viewModelScope.launch {
      val backups = container.zipArchiveManager.listBackups(projectId)
      _projectsUiState.update { it.copy(backupsForSelected = backups) }
    }
  }

  fun clearProjectBanner() {
    _projectsUiState.update { it.copy(statusMessage = null, errorMessage = null) }
  }

  fun refreshBuildDetection(project: ProjectEntity? = activeProject.value) {
    val systems =
      if (project != null) ProjectDetector.detectBuildSystems(File(project.rootPath))
      else emptyList()
    _buildState.update {
      val selected = if (it.selectedSystem in systems) it.selectedSystem else systems.firstOrNull()
      it.copy(detectedSystems = systems, selectedSystem = selected)
    }
  }

  fun selectBuildSystem(system: BuildSystemType) {
    _buildState.update { it.copy(selectedSystem = system) }
  }

  fun selectBuildVariant(variant: BuildVariant) {
    _buildState.update { it.copy(selectedVariant = variant) }
    refreshProtectionPlan()
  }

  fun setSignRelease(enabled: Boolean) {
    _buildState.update { it.copy(signRelease = enabled) }
  }

  fun runBuild() {
    val project = activeProject.value ?: return
    val system = _buildState.value.selectedSystem ?: ProjectDetector.detectBuildSystems(File(project.rootPath)).firstOrNull() ?: return
    viewModelScope.launch {
      val res: BuildExecutionResult =
        container.buildManager.runBuild(
          project = project,
          buildSystem = system,
          variant = _buildState.value.selectedVariant,
          customToolchainPath = preferences.value.customToolchainPath,
          signReleaseArtifact = _buildState.value.signRelease,
        )
      _buildState.update { it.copy(lastResult = res) }
      refreshProtectionPlan(project)
    }
  }

  fun enqueueBackgroundBuild() {
    val project = activeProject.value ?: return
    val system = _buildState.value.selectedSystem ?: ProjectDetector.detectBuildSystems(File(project.rootPath)).firstOrNull() ?: return
    val workId =
      BuildWorker.enqueue(
        context = getApplication(),
        projectId = project.id,
        buildSystem = system,
        variant = _buildState.value.selectedVariant,
        signRelease = _buildState.value.signRelease,
      )
    _projectsUiState.update {
      it.copy(statusMessage = "Queued background WorkManager build (${workId.toString().take(8)})")
    }
  }

  fun cancelBuild() {
    container.buildManager.cancelCurrentBuild()
  }

  fun openWebPreview() {
    val project = activeProject.value ?: return
    val distIndex = File(project.rootPath, "build/web/dist/index.html")
    val srcIndex = File(project.rootPath, "index.html")
    val target = if (distIndex.exists()) distIndex else srcIndex
    if (target.exists()) {
      _buildState.update { it.copy(webPreviewEntryPath = target.absolutePath) }
    }
  }

  fun closeWebPreview() {
    _buildState.update { it.copy(webPreviewEntryPath = null) }
  }

  fun exportArtifactToUri(sourcePath: String, destUri: Uri) {
    viewModelScope.launch {
      runCatching {
        val src = File(sourcePath)
        val out =
          getApplication<Application>().contentResolver.openOutputStream(destUri)
            ?: throw IllegalStateException("Cannot open export destination")
        out.use { output -> src.inputStream().use { it.copyTo(output) } }
      }.onSuccess {
        _projectsUiState.update { it.copy(statusMessage = "Artifact exported successfully") }
      }.onFailure { err ->
        _projectsUiState.update { it.copy(errorMessage = "Artifact export failed: ${err.message}") }
      }
    }
  }

  fun openFileAtLine(relativePath: String, line: Int) {
    editorController.openFileAtLine(relativePath, line)
  }

  // Terminal
  fun executeTerminalCommand(command: String) {
    viewModelScope.launch { container.terminalSessionManager.execute(command) }
  }

  fun requiresScriptConfirmation(command: String): Boolean =
    container.terminalSessionManager.requiresScriptConfirmation(command)

  fun cancelTerminalCommand() = container.terminalSessionManager.cancelRunningCommand()
  fun clearTerminal() = container.terminalSessionManager.clearOutput()

  // SDK Manager
  fun installSdkPackage(spec: SdkPackageSpec) {
    viewModelScope.launch {
      container.sdkManagerRepository.installPackage(spec)
      _sdkStorageBytes.value = container.sdkManagerRepository.calculateTotalSdkStorageBytes()
    }
  }

  fun cancelSdkDownload(packageId: String) = container.sdkManagerRepository.cancelDownload(packageId)

  fun uninstallSdkPackage(packageId: String) {
    viewModelScope.launch {
      container.sdkManagerRepository.uninstallPackage(packageId)
      _sdkStorageBytes.value = container.sdkManagerRepository.calculateTotalSdkStorageBytes()
    }
  }

  fun checkSdkPackageReachability(spec: SdkPackageSpec) {
    viewModelScope.launch {
      _sdkReachability.update { it + (spec.id to "Checking...") }
      val res = container.sdkManagerRepository.checkRemotePackageReachability(spec)
      val msg = res.fold(onSuccess = { it }, onFailure = { "Unreachable: ${it.message}" })
      _sdkReachability.update { it + (spec.id to msg) }
    }
  }

  // AI & Agent
  fun listSelectableContextFiles(): List<String> {
    return editorState.value.fileTree
      .filter { !it.isDirectory }
      .map { it.relativePath }
  }

  fun sendAiMessage(prompt: String, includeBuildErrors: Boolean = false) {
    val project = activeProject.value
    val activeFile = editorState.value.activeTab?.relativePath
    val errContext =
      if (includeBuildErrors) _buildState.value.lastResult?.logs ?: liveBuildLogs.value
      else null
    aiController.sendMessage(prompt, project, activeFile, errContext, preferences.value)
  }

  fun confirmAiOnlineConsent(prompt: String) {
    aiController.confirmConsentAndSend(
      prompt = prompt,
      project = activeProject.value,
      activeFilePath = editorState.value.activeTab?.relativePath,
      recentBuildErrors = _buildState.value.lastResult?.logs,
      prefs = preferences.value,
    )
  }

  fun applyPendingAiPlan(applyAll: Boolean = true) {
    val project = activeProject.value ?: return
    aiController.approveAndApplyPendingPlan(project, applyAll) {
      editorController.refreshTree()
      refreshBuildDetection(project)
      refreshProtectionPlan(project)
    }
  }

  fun rollbackCheckpoint(checkpointId: String) {
    val project = activeProject.value ?: return
    aiController.rollbackCheckpoint(project, checkpointId) {
      editorController.refreshTree()
      refreshBuildDetection(project)
      refreshProtectionPlan(project)
    }
  }

  // AI Provider & Model Management
  fun saveAiProvider(
    id: String? = null,
    displayName: String,
    providerType: OnlineProviderType,
    baseUrl: String,
    newPlainApiKey: String? = null,
    clearApiKey: Boolean = false,
    apiVersion: String = "",
    authMethod: AuthMethod = AIProviderConfig.defaultAuthFor(providerType),
    customAuthHeaderName: String = "",
    defaultModelId: String = providerType.defaultModel,
    requestTimeoutSeconds: Int = 60,
    maxOutputTokens: Int = 4096,
    customHeaders: Map<String, String> = emptyMap(),
    supportsStreaming: Boolean = true,
    isEnabled: Boolean = true,
  ) {
    viewModelScope.launch {
      val res =
        container.aiProviderRegistry.saveProvider(
          id = id,
          displayName = displayName,
          providerType = providerType,
          baseUrl = baseUrl,
          newPlainApiKey = newPlainApiKey,
          clearApiKey = clearApiKey,
          apiVersion = apiVersion,
          authMethod = authMethod,
          customAuthHeaderName = customAuthHeaderName,
          defaultModelId = defaultModelId,
          requestTimeoutSeconds = requestTimeoutSeconds,
          maxOutputTokens = maxOutputTokens,
          customHeaders = customHeaders,
          supportsStreaming = supportsStreaming,
          isEnabled = isEnabled,
        )
      res.onSuccess { saved ->
        if (preferences.value.defaultProviderId.isNullOrBlank()) {
          val comp = saved.defaultModelId.takeIf { it.isNotBlank() }?.let { AIModel.makeCompositeId(saved.id, it) }
          container.preferencesRepository.setDefaultProviderAndModels(
            defaultProviderId = saved.id,
            defaultChatModelCompositeId = comp,
            agentModelCompositeId = comp,
          )
        }
        val warning = saved.lastConnectionMessage.takeIf { it.contains("Security warning", ignoreCase = true) }
        _providerFeedback.value =
          OperationFeedback(
            isError = false,
            message = warning ?: "Saved provider '${saved.displayName}'",
          )
      }.onFailure { err ->
        _providerFeedback.value =
          OperationFeedback(isError = true, message = err.message ?: "Failed to save provider")
      }
    }
  }

  fun setProviderEnabled(providerId: String, enabled: Boolean) {
    viewModelScope.launch { container.aiProviderRegistry.setProviderEnabled(providerId, enabled) }
  }

  fun deleteAiProvider(providerId: String) {
    viewModelScope.launch {
      container.aiProviderRegistry.deleteProvider(providerId)
      _providerFeedback.value = OperationFeedback(false, "Deleted AI provider and its model entries")
    }
  }

  fun clearProviderApiKey(providerId: String) {
    viewModelScope.launch {
      container.aiProviderRegistry.clearProviderApiKey(providerId)
      _providerFeedback.value = OperationFeedback(false, "Cleared encrypted API key")
    }
  }

  fun testAiProviderConnection(providerId: String) {
    viewModelScope.launch {
      _providerFeedback.value = OperationFeedback(false, "Testing connection...")
      val res = container.aiProviderRegistry.testConnection(providerId)
      _providerTestResults.update { it + (providerId to res) }
      _providerFeedback.value =
        OperationFeedback(
          isError = !res.success,
          message =
            buildString {
              append(res.message)
              res.httpStatus?.let { code -> append(" (HTTP $code)") }
              if (res.latencyMs > 0) append(" • ${res.latencyMs}ms")
            },
        )
    }
  }

  fun discoverProviderModels(providerId: String) {
    viewModelScope.launch {
      _modelFeedback.value = OperationFeedback(false, "Querying provider models endpoint...")
      val res = container.aiModelRegistry.refreshModels(providerId)
      res.onSuccess { list ->
        val msg = "Synced ${list.size} model(s) from provider."
        _providerFeedback.value = OperationFeedback(false, msg)
        _modelFeedback.value = OperationFeedback(false, msg)
      }.onFailure { err ->
        val msg = err.message ?: "Model discovery failed"
        _providerFeedback.value = OperationFeedback(true, msg)
        _modelFeedback.value = OperationFeedback(true, msg)
      }
    }
  }

  fun addOrUpdateManualModel(
    providerId: String,
    modelId: String,
    displayName: String,
    contextWindow: Int?,
    maxOutputTokens: Int?,
    visionCapability: CapabilitySupport,
    toolCallingCapability: CapabilitySupport,
    streamingCapability: CapabilitySupport,
  ) {
    viewModelScope.launch {
      container.aiModelRegistry.addOrUpdateManualModel(
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        visionCapability = visionCapability,
        toolCallingCapability = toolCallingCapability,
        streamingCapability = streamingCapability,
      ).onSuccess {
        _modelFeedback.value = OperationFeedback(false, "Saved manual model '${it.modelId}'")
      }.onFailure { err ->
        _modelFeedback.value = OperationFeedback(true, err.message ?: "Failed to add model")
      }
    }
  }

  fun verifyModel(compositeId: String) {
    viewModelScope.launch {
      _modelFeedback.value = OperationFeedback(false, "Verifying model...")
      val res = container.aiModelRegistry.verifyModel(compositeId)
      _modelFeedback.value =
        OperationFeedback(
          isError = !res.success,
          message = "${res.message}${if (res.latencyMs > 0) " (${res.latencyMs}ms)" else ""}",
        )
    }
  }

  fun deleteModel(compositeId: String) {
    viewModelScope.launch {
      container.aiModelRegistry.deleteModel(compositeId)
      _modelFeedback.value = OperationFeedback(false, "Removed model")
    }
  }

  fun setDefaultChatModel(providerId: String, compositeId: String) {
    viewModelScope.launch {
      container.preferencesRepository.setDefaultProviderAndModels(
        defaultProviderId = providerId,
        defaultChatModelCompositeId = compositeId,
      )
      _modelFeedback.value = OperationFeedback(false, "Set default chat model")
    }
  }

  fun setAgentModel(compositeId: String) {
    viewModelScope.launch {
      container.preferencesRepository.setDefaultProviderAndModels(agentModelCompositeId = compositeId)
      _modelFeedback.value = OperationFeedback(false, "Set coding agent model")
    }
  }

  fun setConversationModel(compositeId: String) {
    val convKey = "${activeProject.value?.id ?: "global"}::${aiState.value.conversationId}"
    container.aiModelRegistry.setConversationModel(convKey, compositeId)
    _modelFeedback.value = OperationFeedback(false, "Selected model for active conversation")
  }

  fun setAutoFallback(enabled: Boolean) {
    viewModelScope.launch { container.preferencesRepository.setAutoFallbackOnFailure(enabled) }
  }

  // MCP Server Management
  fun saveMcpServer(
    id: String? = null,
    displayName: String,
    endpointUrl: String,
    transportType: McpTransportType,
    authType: McpAuthType,
    newPlainToken: String?,
    clearToken: Boolean,
    customHeaders: Map<String, String>,
    timeoutSeconds: Int,
    isEnabled: Boolean,
    allowAllTools: Boolean,
  ) {
    viewModelScope.launch {
      container.mcpClientManager.saveServer(
        id = id,
        displayName = displayName,
        endpointUrl = endpointUrl,
        transportType = transportType,
        authType = authType,
        newPlainToken = newPlainToken,
        clearToken = clearToken,
        customHeaders = customHeaders,
        timeoutSeconds = timeoutSeconds,
        isEnabled = isEnabled,
        allowAllTools = allowAllTools,
      ).onSuccess { srv ->
        _mcpFeedback.value = OperationFeedback(false, "Saved MCP server '${srv.displayName}'")
      }.onFailure { err ->
        _mcpFeedback.value = OperationFeedback(true, err.message ?: "Failed to save MCP server")
      }
    }
  }

  fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
    viewModelScope.launch { container.mcpClientManager.setServerEnabled(serverId, enabled) }
  }

  fun deleteMcpServer(serverId: String) {
    viewModelScope.launch {
      container.mcpClientManager.deleteServer(serverId)
      _mcpFeedback.value = OperationFeedback(false, "Deleted MCP server")
    }
  }

  fun connectMcpServer(serverId: String) {
    viewModelScope.launch {
      _mcpFeedback.value = OperationFeedback(false, "Connecting MCP session...")
      val res = container.mcpClientManager.connect(serverId)
      res.onSuccess { tools ->
        _mcpFeedback.value = OperationFeedback(false, "Connected MCP server (${tools.size} tools discovered)")
      }.onFailure { err ->
        _mcpFeedback.value = OperationFeedback(true, err.message ?: "MCP connection failed")
      }
    }
  }

  fun testMcpServer(serverId: String) {
    viewModelScope.launch {
      val res = container.mcpClientManager.testConnection(serverId)
      _mcpFeedback.value =
        res.fold(
          onSuccess = { OperationFeedback(false, it) },
          onFailure = { OperationFeedback(true, it.message ?: "MCP test failed") },
        )
    }
  }

  fun refreshMcpTools(serverId: String) {
    viewModelScope.launch {
      val res = container.mcpClientManager.refreshTools(serverId)
      _mcpFeedback.value =
        res.fold(
          onSuccess = { OperationFeedback(false, "Refreshed ${it.size} MCP tool(s)") },
          onFailure = { OperationFeedback(true, it.message ?: "Failed to refresh MCP tools") },
        )
    }
  }

  fun disconnectMcpServer(serverId: String) {
    viewModelScope.launch {
      container.mcpClientManager.disconnect(serverId)
      _mcpFeedback.value = OperationFeedback(false, "Disconnected MCP server")
    }
  }

  fun setMcpToolPermission(serverId: String, toolName: String, allowed: Boolean) {
    viewModelScope.launch { container.mcpClientManager.setToolPermission(serverId, toolName, allowed) }
  }

  fun setMcpAllowAllTools(serverId: String, allowAll: Boolean) {
    viewModelScope.launch { container.mcpClientManager.setAllowAllTools(serverId, allowAll) }
  }

  // Protection Engine & Mapping Vault
  fun refreshProtectionPlan(project: ProjectEntity? = activeProject.value) {
    if (project == null) {
      _protectionPlan.value = null
      return
    }
    viewModelScope.launch {
      _protectionPlan.value =
        container.protectionPipeline.buildPlan(
          project = project,
          variant = _buildState.value.selectedVariant,
          customToolchainPath = preferences.value.customToolchainPath,
        )
    }
  }

  fun saveProtectionProfile(
    mode: ProtectionProfileMode,
    customConfig: ProtectionConfig? = null,
    keepRules: String? = null,
  ) {
    val project = activeProject.value ?: return
    viewModelScope.launch {
      container.protectionPipeline.profileManager.saveProfile(
        projectId = project.id,
        mode = mode,
        customConfig = customConfig,
        keepRules = keepRules,
      )
      refreshProtectionPlan(project)
      _protectionFeedback.value =
        OperationFeedback(false, "Saved ${mode.displayName} protection profile for ${project.name}")
    }
  }

  fun readDecryptedMapping(entry: MappingVaultEntity) {
    viewModelScope.launch {
      val res = container.symbolAndMappingVault.readDecryptedMapping(entry.id)
      res.onSuccess { plain ->
        _decryptedMappingPreview.value =
          DecryptedMappingPreview(
            entryId = entry.id,
            title = "${entry.originalFileName} (${entry.toolName} • ${entry.buildVariant})",
            sha256 = entry.sha256,
            contentPreview = plain.take(8000),
          )
      }.onFailure { err ->
        _protectionFeedback.value = OperationFeedback(true, err.message ?: "Failed to decrypt mapping")
      }
    }
  }

  fun dismissMappingPreview() {
    _decryptedMappingPreview.value = null
  }

  fun deleteMappingEntry(entryId: String) {
    viewModelScope.launch {
      container.symbolAndMappingVault.deleteEntry(entryId)
      if (_decryptedMappingPreview.value?.entryId == entryId) {
        _decryptedMappingPreview.value = null
      }
      _protectionFeedback.value = OperationFeedback(false, "Deleted mapping vault entry")
    }
  }

  // Settings & Signing
  fun setDarkTheme(dark: Boolean) = viewModelScope.launch { container.preferencesRepository.setDarkTheme(dark) }

  fun updateEditorSettings(fontSizeSp: Int, tabSize: Int, wordWrap: Boolean, autoSave: Boolean) {
    viewModelScope.launch {
      container.preferencesRepository.setEditorSettings(fontSizeSp, tabSize, wordWrap, autoSave)
    }
  }

  fun setAllowCodeUpload(allowed: Boolean) =
    viewModelScope.launch { container.preferencesRepository.setAllowUploadingProjectFilesToOnlineAi(allowed) }

  fun setCustomToolchainPath(path: String) =
    viewModelScope.launch { container.preferencesRepository.setCustomToolchainPath(path) }

  fun ensureSigningKey() {
    viewModelScope.launch {
      runCatching { container.signingManager.ensureSigningKey() }
        .onSuccess { _signingKeyInfo.value = it }
    }
  }
}
