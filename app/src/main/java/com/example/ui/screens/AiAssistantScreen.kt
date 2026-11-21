package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.common.Formatters
import com.example.core.database.AiMessageEntity
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.IdePreferences
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
  checkpoints: List<CheckpointEntity>,
  preferences: IdePreferences,
  onSendMessage: (String, Boolean) -> Unit,
  onCancelGeneration: () -> Unit,
  onToggleAgentMode: (Boolean) -> Unit,
  onApprovePlan: () -> Unit,
  onDiscardPlan: () -> Unit,
  onRollbackCheckpoint: (String) -> Unit,
  onClearChat: () -> Unit,
  onConfirmConsent: (String) -> Unit,
  onDismissConsent: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var input by remember { mutableStateOf("") }
  var showCheckpointsSheet by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxSize().padding(12.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Top AI Control Strip
    IdePanel(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI Engineer", style = MaterialTheme.typography.titleMedium)
            StatusPill(
              text = "${preferences.onlineProvider.displayName} (${preferences.onlineModelId})",
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Text(
            if (activeProject != null) "Context: ${activeProject.name} • ${state.contextFileCount} files (~${state.contextEstimatedTokens} tok)"
            else "No active project context",
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
            Text("Fix Build Errors")
          }
        }
        IconButton(onClick = onClearChat) {
          Icon(Icons.Default.Delete, contentDescription = "Clear chat history")
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

    // Pending Agent Plan + File-Level Diff Approval Panel
    state.pendingPlan?.let { plan ->
      IdePanel(modifier = Modifier.fillMaxWidth().testTag("agent_plan_approval_panel")) {
        Text("Proposed Agent Changes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(plan.summary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        state.pendingPreviews.forEach { preview ->
          Text(
            "${preview.patch.operation.name}: ${preview.patch.relativePath}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.background)
              .border(1.dp, ideColors.border)
              .padding(6.dp),
          ) {
            preview.diffLines.filter { it.type != DiffLineType.CONTEXT }.take(14).forEach { dl ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onApprovePlan,
            modifier = Modifier.weight(1f).testTag("agent_approve_apply_btn"),
          ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Approve & Apply")
          }
          OutlinedButton(
            onClick = onDiscardPlan,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Discard")
          }
        }
      }
    }

    // Conversation Thread
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (messages.isEmpty() && !state.isGenerating) {
        item {
          IdePanel(modifier = Modifier.fillMaxWidth()) {
            Text(
              "Ask the assistant to explain code, generate modules, convert Java to Kotlin, or propose multi-file diffs with checkpoint rollback.",
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(
                if (isUser) "You" else "${msg.providerLabel} • ${msg.modelId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              if (msg.promptTokens > 0 || msg.completionTokens > 0) {
                Text(
                  "${msg.promptTokens}+${msg.completionTokens} tok",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(Modifier.height(4.dp))
            Text(msg.content, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      if (state.isGenerating) {
        item {
          IdePanel(modifier = Modifier.fillMaxWidth()) {
            Text("Streaming response...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(state.streamingReply.ifBlank { "Waiting for tokens..." }, style = MaterialTheme.typography.bodyMedium)
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
        "Sending this prompt in Online mode will transmit relevant source files from '${activeProject?.name}' to ${preferences.onlineProvider.displayName}. Approve project context sharing?",
      confirmLabel = "Allow & Send",
      onConfirm = { onConfirmConsent(state.pendingPromptForConsent) },
      onDismiss = onDismissConsent,
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
