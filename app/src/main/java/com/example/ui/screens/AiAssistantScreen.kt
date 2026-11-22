package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.Formatters
import com.example.core.database.AiMessageEntity
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.editor.DiffLineType
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.components.StatusPill
import com.example.ui.state.AiUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAssistantScreen(
  state: AiUiState,
  activeProject: ProjectEntity?,
  messages: List<AiMessageEntity>,
  conversations: List<String>,
  checkpoints: List<CheckpointEntity>,
  activeModelBadge: String,
  connectedMcpToolCount: Int,
  selectableContextFiles: () -> List<String>,
  onSendMessage: (String, Boolean) -> Unit,
  onCancelGeneration: () -> Unit,
  onToggleAgentMode: (Boolean) -> Unit,
  onSelectConversation: (String) -> Unit,
  onToggleContextFile: (String) -> Unit,
  onClearContextFiles: () -> Unit,
  onTogglePatchFile: (String) -> Unit,
  onSelectAllPatchFiles: () -> Unit,
  onApprovePlan: (Boolean) -> Unit,
  onDiscardPlan: () -> Unit,
  onResolveToolApproval: (Boolean) -> Unit,
  onRollbackCheckpoint: (String) -> Unit,
  onClearChat: () -> Unit,
  onConfirmConsent: (String) -> Unit,
  onDismissConsent: () -> Unit,
  onOpenModelSelector: () -> Unit,
  onOpenMcpManager: () -> Unit,
) {
  val context = LocalContext.current
  val ideColors = LocalIdeColors.current
  var input by remember { mutableStateOf("") }
  var showCheckpointsSheet by remember { mutableStateOf(false) }
  var showContextPicker by remember { mutableStateOf(false) }
  var showConversationDialog by remember { mutableStateOf(false) }

  fun copyText(label: String, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cb?.setPrimaryClip(ClipData.newPlainText(label, text))
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(12.dp).imePadding(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Top AI Control Strip
    IdePanel(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text("AI Engineer", style = MaterialTheme.typography.titleMedium)
            StatusPill(
              text = activeModelBadge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable { onOpenModelSelector() },
            )
          }
          Text(
            if (activeProject != null) {
              "Context: ${activeProject.name} • ${state.selectedContextFiles.size} pinned, ${state.contextFileCount} sent (~${state.contextEstimatedTokens} tok)"
            } else {
              "No active project context"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Agent", style = MaterialTheme.typography.labelSmall)
          Spacer(Modifier.width(4.dp))
          Switch(checked = state.agentModeEnabled, onCheckedChange = onToggleAgentMode)
        }
      }
      Spacer(Modifier.height(6.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onOpenModelSelector, modifier = Modifier.testTag("ai_open_model_selector_btn")) {
          Icon(Icons.Default.Tune, contentDescription = null)
          Spacer(Modifier.width(4.dp))
          Text("Model")
        }
        OutlinedButton(onClick = onOpenMcpManager, modifier = Modifier.testTag("ai_open_mcp_btn")) {
          Icon(Icons.Default.Dns, contentDescription = null)
          Spacer(Modifier.width(4.dp))
          Text("MCP ($connectedMcpToolCount)")
        }
        if (activeProject != null) {
          OutlinedButton(onClick = { showContextPicker = true }, modifier = Modifier.testTag("ai_context_picker_btn")) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Files (${state.selectedContextFiles.size})")
          }
        }
        OutlinedButton(onClick = { showConversationDialog = true }) {
          Text("Thread: ${state.conversationId}")
        }
        FilterChip(
          selected = showCheckpointsSheet,
          onClick = { showCheckpointsSheet = !showCheckpointsSheet },
          leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
          label = { Text("Checkpoints (${checkpoints.size})") },
        )
        if (activeProject != null) {
          OutlinedButton(
            onClick = { onSendMessage("Analyze the latest build output and propose fixes for any errors.", true) },
            enabled = !state.isGenerating,
          ) {
            Icon(Icons.Default.BugReport, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Fix Build")
          }
        }
        IconButton(onClick = onClearChat) {
          Icon(Icons.Default.Delete, contentDescription = "Clear chat thread")
        }
      }
    }

    if (state.statusBanner != null || state.lastError != null) {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = state.lastError ?: state.statusBanner.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = if (state.lastError != null) MaterialTheme.colorScheme.error else ideColors.success,
        )
      }
    }

    // Conversation Thread & Pending Agent Diffs
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      state.pendingPlan?.let { plan ->
        item(key = "pending_plan") {
          IdePanel(modifier = Modifier.fillMaxWidth().testTag("agent_plan_approval_panel")) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("Proposed Agent Changes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
              TextButton(onClick = onSelectAllPatchFiles) { Text("Select All") }
            }
            Text(plan.summary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            state.pendingPreviews.forEach { preview ->
              val path = preview.patch.relativePath
              val isSelected = path in state.selectedPatchPaths
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isSelected, onCheckedChange = { onTogglePatchFile(path) })
                Text(
                  "${preview.patch.operation.name}: $path",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(MaterialTheme.colorScheme.background)
                  .border(1.dp, ideColors.border)
                  .padding(6.dp),
              ) {
                preview.diffLines.filter { it.type != DiffLineType.CONTEXT }.take(12).forEach { dl ->
                  val prefix = when (dl.type) {
                    DiffLineType.ADDED -> "+"
                    DiffLineType.REMOVED -> "-"
                    DiffLineType.CONTEXT -> " "
                  }
                  val color = when (dl.type) {
                    DiffLineType.ADDED -> ideColors.success
                    DiffLineType.REMOVED -> MaterialTheme.colorScheme.error
                    DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
                  }
                  Text(
                    "$prefix ${dl.text}",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                  )
                }
              }
              Spacer(Modifier.height(6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Button(
                onClick = { onApprovePlan(true) },
                modifier = Modifier.testTag("agent_approve_apply_btn"),
              ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Accept All")
              }
              OutlinedButton(
                onClick = { onApprovePlan(false) },
                enabled = state.selectedPatchPaths.isNotEmpty(),
                modifier = Modifier.testTag("agent_accept_selected_btn"),
              ) {
                Text("Accept Selected (${state.selectedPatchPaths.size})")
              }
              OutlinedButton(onClick = onDiscardPlan) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Reject")
              }
            }
          }
        }
      }

      if (messages.isEmpty() && !state.isGenerating) {
        item {
          IdePanel(modifier = Modifier.fillMaxWidth()) {
            Text(
              "Ask the assistant to explain code, generate modules, use authorized MCP tools, or propose multi-file diffs with checkpoint rollback.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      items(messages, key = { it.id }) { msg ->
        val isUser = msg.role == "user"
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, ideColors.border),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                if (isUser) "You" else "${msg.providerLabel} • ${msg.modelId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.promptTokens > 0 || msg.completionTokens > 0) {
                  Text(
                    "${msg.promptTokens}+${msg.completionTokens} tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(
                  onClick = { copyText("AI Message", msg.content) },
                  modifier = Modifier.size(32.dp),
                ) {
                  Icon(Icons.Default.ContentCopy, contentDescription = "Copy message", modifier = Modifier.size(14.dp))
                }
              }
            }
            Spacer(Modifier.height(4.dp))
            AiMessageContent(content = msg.content, onCopyCode = { code -> copyText("Code snippet", code) })
          }
        }
      }

      if (state.isGenerating) {
        item {
          IdePanel(modifier = Modifier.fillMaxWidth()) {
            Text("Streaming response...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(state.streamingReply.ifBlank { "Waiting for tokens / tool calls..." }, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }

    // Prompt Composer
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text("Ask AI or describe project task...") },
        maxLines = 4,
        modifier = Modifier.weight(1f).testTag("ai_prompt_input"),
      )
      if (state.isGenerating) {
        Button(onClick = onCancelGeneration, modifier = Modifier.testTag("ai_cancel_btn")) {
          Icon(Icons.Default.Stop, contentDescription = "Cancel AI generation")
        }
      } else {
        Button(
          onClick = {
            val text = input
            input = ""
            onSendMessage(text, false)
          },
          enabled = input.isNotBlank(),
          modifier = Modifier.testTag("ai_send_btn"),
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt")
        }
      }
    }
  }

  if (state.needsOnlineConsentDialog && state.pendingPromptForConsent != null) {
    ConfirmActionDialog(
      title = "Upload Project Context?",
      message =
        "Sending this prompt will transmit selected source files from '${activeProject?.name}' to the configured online AI provider ($activeModelBadge). Approve project context sharing?",
      confirmLabel = "Allow & Send",
      onConfirm = { onConfirmConsent(state.pendingPromptForConsent) },
      onDismiss = onDismissConsent,
    )
  }

  state.pendingToolApproval?.let { approval ->
    AlertDialog(
      onDismissRequest = { onResolveToolApproval(false) },
      title = { Text("Approve Sensitive MCP Tool?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Server: ${approval.serverName}", style = MaterialTheme.typography.titleSmall)
          Text("Tool: ${approval.toolName}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
          Text(approval.riskReason, style = MaterialTheme.typography.labelSmall, color = ideColors.warning)
          Text(
            approval.argumentsJson,
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.background)
              .border(1.dp, ideColors.border)
              .padding(6.dp),
          )
        }
      },
      confirmButton = {
        Button(onClick = { onResolveToolApproval(true) }, modifier = Modifier.testTag("mcp_tool_approve_btn")) {
          Text("Allow Once")
        }
      },
      dismissButton = {
        TextButton(onClick = { onResolveToolApproval(false) }) { Text("Deny") }
      },
    )
  }

  if (showContextPicker && activeProject != null) {
    val files = remember(activeProject.id) { selectableContextFiles() }
    AlertDialog(
      onDismissRequest = { showContextPicker = false },
      title = { Text("Select Context Files (${state.selectedContextFiles.size})") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (files.isEmpty()) {
            Text("No source files found in workspace.")
          } else {
            LazyColumn(modifier = Modifier.height(260.dp)) {
              items(files) { rel ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Checkbox(
                    checked = rel in state.selectedContextFiles,
                    onCheckedChange = { onToggleContextFile(rel) },
                  )
                  Text(rel, style = MaterialTheme.typography.bodySmall)
                }
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showContextPicker = false }) { Text("Done") } },
      dismissButton = {
        TextButton(onClick = onClearContextFiles) { Text("Clear Selection") }
      },
    )
  }

  if (showConversationDialog) {
    var newThreadName by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showConversationDialog = false },
      title = { Text("Conversations") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = newThreadName,
            onValueChange = { newThreadName = it },
            label = { Text("New thread name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          conversations.forEach { conv ->
            OutlinedButton(
              onClick = {
                onSelectConversation(conv)
                showConversationDialog = false
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(if (conv == state.conversationId) "$conv (active)" else conv)
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (newThreadName.isNotBlank()) {
              onSelectConversation(newThreadName.trim())
            }
            showConversationDialog = false
          },
        ) { Text("Open / Create") }
      },
      dismissButton = { TextButton(onClick = { showConversationDialog = false }) { Text("Close") } },
    )
  }

  if (showCheckpointsSheet) {
    AlertDialog(
      onDismissRequest = { showCheckpointsSheet = false },
      title = { Text("Agent Checkpoints & Rollback") },
      text = {
        if (checkpoints.isEmpty()) {
          Text("No AI patch checkpoints recorded for this project yet.")
        } else {
          LazyColumn(modifier = Modifier.height(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(checkpoints, key = { it.id }) { cp ->
              Column {
                Text(cp.description, style = MaterialTheme.typography.titleSmall)
                Text(
                  "${Formatters.formatTimestamp(cp.createdAtMillis)} • ${if (cp.isRolledBack) "Rolled back" else "Applied"}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!cp.isRolledBack) {
                  TextButton(onClick = { onRollbackCheckpoint(cp.id) }) { Text("Rollback Changes") }
                }
                HorizontalDivider(color = ideColors.border)
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showCheckpointsSheet = false }) { Text("Close") } },
    )
  }
}

private data class MessageSegment(val isCode: Boolean, val lang: String, val text: String)

private fun splitMessageSegments(content: String): List<MessageSegment> {
  val regex = Regex("```([a-zA-Z0-9_-]*)\\n([\\s\\S]*?)```")
  val segments = mutableListOf<MessageSegment>()
  var cursor = 0
  for (match in regex.findAll(content)) {
    if (match.range.first > cursor) {
      val plain = content.substring(cursor, match.range.first).trim()
      if (plain.isNotEmpty()) segments += MessageSegment(isCode = false, lang = "", text = plain)
    }
    val lang = match.groupValues[1].ifBlank { "code" }
    val code = match.groupValues[2].trimEnd()
    segments += MessageSegment(isCode = true, lang = lang, text = code)
    cursor = match.range.last + 1
  }
  if (cursor < content.length) {
    val tail = content.substring(cursor).trim()
    if (tail.isNotEmpty() || segments.isEmpty()) {
      segments += MessageSegment(isCode = false, lang = "", text = tail.ifEmpty { content })
    }
  }
  return if (segments.isEmpty()) listOf(MessageSegment(false, "", content)) else segments
}

@Composable
private fun AiMessageContent(
  content: String,
  onCopyCode: (String) -> Unit,
) {
  val ideColors = LocalIdeColors.current
  val segments = remember(content) { splitMessageSegments(content) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    segments.forEach { seg ->
      if (!seg.isCode) {
        Text(seg.text, style = MaterialTheme.typography.bodyMedium)
      } else {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, ideColors.border)
            .padding(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(seg.lang.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { onCopyCode(seg.text) }) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(Modifier.width(4.dp))
              Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
          }
          Text(
            text = seg.text,
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          )
        }
      }
    }
  }
}
