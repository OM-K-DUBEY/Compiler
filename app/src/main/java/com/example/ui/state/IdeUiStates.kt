package com.example.ui.state

import com.example.ai.AgentPlan
import com.example.ai.AiTokenUsage
import com.example.ai.PatchPreview
import com.example.compiler.BuildExecutionResult
import com.example.compiler.BuildSystemType
import com.example.compiler.BuildVariant
import com.example.core.database.ProjectEntity
import com.example.core.storage.BackupRecord
import com.example.core.storage.FileTreeNode
import com.example.editor.CodeDiagnostic
import com.example.editor.CodeLanguage
import com.example.editor.CompletionItem
import com.example.editor.FoldRegion
import com.example.editor.InFileMatch
import com.example.editor.ProjectSearchHit

data class OpenEditorTab(
  val id: String,
  val relativePath: String,
  val fileName: String,
  val language: CodeLanguage,
  val content: String,
  val savedContent: String,
  val cursorOffset: Int = 0,
  val cursorRevision: Int = 0,
  val undoStack: List<String> = emptyList(),
  val redoStack: List<String> = emptyList(),
  val collapsedFoldStarts: Set<Int> = emptySet(),
) {
  val isDirty: Boolean get() = content != savedContent
}

data class EditorUiState(
  val activeProject: ProjectEntity? = null,
  val fileTree: List<FileTreeNode> = emptyList(),
  val openTabs: List<OpenEditorTab> = emptyList(),
  val activeTabId: String? = null,
  val diagnostics: List<CodeDiagnostic> = emptyList(),
  val completions: List<CompletionItem> = emptyList(),
  val foldRegions: List<FoldRegion> = emptyList(),
  val isExplorerVisible: Boolean = true,
  val isBottomDiagnosticsOpen: Boolean = false,
  val searchQuery: String = "",
  val replaceQuery: String = "",
  val searchCaseSensitive: Boolean = false,
  val searchRegex: Boolean = false,
  val inFileMatches: List<InFileMatch> = emptyList(),
  val activeMatchIndex: Int = 0,
  val projectSearchHits: List<ProjectSearchHit> = emptyList(),
  val statusBanner: String? = null,
) {
  val activeTab: OpenEditorTab?
    get() = openTabs.firstOrNull { it.id == activeTabId } ?: openTabs.firstOrNull()
}

data class BuildUiState(
  val detectedSystems: List<BuildSystemType> = emptyList(),
  val selectedSystem: BuildSystemType? = null,
  val selectedVariant: BuildVariant = BuildVariant.DEBUG,
  val signRelease: Boolean = true,
  val lastResult: BuildExecutionResult? = null,
  val webPreviewEntryPath: String? = null,
)

data class AiUiState(
  val isGenerating: Boolean = false,
  val streamingReply: String = "",
  val lastError: String? = null,
  val lastUsage: AiTokenUsage? = null,
  val agentModeEnabled: Boolean = true,
  val conversationId: String = "default",
  val selectedContextFiles: Set<String> = emptySet(),
  val pendingPlan: AgentPlan? = null,
  val pendingPreviews: List<PatchPreview> = emptyList(),
  val selectedPatchPaths: Set<String> = emptySet(),
  val needsOnlineConsentDialog: Boolean = false,
  val pendingPromptForConsent: String? = null,
  val pendingToolApproval: com.example.mcp.PendingMcpToolApproval? = null,
  val contextFileCount: Int = 0,
  val contextEstimatedTokens: Int = 0,
  val activeMcpToolCount: Int = 0,
  val statusBanner: String? = null,
)

data class ProjectsUiState(
  val isLoading: Boolean = false,
  val backupsForSelected: List<BackupRecord> = emptyList(),
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

data class DecryptedMappingPreview(
  val entryId: String,
  val title: String,
  val sha256: String,
  val contentPreview: String,
)

data class OperationFeedback(
  val isError: Boolean,
  val message: String,
  val timestampMillis: Long = System.currentTimeMillis(),
)
