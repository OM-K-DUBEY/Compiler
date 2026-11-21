package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.preferences.IdePreferences
import com.example.core.storage.FileTreeNode
import com.example.editor.CodeFoldingEngine
import com.example.editor.CodeFormatter
import com.example.editor.DiagnosticSeverity
import com.example.editor.SyntaxHighlighter
import com.example.ui.components.IdePanel
import com.example.ui.components.StatusPill
import com.example.ui.state.EditorUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@Composable
fun EditorScreen(
  state: EditorUiState,
  preferences: IdePreferences,
  onOpenFile: (String) -> Unit,
  onSelectTab: (String) -> Unit,
  onCloseTab: (String) -> Unit,
  onUpdateContent: (String, Int) -> Unit,
  onSave: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onFormat: () -> Unit,
  onApplyCompletion: (String) -> Unit,
  onToggleExplorer: () -> Unit,
  onToggleDiagnostics: () -> Unit,
  onToggleFold: (Int) -> Unit,
  onSearchChange: (String, String, Boolean, Boolean) -> Unit,
  onReplaceAll: () -> Unit,
  onSearchProject: (String) -> Unit,
  onCreateFile: (String) -> Unit,
  onCreateFolder: (String) -> Unit,
  onRenameOrMove: (String, String) -> Unit,
  onDeleteEntry: (String) -> Unit,
  onRunBuild: () -> Unit,
  onNavigateProjects: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var showSearchBar by remember { mutableStateOf(false) }
  var showFoldDialog by remember { mutableStateOf(false) }
  var showNewFileDialog by remember { mutableStateOf(false) }
  var showNewFolderDialog by remember { mutableStateOf(false) }
  var entryMenuTarget by remember { mutableStateOf<FileTreeNode?>(null) }
  var renameMoveTarget by remember { mutableStateOf<FileTreeNode?>(null) }

  if (state.activeProject == null) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        Text("No Active Project", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
          "Open or create a workspace in Projects to launch the code editor.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onNavigateProjects) { Text("Go to Projects") }
      }
    }
    return
  }

  Column(modifier = Modifier.fillMaxSize().imePadding()) {
    // Compact Editor Toolbar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, ideColors.border)
        .padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onToggleExplorer, modifier = Modifier.testTag("editor_toggle_explorer")) {
        Icon(
          if (state.isExplorerVisible) Icons.Default.FolderOpen else Icons.Default.Folder,
          contentDescription = "Toggle file explorer",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      IconButton(onClick = onUndo, enabled = state.activeTab?.undoStack?.isNotEmpty() == true) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
      }
      IconButton(onClick = onRedo, enabled = state.activeTab?.redoStack?.isNotEmpty() == true) {
        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
      }
      IconButton(onClick = onSave, modifier = Modifier.testTag("editor_save_btn")) {
        Icon(Icons.Default.Save, contentDescription = "Save file")
      }
      IconButton(onClick = onFormat, modifier = Modifier.testTag("editor_format_btn")) {
        Icon(Icons.Default.AutoFixHigh, contentDescription = "Format code")
      }
      IconButton(onClick = { showFoldDialog = !showFoldDialog }) {
        Icon(Icons.Default.UnfoldLess, contentDescription = "Code folding")
      }
      IconButton(onClick = { showSearchBar = !showSearchBar }, modifier = Modifier.testTag("editor_search_btn")) {
        Icon(Icons.Default.Search, contentDescription = "Find and replace")
      }
      Spacer(Modifier.weight(1f))
      val errCount = state.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
      IconButton(onClick = onToggleDiagnostics, modifier = Modifier.testTag("editor_diagnostics_btn")) {
        Icon(
          Icons.Default.BugReport,
          contentDescription = "Toggle diagnostics",
          tint = if (errCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onRunBuild, modifier = Modifier.testTag("editor_run_build_btn")) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Run build", tint = ideColors.success)
      }
    }

    // Search & Replace / Project Search Panel
    if (showSearchBar) {
      SearchAndReplacePanel(
        state = state,
        onSearchChange = onSearchChange,
        onReplaceAll = onReplaceAll,
        onSearchProject = onSearchProject,
        onOpenHit = { hit -> onOpenFile(hit.relativePath) },
      )
    }

    // Main Split Workspace: Explorer + Editor Canvas
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
      val wideLayout = maxWidth >= 600.dp
      Row(modifier = Modifier.fillMaxSize()) {
        if (state.isExplorerVisible) {
          Column(
            modifier = Modifier
              .width(if (wideLayout) 220.dp else 165.dp)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, ideColors.border),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                state.activeProject.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              IconButton(onClick = { showNewFileDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New file", modifier = Modifier.size(18.dp))
              }
              IconButton(onClick = { showNewFolderDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", modifier = Modifier.size(18.dp))
              }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              items(state.fileTree, key = { it.relativePath }) { node ->
                val isSelected = state.activeTab?.relativePath == node.relativePath
                Box {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface
                      )
                      .clickable {
                        if (!node.isDirectory) onOpenFile(node.relativePath)
                        else entryMenuTarget = node
                      }
                      .padding(start = (8 + node.depth * 12).dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Icon(
                      imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                      contentDescription = null,
                      modifier = Modifier.size(15.dp),
                      tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                      node.name,
                      style = MaterialTheme.typography.bodySmall,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { entryMenuTarget = node }, modifier = Modifier.size(24.dp)) {
                      Icon(Icons.Default.DriveFileMove, contentDescription = "File actions", modifier = Modifier.size(13.dp))
                    }
                  }
                  DropdownMenu(
                    expanded = entryMenuTarget?.relativePath == node.relativePath,
                    onDismissRequest = { entryMenuTarget = null },
                  ) {
                    DropdownMenuItem(
                      text = { Text("Rename / Move") },
                      onClick = {
                        renameMoveTarget = node
                        entryMenuTarget = null
                      },
                    )
                    DropdownMenuItem(
                      text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                      leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                      onClick = {
                        onDeleteEntry(node.relativePath)
                        entryMenuTarget = null
                      },
                    )
                  }
                }
              }
            }
          }
        }

        // Right Workspace: Tabs + Code Canvas + Completion Bar
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
          // Open File Tabs
          if (state.openTabs.isNotEmpty()) {
            LazyRow(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, ideColors.border),
            ) {
              items(state.openTabs, key = { it.id }) { tab ->
                val isCurrent = tab.id == state.activeTab?.id
                Row(
                  modifier = Modifier
                    .background(if (isCurrent) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, ideColors.border)
                    .clickable { onSelectTab(tab.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = if (tab.isDirty) "${tab.fileName} •" else tab.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(Modifier.width(6.dp))
                  Icon(
                    Icons.Default.Close,
                    contentDescription = "Close ${tab.fileName}",
                    modifier = Modifier.size(14.dp).clickable { onCloseTab(tab.id) },
                  )
                }
              }
            }
          }

          val activeTab = state.activeTab
          if (activeTab == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
              Text(
                "Select a file from the explorer to begin editing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            val isCollapsedMode = activeTab.collapsedFoldStarts.isNotEmpty()
            val displayedText =
              if (isCollapsedMode) {
                CodeFoldingEngine.collapseContent(activeTab.content, activeTab.collapsedFoldStarts, state.foldRegions)
              } else {
                activeTab.content
              }

            var selection by remember(activeTab.id) { mutableStateOf(TextRange(activeTab.cursorOffset.coerceIn(0, displayedText.length))) }
            val matchedBrackets =
              remember(displayedText, selection.start) {
                SyntaxHighlighter.findMatchingBracket(displayedText, selection.start)
              }

            val visualTransform =
              remember(activeTab.language, ideColors.isDark, matchedBrackets) {
                VisualTransformation { text ->
                  val highlighted: AnnotatedString =
                    SyntaxHighlighter.highlight(
                      text = text.text,
                      language = activeTab.language,
                      isDark = ideColors.isDark,
                      matchedBracketIndices = matchedBrackets,
                    )
                  TransformedText(highlighted, OffsetMapping.Identity)
                }
              }

            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            val lines = remember(displayedText) { displayedText.split('\n') }
            val fontSizeSp = preferences.editorFontSizeSp.sp
            val lineHeightSp = (preferences.editorFontSizeSp + 6).sp

            Row(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(vScroll),
            ) {
              // Synchronized Line Numbers Gutter
              Column(
                modifier = Modifier
                  .background(MaterialTheme.colorScheme.surface)
                  .border(0.5.dp, ideColors.border)
                  .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
              ) {
                lines.forEachIndexed { idx, _ ->
                  val lineNum = idx + 1
                  val hasFold = state.foldRegions.any { it.startLine == lineNum }
                  val isFolded = lineNum in activeTab.collapsedFoldStarts
                  Text(
                    text = if (hasFold) (if (isFolded) "+$lineNum" else "-$lineNum") else "$lineNum",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = fontSizeSp,
                    lineHeight = lineHeightSp,
                    color = if (hasFold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                      if (hasFold) Modifier.clickable { onToggleFold(lineNum) } else Modifier,
                  )
                }
              }

              // Real Editable Code Canvas
              val codeFieldModifier =
                if (preferences.editorWordWrap) {
                  Modifier.weight(1f).padding(10.dp)
                } else {
                  Modifier.weight(1f).horizontalScroll(hScroll).padding(10.dp)
                }

              BasicTextField(
                value = TextFieldValue(text = displayedText, selection = selection),
                onValueChange = { next ->
                  if (isCollapsedMode) {
                    // Unfold automatically when user starts editing folded view
                    activeTab.collapsedFoldStarts.forEach { onToggleFold(it) }
                    return@BasicTextField
                  }
                  val prevText = activeTab.content
                  var updatedText = next.text
                  var nextSel = next.selection

                  // Auto-indentation on single newline insertion
                  if (updatedText.length == prevText.length + 1 && nextSel.start > 0 && updatedText[nextSel.start - 1] == '\n') {
                    val indent = CodeFormatter.computeAutoIndentForNewline(updatedText.substring(0, nextSel.start - 1), preferences.editorTabSize)
                    if (indent.isNotEmpty()) {
                      updatedText = updatedText.substring(0, nextSel.start) + indent + updatedText.substring(nextSel.start)
                      nextSel = TextRange(nextSel.start + indent.length)
                    }
                  }
                  selection = nextSel
                  onUpdateContent(updatedText, nextSel.start)
                },
                textStyle =
                  TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = fontSizeSp,
                    lineHeight = lineHeightSp,
                    color = MaterialTheme.colorScheme.onBackground,
                  ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransform,
                modifier = codeFieldModifier.testTag("code_editor_field"),
              )
            }

            // Code Completion Suggestion Strip
            if (state.completions.isNotEmpty()) {
              LazyRow(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(MaterialTheme.colorScheme.surface)
                  .border(1.dp, ideColors.border)
                  .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                items(state.completions) { comp ->
                  Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.clickable { onApplyCompletion(comp.insertText) },
                  ) {
                    Text(
                      "${comp.label} (${comp.kind})",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                  }
                }
              }
            }

            // Status / Cursor Bar
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "${activeTab.language.displayName} • ${state.statusBanner ?: activeTab.relativePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              val errCount = state.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
              val warnCount = state.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }
              Text(
                "E:$errCount W:$warnCount",
                style = MaterialTheme.typography.labelSmall,
                color = if (errCount > 0) MaterialTheme.colorScheme.error else ideColors.success,
                modifier = Modifier.clickable { onToggleDiagnostics() },
              )
            }
          }
        }
      }
    }

    // Bottom Diagnostics Panel
    if (state.isBottomDiagnosticsOpen) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .height(145.dp)
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, ideColors.border)
          .padding(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Diagnostics (${state.diagnostics.size})", style = MaterialTheme.typography.titleSmall)
          IconButton(onClick = onToggleDiagnostics, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close diagnostics")
          }
        }
        if (state.diagnostics.isEmpty()) {
          Text("No issues detected in active file.", style = MaterialTheme.typography.bodySmall, color = ideColors.success)
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.diagnostics) { diag ->
              val color =
                when (diag.severity) {
                  DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
                  DiagnosticSeverity.WARNING -> ideColors.warning
                  DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.primary
                }
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                StatusPill(diag.severity.name, color)
                Text(
                  "L${diag.line}:${diag.column} ${diag.message}",
                  style = MaterialTheme.typography.bodySmall,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
    }
  }

  if (showFoldDialog && state.activeTab != null) {
    val activeTab = state.activeTab!!
    AlertDialog(
      onDismissRequest = { showFoldDialog = false },
      title = { Text("Code Folding Regions") },
      text = {
        if (state.foldRegions.isEmpty()) {
          Text("No foldable block regions in ${activeTab.fileName}.")
        } else {
          LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.foldRegions) { reg ->
              val isCollapsed = reg.startLine in activeTab.collapsedFoldStarts
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  "L${reg.startLine}-${reg.endLine}: ${reg.headerPreview}",
                  style = MaterialTheme.typography.bodySmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onToggleFold(reg.startLine) }) {
                  Text(if (isCollapsed) "Unfold" else "Fold")
                }
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showFoldDialog = false }) { Text("Close") } },
    )
  }

  if (showNewFileDialog) {
    InputPathDialog(
      title = "Create File",
      label = "Relative Path (e.g. src/Utils.kt)",
      initialValue = "",
      onConfirm = {
        showNewFileDialog = false
        onCreateFile(it)
      },
      onDismiss = { showNewFileDialog = false },
    )
  }

  if (showNewFolderDialog) {
    InputPathDialog(
      title = "Create Folder",
      label = "Relative Folder Path",
      initialValue = "",
      onConfirm = {
        showNewFolderDialog = false
        onCreateFolder(it)
      },
      onDismiss = { showNewFolderDialog = false },
    )
  }

  renameMoveTarget?.let { target ->
    InputPathDialog(
      title = "Rename / Move",
      label = "New Relative Path",
      initialValue = target.relativePath,
      onConfirm = { newPath ->
        onRenameOrMove(target.relativePath, newPath)
        renameMoveTarget = null
      },
      onDismiss = { renameMoveTarget = null },
    )
  }
}

@Composable
private fun SearchAndReplacePanel(
  state: EditorUiState,
  onSearchChange: (String, String, Boolean, Boolean) -> Unit,
  onReplaceAll: () -> Unit,
  onSearchProject: (String) -> Unit,
  onOpenHit: (com.example.editor.ProjectSearchHit) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .border(1.dp, LocalIdeColors.current.border)
      .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = state.searchQuery,
        onValueChange = { onSearchChange(it, state.replaceQuery, state.searchCaseSensitive, state.searchRegex) },
        label = { Text("Find (${state.inFileMatches.size})") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = state.replaceQuery,
        onValueChange = { onSearchChange(state.searchQuery, it, state.searchCaseSensitive, state.searchRegex) },
        label = { Text("Replace") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Checkbox(
        checked = state.searchCaseSensitive,
        onCheckedChange = { onSearchChange(state.searchQuery, state.replaceQuery, it, state.searchRegex) },
      )
      Text("Aa", style = MaterialTheme.typography.labelSmall)
      Checkbox(
        checked = state.searchRegex,
        onCheckedChange = { onSearchChange(state.searchQuery, state.replaceQuery, state.searchCaseSensitive, it) },
      )
      Text("Regex", style = MaterialTheme.typography.labelSmall)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = onReplaceAll) { Text("Replace All") }
      OutlinedButton(onClick = { onSearchProject(state.searchQuery) }) { Text("In Project") }
    }
    if (state.projectSearchHits.isNotEmpty()) {
      LazyColumn(modifier = Modifier.height(90.dp)) {
        items(state.projectSearchHits.take(20)) { hit ->
          Text(
            "${hit.relativePath}:${hit.line} — ${hit.preview}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { onOpenHit(hit) }.padding(vertical = 2.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun InputPathDialog(
  title: String,
  label: String,
  initialValue: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var value by remember(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("Confirm") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
