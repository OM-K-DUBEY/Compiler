package com.example.ui.viewmodel

import com.example.core.database.EditorTabDao
import com.example.core.database.EditorTabEntity
import com.example.core.database.ProjectEntity
import com.example.core.storage.ProjectWorkspaceManager
import com.example.editor.CodeFoldingEngine
import com.example.editor.CodeFormatter
import com.example.editor.CodeLanguage
import com.example.editor.CompletionEngine
import com.example.editor.DiagnosticsEngine
import com.example.editor.SearchEngine
import com.example.ui.state.EditorUiState
import com.example.ui.state.OpenEditorTab
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorController(
  private val scope: CoroutineScope,
  private val workspaceManager: ProjectWorkspaceManager,
  private val editorTabDao: EditorTabDao,
) {
  private val _state = MutableStateFlow(EditorUiState())
  val state: StateFlow<EditorUiState> = _state.asStateFlow()

  private var autoSaveJob: Job? = null
  private var projectSymbolsCache: List<String> = emptyList()

  fun loadProject(project: ProjectEntity?) {
    if (project == null) {
      _state.value = EditorUiState()
      return
    }
    scope.launch {
      val root = File(project.rootPath)
      val tree = workspaceManager.listFileTree(root)
      val savedTabs = editorTabDao.getTabsForProject(project.id)
      val restoredTabs =
        savedTabs.mapNotNull { entity ->
          val file = File(root, entity.relativePath)
          if (!file.exists() || !file.isFile) return@mapNotNull null
          val diskText = runCatching { workspaceManager.readFileText(root, entity.relativePath) }.getOrNull() ?: return@mapNotNull null
          val activeContent = if (entity.isDirty && entity.recoveryContent != null) entity.recoveryContent else diskText
          OpenEditorTab(
            id = entity.id,
            relativePath = entity.relativePath,
            fileName = file.name,
            language = CodeLanguage.fromFileName(file.name),
            content = activeContent,
            savedContent = diskText,
            cursorOffset = entity.cursorOffset.coerceIn(0, activeContent.length),
          )
        }.toMutableList()

      if (restoredTabs.isEmpty()) {
        val defaultRel = tree.firstOrNull { !it.isDirectory }?.relativePath
        if (defaultRel != null) {
          val text = runCatching { workspaceManager.readFileText(root, defaultRel) }.getOrDefault("")
          restoredTabs +=
            OpenEditorTab(
              id = "${project.id}::$defaultRel",
              relativePath = defaultRel,
              fileName = File(defaultRel).name,
              language = CodeLanguage.fromFileName(defaultRel),
              content = text,
              savedContent = text,
            )
        }
      }

      val activeTab = restoredTabs.firstOrNull()
      refreshProjectSymbols(root, tree.filter { !it.isDirectory }.map { it.relativePath })

      _state.update {
        it.copy(
          activeProject = project,
          fileTree = tree,
          openTabs = restoredTabs,
          activeTabId = activeTab?.id,
          diagnostics = activeTab?.let { t -> DiagnosticsEngine.analyze(t.relativePath, t.content) } ?: emptyList(),
          foldRegions = activeTab?.let { t -> CodeFoldingEngine.detectFoldRegions(t.content) } ?: emptyList(),
          completions = emptyList(),
          statusBanner = if (restoredTabs.any { t -> t.isDirty }) "Recovered unsaved draft from local cache" else null,
        )
      }
    }
  }

  fun refreshTree() {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      val tree = workspaceManager.listFileTree(root)
      // Reload clean tabs from disk in case agent/backup modified them
      val updatedTabs =
        _state.value.openTabs.mapNotNull { tab ->
          val file = File(root, tab.relativePath)
          if (!file.exists()) return@mapNotNull null
          if (!tab.isDirty) {
            val fresh = runCatching { workspaceManager.readFileText(root, tab.relativePath) }.getOrDefault(tab.content)
            tab.copy(content = fresh, savedContent = fresh)
          } else {
            tab
          }
        }
      _state.update { it.copy(fileTree = tree, openTabs = updatedTabs) }
      reanalyzeActiveTab()
    }
  }

  fun openFile(relativePath: String) {
    val project = _state.value.activeProject ?: return
    val tabId = "${project.id}::$relativePath"
    val existing = _state.value.openTabs.firstOrNull { it.id == tabId }
    if (existing != null) {
      _state.update {
        it.copy(
          activeTabId = existing.id,
          diagnostics = DiagnosticsEngine.analyze(existing.relativePath, existing.content),
          foldRegions = CodeFoldingEngine.detectFoldRegions(existing.content),
          completions = emptyList(),
        )
      }
      return
    }

    scope.launch {
      val root = File(project.rootPath)
      val contentResult = runCatching { workspaceManager.readFileText(root, relativePath) }
      contentResult.onSuccess { text ->
        val newTab =
          OpenEditorTab(
            id = tabId,
            relativePath = relativePath,
            fileName = File(relativePath).name,
            language = CodeLanguage.fromFileName(relativePath),
            content = text,
            savedContent = text,
          )
        _state.update { s ->
          s.copy(
            openTabs = s.openTabs + newTab,
            activeTabId = newTab.id,
            diagnostics = DiagnosticsEngine.analyze(newTab.relativePath, newTab.content),
            foldRegions = CodeFoldingEngine.detectFoldRegions(newTab.content),
            completions = emptyList(),
            statusBanner = null,
          )
        }
        persistTabRecovery(project.id, newTab, _state.value.openTabs.lastIndex.coerceAtLeast(0))
      }.onFailure { err ->
        _state.update { it.copy(statusBanner = err.message ?: "Unable to open file") }
      }
    }
  }

  fun openFileAtLine(relativePath: String, line: Int) {
    val project = _state.value.activeProject ?: return
    val cleanRel = relativePath.removePrefix("./").trimStart('/')
    val tabId = "${project.id}::$cleanRel"
    val existing = _state.value.openTabs.firstOrNull { it.id == tabId }
    if (existing != null) {
      val offset = lineStartOffset(existing.content, line)
      val updated = existing.copy(cursorOffset = offset)
      _state.update { s ->
        s.copy(
          openTabs = s.openTabs.map { if (it.id == tabId) updated else it },
          activeTabId = tabId,
          diagnostics = DiagnosticsEngine.analyze(updated.relativePath, updated.content),
          foldRegions = CodeFoldingEngine.detectFoldRegions(updated.content),
          statusBanner = "Jumped to $cleanRel:$line",
        )
      }
      return
    }

    scope.launch {
      val root = File(project.rootPath)
      runCatching { workspaceManager.readFileText(root, cleanRel) }
        .onSuccess { text ->
          val offset = lineStartOffset(text, line)
          val newTab =
            OpenEditorTab(
              id = tabId,
              relativePath = cleanRel,
              fileName = File(cleanRel).name,
              language = CodeLanguage.fromFileName(cleanRel),
              content = text,
              savedContent = text,
              cursorOffset = offset,
            )
          _state.update { s ->
            s.copy(
              openTabs = s.openTabs + newTab,
              activeTabId = newTab.id,
              diagnostics = DiagnosticsEngine.analyze(newTab.relativePath, newTab.content),
              foldRegions = CodeFoldingEngine.detectFoldRegions(newTab.content),
              completions = emptyList(),
              statusBanner = "Opened $cleanRel:$line",
            )
          }
          persistTabRecovery(project.id, newTab, _state.value.openTabs.lastIndex.coerceAtLeast(0))
        }.onFailure { err ->
          _state.update { it.copy(statusBanner = err.message ?: "Unable to open $cleanRel") }
        }
    }
  }

  private fun lineStartOffset(text: String, targetLine: Int): Int {
    if (targetLine <= 1) return 0
    var line = 1
    var idx = 0
    while (idx < text.length && line < targetLine) {
      if (text[idx] == '\n') line++
      idx++
    }
    return idx.coerceIn(0, text.length)
  }

  fun selectTab(tabId: String) {
    val tab = _state.value.openTabs.firstOrNull { it.id == tabId } ?: return
    _state.update {
      it.copy(
        activeTabId = tabId,
        diagnostics = DiagnosticsEngine.analyze(tab.relativePath, tab.content),
        foldRegions = CodeFoldingEngine.detectFoldRegions(tab.content),
        completions = emptyList(),
      )
    }
  }

  fun closeTab(tabId: String) {
    val currentTabs = _state.value.openTabs
    val remaining = currentTabs.filterNot { it.id == tabId }
    val nextActive =
      if (_state.value.activeTabId == tabId) remaining.lastOrNull()?.id else _state.value.activeTabId
    _state.update {
      it.copy(
        openTabs = remaining,
        activeTabId = nextActive,
        diagnostics = remaining.firstOrNull { t -> t.id == nextActive }?.let { t -> DiagnosticsEngine.analyze(t.relativePath, t.content) } ?: emptyList(),
      )
    }
    scope.launch { editorTabDao.deleteById(tabId) }
  }

  fun updateContent(newText: String, cursorOffset: Int, autoSaveEnabled: Boolean) {
    val active = _state.value.activeTab ?: return
    val project = _state.value.activeProject ?: return

    val updatedTab =
      if (newText != active.content) {
        active.copy(
          content = newText,
          cursorOffset = cursorOffset,
          undoStack = (active.undoStack + active.content).takeLast(50),
          redoStack = emptyList(),
        )
      } else {
        active.copy(cursorOffset = cursorOffset)
      }

    val completions =
      CompletionEngine.suggest(
        content = updatedTab.content,
        cursorOffset = cursorOffset,
        language = updatedTab.language,
        extraProjectSymbols = projectSymbolsCache,
      )
    val diagnostics = DiagnosticsEngine.analyze(updatedTab.relativePath, updatedTab.content)
    val folds = CodeFoldingEngine.detectFoldRegions(updatedTab.content)
    val matches =
      if (_state.value.searchQuery.isNotEmpty()) {
        SearchEngine.findInText(
          updatedTab.content,
          _state.value.searchQuery,
          _state.value.searchCaseSensitive,
          _state.value.searchRegex,
        )
      } else {
        emptyList()
      }

    _state.update { s ->
      s.copy(
        openTabs = s.openTabs.map { if (it.id == updatedTab.id) updatedTab else it },
        diagnostics = diagnostics,
        completions = completions,
        foldRegions = folds,
        inFileMatches = matches,
      )
    }

    autoSaveJob?.cancel()
    autoSaveJob =
      scope.launch {
        persistTabRecovery(project.id, updatedTab, 0)
        if (autoSaveEnabled && updatedTab.isDirty) {
          delay(900)
          saveActiveTab()
        }
      }
  }

  fun applyCompletion(insertText: String) {
    val active = _state.value.activeTab ?: return
    val range = CompletionEngine.currentWordRange(active.content, active.cursorOffset)
    val newContent = active.content.replaceRange(range, insertText)
    val newCursor = range.first + insertText.length
    updateContent(newContent, newCursor, autoSaveEnabled = true)
    _state.update { it.copy(completions = emptyList()) }
  }

  fun undo() {
    val active = _state.value.activeTab ?: return
    val prev = active.undoStack.lastOrNull() ?: return
    val updated =
      active.copy(
        content = prev,
        cursorOffset = prev.length.coerceAtMost(active.cursorOffset),
        undoStack = active.undoStack.dropLast(1),
        redoStack = (active.redoStack + active.content).takeLast(50),
      )
    replaceActiveTab(updated)
  }

  fun redo() {
    val active = _state.value.activeTab ?: return
    val next = active.redoStack.lastOrNull() ?: return
    val updated =
      active.copy(
        content = next,
        cursorOffset = next.length.coerceAtMost(active.cursorOffset),
        undoStack = (active.undoStack + active.content).takeLast(50),
        redoStack = active.redoStack.dropLast(1),
      )
    replaceActiveTab(updated)
  }

  fun formatActiveFile(tabSize: Int) {
    val active = _state.value.activeTab ?: return
    val formatted = CodeFormatter.format(active.content, active.language, tabSize)
    if (formatted != active.content) {
      updateContent(formatted, formatted.length.coerceAtMost(active.cursorOffset), autoSaveEnabled = true)
      _state.update { it.copy(statusBanner = "Formatted ${active.fileName}") }
    }
  }

  fun toggleFoldRegion(startLine: Int) {
    val active = _state.value.activeTab ?: return
    val nextFolds =
      if (startLine in active.collapsedFoldStarts) active.collapsedFoldStarts - startLine
      else active.collapsedFoldStarts + startLine
    val updated = active.copy(collapsedFoldStarts = nextFolds)
    replaceActiveTab(updated)
  }

  fun saveActiveTab() {
    val project = _state.value.activeProject ?: return
    val active = _state.value.activeTab ?: return
    scope.launch {
      val root = File(project.rootPath)
      runCatching {
        workspaceManager.writeFileText(root, active.relativePath, active.content)
        workspaceManager.refreshMetadata(project.id)
      }.onSuccess {
        val savedTab = active.copy(savedContent = active.content)
        _state.update { s ->
          s.copy(
            openTabs = s.openTabs.map { if (it.id == savedTab.id) savedTab else it },
            statusBanner = "Saved ${active.fileName}",
          )
        }
        persistTabRecovery(project.id, savedTab, 0)
      }.onFailure { err ->
        _state.update { it.copy(statusBanner = "Save failed: ${err.message}") }
      }
    }
  }

  fun toggleExplorer() = _state.update { it.copy(isExplorerVisible = !it.isExplorerVisible) }

  fun toggleDiagnosticsDrawer() =
    _state.update { it.copy(isBottomDiagnosticsOpen = !it.isBottomDiagnosticsOpen) }

  fun updateSearch(query: String, replace: String, caseSensitive: Boolean, useRegex: Boolean) {
    val content = _state.value.activeTab?.content.orEmpty()
    val matches = SearchEngine.findInText(content, query, caseSensitive, useRegex)
    _state.update {
      it.copy(
        searchQuery = query,
        replaceQuery = replace,
        searchCaseSensitive = caseSensitive,
        searchRegex = useRegex,
        inFileMatches = matches,
        activeMatchIndex = 0,
      )
    }
  }

  fun replaceAllInActiveFile() {
    val active = _state.value.activeTab ?: return
    val s = _state.value
    val (replaced, count) =
      SearchEngine.replaceAllInText(
        active.content,
        s.searchQuery,
        s.replaceQuery,
        s.searchCaseSensitive,
        s.searchRegex,
      )
    if (count > 0) {
      updateContent(replaced, 0, autoSaveEnabled = true)
      _state.update { it.copy(statusBanner = "Replaced $count occurrence(s)") }
    }
  }

  fun searchInProject(query: String) {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      val paths = _state.value.fileTree.filter { !it.isDirectory }.map { it.relativePath }
      val hits = SearchEngine.findInProject(root, paths, query, _state.value.searchCaseSensitive)
      _state.update { it.copy(projectSearchHits = hits) }
    }
  }

  fun createFile(relativePath: String) {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      runCatching {
        workspaceManager.createFile(root, relativePath)
        workspaceManager.refreshMetadata(project.id)
      }.onSuccess {
        refreshTree()
        openFile(relativePath)
      }.onFailure { err ->
        _state.update { it.copy(statusBanner = err.message ?: "Failed to create file") }
      }
    }
  }

  fun createFolder(relativePath: String) {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      runCatching { workspaceManager.createDirectory(root, relativePath) }
        .onSuccess { refreshTree() }
        .onFailure { err -> _state.update { it.copy(statusBanner = err.message) } }
    }
  }

  fun renameEntry(oldPath: String, newPath: String) {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      runCatching {
        workspaceManager.renameOrMoveEntry(root, oldPath, newPath)
        workspaceManager.refreshMetadata(project.id)
      }.onSuccess {
        val oldId = "${project.id}::$oldPath"
        closeTab(oldId)
        refreshTree()
      }.onFailure { err ->
        _state.update { it.copy(statusBanner = err.message) }
      }
    }
  }

  fun deleteEntry(relativePath: String) {
    val project = _state.value.activeProject ?: return
    scope.launch {
      val root = File(project.rootPath)
      runCatching {
        workspaceManager.deleteEntry(root, relativePath)
        workspaceManager.refreshMetadata(project.id)
      }.onSuccess {
        closeTab("${project.id}::$relativePath")
        refreshTree()
      }.onFailure { err ->
        _state.update { it.copy(statusBanner = err.message) }
      }
    }
  }

  private fun replaceActiveTab(updated: OpenEditorTab) {
    _state.update { s ->
      s.copy(
        openTabs = s.openTabs.map { if (it.id == updated.id) updated else it },
        diagnostics = DiagnosticsEngine.analyze(updated.relativePath, updated.content),
        foldRegions = CodeFoldingEngine.detectFoldRegions(updated.content),
      )
    }
  }

  private fun reanalyzeActiveTab() {
    val active = _state.value.activeTab ?: return
    _state.update {
      it.copy(
        diagnostics = DiagnosticsEngine.analyze(active.relativePath, active.content),
        foldRegions = CodeFoldingEngine.detectFoldRegions(active.content),
      )
    }
  }

  private suspend fun persistTabRecovery(projectId: String, tab: OpenEditorTab, order: Int) {
    editorTabDao.upsert(
      EditorTabEntity(
        id = tab.id,
        projectId = projectId,
        relativePath = tab.relativePath,
        recoveryContent = if (tab.isDirty) tab.content else null,
        isDirty = tab.isDirty,
        cursorOffset = tab.cursorOffset,
        sortOrder = order,
        updatedAtMillis = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun refreshProjectSymbols(root: File, paths: List<String>) {
    val syms = mutableListOf<String>()
    for (rel in paths.take(30)) {
      val text = runCatching { workspaceManager.readFileText(root, rel) }.getOrNull() ?: continue
      syms += CompletionEngine.extractSymbols(text)
    }
    projectSymbolsCache = syms.distinct()
  }
}
