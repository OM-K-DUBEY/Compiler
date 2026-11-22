package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mcp.McpConnectionStatus
import com.example.ui.navigation.IdeRoute
import com.example.ui.screens.AiAssistantScreen
import com.example.ui.screens.AiProviderManagerScreen
import com.example.ui.screens.BuildHistoryScreen
import com.example.ui.screens.BuildManagerScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.MappingVaultScreen
import com.example.ui.screens.McpManagerScreen
import com.example.ui.screens.ModelSelectorScreen
import com.example.ui.screens.ProjectImportScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.ProtectionScreen
import com.example.ui.screens.SdkManagerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.DevForgeTheme
import com.example.ui.theme.LocalIdeColors
import com.example.ui.viewmodel.MainIdeViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: MainIdeViewModel = viewModel()
      val prefs by vm.preferences.collectAsStateWithLifecycle()

      DevForgeTheme(darkTheme = prefs.darkTheme) {
        DevForgeIdeShell(vm = vm)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevForgeIdeShell(vm: MainIdeViewModel) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRouteString = navBackStackEntry?.destination?.route ?: IdeRoute.DASHBOARD.route
  val currentRoute = IdeRoute.entries.firstOrNull { it.route == currentRouteString } ?: IdeRoute.DASHBOARD

  val prefs by vm.preferences.collectAsStateWithLifecycle()
  val projects by vm.projects.collectAsStateWithLifecycle()
  val activeProject by vm.activeProject.collectAsStateWithLifecycle()
  val editorState by vm.editorState.collectAsStateWithLifecycle()
  val buildState by vm.buildState.collectAsStateWithLifecycle()
  val isBuilding by vm.isBuilding.collectAsStateWithLifecycle()
  val liveBuildLogs by vm.liveBuildLogs.collectAsStateWithLifecycle()
  val buildHistory by vm.buildHistory.collectAsStateWithLifecycle()
  val aiState by vm.aiState.collectAsStateWithLifecycle()
  val aiMessages by vm.aiMessages.collectAsStateWithLifecycle()
  val aiConversations by vm.aiConversations.collectAsStateWithLifecycle()
  val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()

  val aiProviders by vm.aiProviders.collectAsStateWithLifecycle()
  val aiModels by vm.aiModels.collectAsStateWithLifecycle()
  val conversationModels by vm.conversationModels.collectAsStateWithLifecycle()
  val providerFeedback by vm.providerFeedback.collectAsStateWithLifecycle()
  val providerTestResults by vm.providerTestResults.collectAsStateWithLifecycle()
  val modelFeedback by vm.modelFeedback.collectAsStateWithLifecycle()

  val mcpServers by vm.mcpServers.collectAsStateWithLifecycle()
  val mcpDiscoveredTools by vm.mcpDiscoveredTools.collectAsStateWithLifecycle()
  val mcpServerLogs by vm.mcpServerLogs.collectAsStateWithLifecycle()
  val mcpAuditLogs by vm.mcpAuditLogs.collectAsStateWithLifecycle()
  val mcpFeedback by vm.mcpFeedback.collectAsStateWithLifecycle()

  val protectionProfile by vm.activeProtectionProfile.collectAsStateWithLifecycle()
  val protectionPlan by vm.protectionPlan.collectAsStateWithLifecycle()
  val protectionReports by vm.protectionReports.collectAsStateWithLifecycle()
  val protectionFeedback by vm.protectionFeedback.collectAsStateWithLifecycle()
  val mappingVaultEntries by vm.mappingVaultEntries.collectAsStateWithLifecycle()
  val decryptedMappingPreview by vm.decryptedMappingPreview.collectAsStateWithLifecycle()

  val sdkPackages by vm.sdkPackages.collectAsStateWithLifecycle()
  val sdkReachability by vm.sdkReachability.collectAsStateWithLifecycle()
  val sdkStorageBytes by vm.sdkStorageBytes.collectAsStateWithLifecycle()
  val hardware by vm.hardwareSnapshot.collectAsStateWithLifecycle()
  val projectsUiState by vm.projectsUiState.collectAsStateWithLifecycle()
  val terminalLines by vm.terminalLines.collectAsStateWithLifecycle()
  val terminalDir by vm.terminalWorkingDir.collectAsStateWithLifecycle()
  val terminalRunning by vm.terminalRunning.collectAsStateWithLifecycle()
  val terminalHistory by vm.terminalHistory.collectAsStateWithLifecycle()
  val terminalEnv by vm.terminalEnv.collectAsStateWithLifecycle()
  val signingKeyInfo by vm.signingKeyInfo.collectAsStateWithLifecycle()

  val activeConvKey = "${activeProject?.id ?: "global"}::${aiState.conversationId}"
  val activeModelBadge =
    remember(aiProviders, aiModels, prefs, aiState.agentModeEnabled, conversationModels, activeConvKey) {
      val convComposite = conversationModels[activeConvKey]
      val chosenComposite =
        convComposite
          ?: (if (aiState.agentModeEnabled) prefs.agentModelCompositeId else prefs.defaultChatModelCompositeId)
          ?: prefs.defaultChatModelCompositeId
      val matchedModel = aiModels.firstOrNull { it.compositeId == chosenComposite }
      val matchedProvider = aiProviders.firstOrNull { it.id == (matchedModel?.providerId ?: prefs.defaultProviderId) }
      when {
        matchedModel != null && matchedProvider != null -> "${matchedProvider.displayName}: ${matchedModel.modelId}"
        matchedProvider != null -> "${matchedProvider.displayName}: ${matchedProvider.defaultModelId.ifBlank { "default" }}"
        aiProviders.isEmpty() -> "No AI provider configured"
        else -> "${aiProviders.first().displayName}: ${aiProviders.first().defaultModelId.ifBlank { "default" }}"
      }
    }
  val connectedMcpCount = remember(mcpServers) { mcpServers.count { it.connectionStatus == McpConnectionStatus.CONNECTED } }
  val connectedMcpToolsCount =
    remember(mcpServers, mcpDiscoveredTools) {
      mcpServers
        .filter { it.isEnabled && it.connectionStatus == McpConnectionStatus.CONNECTED }
        .sumOf { srv ->
          mcpDiscoveredTools[srv.id].orEmpty().count { srv.allowAllTools || it.name in srv.allowedTools }
        }
    }

  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  fun navigateTo(target: IdeRoute) {
    if (currentRoute == target) return
    navController.navigate(target.route) {
      popUpTo(IdeRoute.DASHBOARD.route) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  if (drawerState.isOpen || currentRoute != IdeRoute.DASHBOARD) {
    BackHandler {
      if (drawerState.isOpen) {
        scope.launch { drawerState.close() }
      } else if (!navController.popBackStack()) {
        navigateTo(IdeRoute.DASHBOARD)
      }
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(modifier = Modifier.width(270.dp)) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          Text(
            "DevForge AI",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          )
          IdeRoute.entries.forEach { dest ->
            NavigationDrawerItem(
              selected = currentRoute == dest,
              onClick = {
                scope.launch { drawerState.close() }
                navigateTo(dest)
              },
              icon = { Icon(dest.icon, contentDescription = null) },
              label = { Text(dest.label) },
              modifier = Modifier.padding(horizontal = 8.dp).testTag("drawer_${dest.route}"),
            )
          }
        }
      }
    },
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useRail = maxWidth >= 600.dp

      Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
          TopAppBar(
            colors =
              TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
              ),
            navigationIcon = {
              IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier.testTag("open_drawer_btn"),
              ) {
                Icon(Icons.Default.Menu, contentDescription = "Open IDE menu")
              }
            },
            title = {
              Column {
                Text(currentRoute.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                activeProject?.let {
                  Text(
                    it.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            },
            actions = {
              IconButton(onClick = { navigateTo(IdeRoute.BUILD) }, modifier = Modifier.testTag("topbar_build_btn")) {
                Icon(Icons.Default.Build, contentDescription = "Build Manager")
              }
              IconButton(onClick = { navigateTo(IdeRoute.PROTECTION) }, modifier = Modifier.testTag("topbar_protection_btn")) {
                Icon(Icons.Default.Shield, contentDescription = "Code Protection")
              }
              IconButton(onClick = { navigateTo(IdeRoute.SETTINGS) }, modifier = Modifier.testTag("topbar_settings_btn")) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
              }
            },
          )
        },
        bottomBar = {
          if (!useRail) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
              IdeRoute.entries.filter { it.primaryBottomNav }.forEach { dest ->
                NavigationBarItem(
                  selected = currentRoute == dest,
                  onClick = { navigateTo(dest) },
                  icon = { Icon(dest.icon, contentDescription = dest.label) },
                  label = { Text(dest.shortLabel, maxLines = 1) },
                  modifier = Modifier.testTag(dest.testTag),
                )
              }
            }
          }
        },
      ) { innerPadding ->
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background),
        ) {
          if (useRail) {
            NavigationRail(
              containerColor = MaterialTheme.colorScheme.surface,
              modifier = Modifier
                .fillMaxHeight()
                .border(1.dp, LocalIdeColors.current.border)
                .verticalScroll(rememberScrollState()),
            ) {
              IdeRoute.entries.forEach { dest ->
                NavigationRailItem(
                  selected = currentRoute == dest,
                  onClick = { navigateTo(dest) },
                  icon = { Icon(dest.icon, contentDescription = dest.label) },
                  label = { Text(dest.shortLabel) },
                  modifier = Modifier.testTag(dest.testTag),
                )
              }
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            NavHost(navController = navController, startDestination = IdeRoute.DASHBOARD.route) {
              composable(IdeRoute.DASHBOARD.route) {
                DashboardScreen(
                  activeProject = activeProject,
                  projects = projects,
                  detectedSystems = buildState.detectedSystems,
                  recentBuilds = buildHistory,
                  sdkPackages = sdkPackages,
                  hardware = hardware,
                  preferences = prefs,
                  activeProviderLabel = activeModelBadge,
                  connectedMcpCount = connectedMcpCount,
                  onNavigate = ::navigateTo,
                  onOpenProject = vm::openProject,
                  onRefreshHardware = vm::refreshHardwareSnapshot,
                )
              }
              composable(IdeRoute.PROJECTS.route) {
                ProjectsScreen(
                  projects = projects,
                  activeProjectId = activeProject?.id,
                  uiState = projectsUiState,
                  onCreateProject = vm::createProject,
                  onImportZip = vm::importProjectZip,
                  onExportZip = vm::exportProjectZip,
                  onOpenProjectInEditor = { id ->
                    vm.openProject(id)
                    navigateTo(IdeRoute.EDITOR)
                  },
                  onRenameProject = vm::renameProject,
                  onDuplicateProject = vm::duplicateProject,
                  onDeleteProject = vm::deleteProject,
                  onCreateBackup = vm::createBackup,
                  onRestoreBackup = vm::restoreBackup,
                  onLoadBackups = vm::loadBackups,
                  onClearBanner = vm::clearProjectBanner,
                  onOpenImportScreen = { navigateTo(IdeRoute.PROJECT_IMPORT) },
                )
              }
              composable(IdeRoute.PROJECT_IMPORT.route) {
                ProjectImportScreen(
                  uiState = projectsUiState,
                  onImportZip = vm::importProjectZip,
                  onCreateProject = vm::createProject,
                  onClearBanner = vm::clearProjectBanner,
                  onOpenEditor = { navigateTo(IdeRoute.EDITOR) },
                )
              }
              composable(IdeRoute.EDITOR.route) {
                EditorScreen(
                  state = editorState,
                  preferences = prefs,
                  onOpenFile = vm.editorController::openFile,
                  onSelectTab = vm.editorController::selectTab,
                  onCloseTab = vm.editorController::closeTab,
                  onUpdateContent = { text, cursor ->
                    vm.editorController.updateContent(text, cursor, prefs.autoSaveEnabled)
                  },
                  onSave = vm.editorController::saveActiveTab,
                  onUndo = vm.editorController::undo,
                  onRedo = vm.editorController::redo,
                  onFormat = { vm.editorController.formatActiveFile(prefs.editorTabSize) },
                  onApplyCompletion = vm.editorController::applyCompletion,
                  onToggleExplorer = vm.editorController::toggleExplorer,
                  onToggleDiagnostics = vm.editorController::toggleDiagnosticsDrawer,
                  onToggleFold = vm.editorController::toggleFoldRegion,
                  onSearchChange = vm.editorController::updateSearch,
                  onReplaceAll = vm.editorController::replaceAllInActiveFile,
                  onSearchProject = vm.editorController::searchInProject,
                  onCreateFile = vm.editorController::createFile,
                  onCreateFolder = vm.editorController::createFolder,
                  onRenameOrMove = vm.editorController::renameEntry,
                  onDeleteEntry = vm.editorController::deleteEntry,
                  onRunBuild = {
                    vm.runBuild()
                    navigateTo(IdeRoute.BUILD)
                  },
                  onNavigateProjects = { navigateTo(IdeRoute.PROJECTS) },
                )
              }
              composable(IdeRoute.AI.route) {
                AiAssistantScreen(
                  state = aiState,
                  activeProject = activeProject,
                  messages = aiMessages,
                  conversations = aiConversations,
                  checkpoints = checkpoints,
                  activeModelBadge = activeModelBadge,
                  connectedMcpToolCount = connectedMcpToolsCount,
                  selectableContextFiles = vm::listSelectableContextFiles,
                  onSendMessage = vm::sendAiMessage,
                  onCancelGeneration = vm.aiController::cancelGeneration,
                  onToggleAgentMode = vm.aiController::setAgentMode,
                  onSelectConversation = vm.aiController::selectConversation,
                  onToggleContextFile = vm.aiController::toggleContextFile,
                  onClearContextFiles = vm.aiController::clearSelectedContextFiles,
                  onTogglePatchFile = vm.aiController::togglePatchFileSelection,
                  onSelectAllPatchFiles = vm.aiController::selectAllPatchFiles,
                  onApprovePlan = vm::applyPendingAiPlan,
                  onDiscardPlan = vm.aiController::discardPendingPlan,
                  onResolveToolApproval = vm.aiController::resolvePendingToolApproval,
                  onRollbackCheckpoint = vm::rollbackCheckpoint,
                  onClearChat = { vm.aiController.clearChat(activeProject?.id) },
                  onConfirmConsent = vm::confirmAiOnlineConsent,
                  onDismissConsent = vm.aiController::dismissConsentDialog,
                  onOpenModelSelector = { navigateTo(IdeRoute.MODEL_SELECTOR) },
                  onOpenMcpManager = { navigateTo(IdeRoute.MCP_MANAGER) },
                )
              }
              composable(IdeRoute.AI_PROVIDERS.route) {
                AiProviderManagerScreen(
                  providers = aiProviders,
                  preferences = prefs,
                  feedback = providerFeedback,
                  testResults = providerTestResults,
                  onSaveProvider = vm::saveAiProvider,
                  onSetProviderEnabled = vm::setProviderEnabled,
                  onDeleteProvider = vm::deleteAiProvider,
                  onClearApiKey = vm::clearProviderApiKey,
                  onTestConnection = vm::testAiProviderConnection,
                  onDiscoverModels = vm::discoverProviderModels,
                  onSetDefaultProvider = vm::setDefaultChatModel,
                  onOpenModelSelector = { navigateTo(IdeRoute.MODEL_SELECTOR) },
                )
              }
              composable(IdeRoute.MODEL_SELECTOR.route) {
                ModelSelectorScreen(
                  providers = aiProviders,
                  models = aiModels,
                  preferences = prefs,
                  activeConversationKey = activeConvKey,
                  conversationModels = conversationModels,
                  feedback = modelFeedback,
                  onDiscoverModels = vm::discoverProviderModels,
                  onAddManualModel = vm::addOrUpdateManualModel,
                  onVerifyModel = vm::verifyModel,
                  onDeleteModel = vm::deleteModel,
                  onSetDefaultChatModel = vm::setDefaultChatModel,
                  onSetAgentModel = vm::setAgentModel,
                  onSetConversationModel = vm::setConversationModel,
                )
              }
              composable(IdeRoute.MCP_MANAGER.route) {
                McpManagerScreen(
                  servers = mcpServers,
                  discoveredTools = mcpDiscoveredTools,
                  serverLogs = mcpServerLogs,
                  auditLogs = mcpAuditLogs,
                  feedback = mcpFeedback,
                  onSaveServer = vm::saveMcpServer,
                  onSetServerEnabled = vm::setMcpServerEnabled,
                  onDeleteServer = vm::deleteMcpServer,
                  onConnect = vm::connectMcpServer,
                  onDisconnect = vm::disconnectMcpServer,
                  onTestConnection = vm::testMcpServer,
                  onRefreshTools = vm::refreshMcpTools,
                  onSetToolPermission = vm::setMcpToolPermission,
                  onSetAllowAllTools = vm::setMcpAllowAllTools,
                )
              }
              composable(IdeRoute.TERMINAL.route) {
                TerminalScreen(
                  lines = terminalLines,
                  workingDir = terminalDir,
                  isRunning = terminalRunning,
                  history = terminalHistory,
                  envVars = terminalEnv,
                  requiresConfirmation = vm::requiresScriptConfirmation,
                  onExecute = vm::executeTerminalCommand,
                  onCancel = vm::cancelTerminalCommand,
                  onClear = vm::clearTerminal,
                )
              }
              composable(IdeRoute.BUILD.route) {
                BuildManagerScreen(
                  activeProject = activeProject,
                  buildState = buildState,
                  isBuilding = isBuilding,
                  liveLogs = liveBuildLogs,
                  history = buildHistory,
                  onRefreshDetection = { vm.refreshBuildDetection() },
                  onSelectSystem = vm::selectBuildSystem,
                  onSelectVariant = vm::selectBuildVariant,
                  onSetSignRelease = vm::setSignRelease,
                  onRunBuild = vm::runBuild,
                  onEnqueueBackgroundBuild = vm::enqueueBackgroundBuild,
                  onCancelBuild = vm::cancelBuild,
                  onOpenWebPreview = vm::openWebPreview,
                  onCloseWebPreview = vm::closeWebPreview,
                  onExportArtifact = vm::exportArtifactToUri,
                  onNavigateToFileLine = { file, line ->
                    vm.openFileAtLine(file, line)
                    navigateTo(IdeRoute.EDITOR)
                  },
                  onOpenProtection = { navigateTo(IdeRoute.PROTECTION) },
                  onOpenBuildHistory = { navigateTo(IdeRoute.BUILD_HISTORY) },
                )
              }
              composable(IdeRoute.BUILD_HISTORY.route) {
                BuildHistoryScreen(
                  history = buildHistory,
                  onNavigateToFileLine = { file, line ->
                    vm.openFileAtLine(file, line)
                    navigateTo(IdeRoute.EDITOR)
                  },
                )
              }
              composable(IdeRoute.SDK.route) {
                SdkManagerScreen(
                  packages = sdkPackages,
                  hardware = hardware,
                  sdkStorageBytes = sdkStorageBytes,
                  reachabilityMap = sdkReachability,
                  onInstall = vm::installSdkPackage,
                  onCancelDownload = vm::cancelSdkDownload,
                  onUninstall = vm::uninstallSdkPackage,
                  onCheckReachability = vm::checkSdkPackageReachability,
                  onRefreshHardware = vm::refreshHardwareSnapshot,
                )
              }
              composable(IdeRoute.PROTECTION.route) {
                ProtectionScreen(
                  activeProject = activeProject,
                  savedProfile = protectionProfile,
                  plan = protectionPlan,
                  reports = protectionReports,
                  feedback = protectionFeedback,
                  onRefreshPlan = { vm.refreshProtectionPlan() },
                  onSaveProfile = vm::saveProtectionProfile,
                  onOpenMappingVault = { navigateTo(IdeRoute.MAPPING_VAULT) },
                )
              }
              composable(IdeRoute.MAPPING_VAULT.route) {
                MappingVaultScreen(
                  entries = mappingVaultEntries,
                  decryptedPreview = decryptedMappingPreview,
                  feedback = protectionFeedback,
                  onDecryptAndPreview = vm::readDecryptedMapping,
                  onDismissPreview = vm::dismissMappingPreview,
                  onDeleteEntry = vm::deleteMappingEntry,
                )
              }
              composable(IdeRoute.SETTINGS.route) {
                SettingsScreen(
                  preferences = prefs,
                  providerCount = aiProviders.size,
                  mcpServerCount = mcpServers.size,
                  signingKeyInfo = signingKeyInfo,
                  onSetDarkTheme = vm::setDarkTheme,
                  onSaveEditorSettings = vm::updateEditorSettings,
                  onSetAllowCodeUpload = vm::setAllowCodeUpload,
                  onSetAutoFallback = vm::setAutoFallback,
                  onSaveCustomToolchainPath = vm::setCustomToolchainPath,
                  onEnsureSigningKey = vm::ensureSigningKey,
                  onNavigate = ::navigateTo,
                )
              }
            }
          }
        }
      }
    }
  }
}
