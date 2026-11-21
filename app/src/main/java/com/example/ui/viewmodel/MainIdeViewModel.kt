package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DevForgeApp
import com.example.ai.InstalledOfflineModel
import com.example.ai.OfflineDownloadState
import com.example.compiler.BuildExecutionResult
import com.example.compiler.BuildSystemType
import com.example.compiler.BuildVariant
import com.example.compiler.ProjectDetector
import com.example.core.common.DeviceHardwareSnapshot
import com.example.core.database.AiMessageEntity
import com.example.core.database.BuildHistoryEntity
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.AiMode
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.OnlineProviderType
import com.example.core.security.SigningKeyInfo
import com.example.core.storage.ProjectTemplateType
import com.example.sdk.SdkPackageSpec
import com.example.sdk.SdkPackageUiState
import com.example.terminal.TerminalLine
import com.example.ui.state.AiUiState
import com.example.ui.state.BuildUiState
import com.example.ui.state.EditorUiState
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
      offlineAiManager = container.offlineAiManager,
      contextManager = container.projectContextManager,
      codingAgentEngine = container.codingAgentEngine,
      preferencesRepository = container.preferencesRepository,
      deviceInfoProvider = container.deviceInfoProvider,
    )

  val aiState: StateFlow<AiUiState> = aiController.state

  val aiMessages: StateFlow<List<AiMessageEntity>> =
    activeProject
      .flatMapLatest { proj -> aiController.observeMessages(proj?.id) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val checkpoints: StateFlow<List<CheckpointEntity>> =
    activeProject
      .flatMapLatest { proj ->
        if (proj == null) flowOf(emptyList()) else aiController.observeCheckpoints(proj.id)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

  val installedOfflineModels: StateFlow<List<InstalledOfflineModel>> =
    container.offlineAiManager.installedModels
  val offlineDownloads: StateFlow<Map<String, OfflineDownloadState>> =
    container.offlineAiManager.downloads
  val isNativeLlamaLoaded: Boolean get() = container.offlineAiManager.isNativeLlamaLoaded

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
      var loadedProjectId: String? = null
      activeProject.collect { proj ->
        if (proj?.id != loadedProjectId) {
          loadedProjectId = proj?.id
          editorController.loadProject(proj)
          refreshBuildDetection(proj)
          if (proj != null) {
            container.terminalSessionManager.setWorkingDirectory(File(proj.rootPath))
            loadBackups(proj.id)
          }
        }
      }
    }
    viewModelScope.launch {
      preferences.collect { prefs ->
        if (prefs.customToolchainPath.isNotBlank()) {
          container.terminalSessionManager.setCustomPathPrefix(prefs.customToolchainPath)
        }
      }
    }
  }

  fun refreshHardwareSnapshot() {
    _hardwareSnapshot.value = container.deviceInfoProvider.snapshot()
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
    }
  }

  fun cancelSdkDownload(packageId: String) = container.sdkManagerRepository.cancelDownload(packageId)

  fun uninstallSdkPackage(packageId: String) {
    viewModelScope.launch { container.sdkManagerRepository.uninstallPackage(packageId) }
  }

  // AI & Agent
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

  fun applyPendingAiPlan() {
    val project = activeProject.value ?: return
    aiController.approveAndApplyPendingPlan(project) {
      editorController.refreshTree()
      refreshBuildDetection(project)
    }
  }

  fun rollbackCheckpoint(checkpointId: String) {
    val project = activeProject.value ?: return
    aiController.rollbackCheckpoint(project, checkpointId) {
      editorController.refreshTree()
      refreshBuildDetection(project)
    }
  }

  // Settings & Signing
  fun setDarkTheme(dark: Boolean) = viewModelScope.launch { container.preferencesRepository.setDarkTheme(dark) }

  fun updateEditorSettings(fontSizeSp: Int, tabSize: Int, wordWrap: Boolean, autoSave: Boolean) {
    viewModelScope.launch {
      container.preferencesRepository.setEditorSettings(fontSizeSp, tabSize, wordWrap, autoSave)
    }
  }

  fun setAiMode(mode: AiMode) = viewModelScope.launch { container.preferencesRepository.setAiMode(mode) }

  fun saveOnlineAiConfig(
    provider: OnlineProviderType,
    modelId: String,
    customEndpoint: String,
    newApiKeyOrNull: String?,
  ) {
    viewModelScope.launch {
      container.preferencesRepository.setOnlineAiConfig(provider, modelId, customEndpoint, newApiKeyOrNull)
    }
  }

  fun clearApiKey() = viewModelScope.launch { container.preferencesRepository.clearApiKey() }

  fun setAllowCodeUpload(allowed: Boolean) =
    viewModelScope.launch { container.preferencesRepository.setAllowUploadingProjectFilesToOnlineAi(allowed) }

  fun selectOfflineModel(path: String?, contextSize: Int, temp: Float, threads: Int) =
    viewModelScope.launch {
      container.preferencesRepository.setOfflineAiConfig(path, contextSize, temp, threads)
    }

  fun setCustomToolchainPath(path: String) =
    viewModelScope.launch { container.preferencesRepository.setCustomToolchainPath(path) }

  fun ensureSigningKey() {
    viewModelScope.launch {
      runCatching { container.signingManager.ensureSigningKey() }
        .onSuccess { _signingKeyInfo.value = it }
    }
  }
}
