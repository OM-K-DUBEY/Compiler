package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.terminal.TerminalLine
import com.example.terminal.TerminalLineKind
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors
import java.io.File

@Composable
fun TerminalScreen(
  lines: List<TerminalLine>,
  workingDir: File,
  isRunning: Boolean,
  history: List<String>,
  envVars: Map<String, String>,
  requiresConfirmation: (String) -> Boolean,
  onExecute: (String) -> Unit,
  onCancel: () -> Unit,
  onClear: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var commandInput by remember { mutableStateOf("") }
  var pendingScriptConfirm by remember { mutableStateOf<String?>(null) }
  var showEnvDialog by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  LaunchedEffect(lines.size) {
    if (lines.isNotEmpty()) {
      listState.animateScrollToItem(lines.lastIndex)
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(12.dp).imePadding(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    IdePanel(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Shell Terminal (/system/bin/sh)", style = MaterialTheme.typography.titleSmall)
          Text(
            workingDir.absolutePath,
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = { showEnvDialog = true }) {
          Icon(Icons.Default.Info, contentDescription = "Environment variables")
        }
        IconButton(onClick = onClear) {
          Icon(Icons.Default.ClearAll, contentDescription = "Clear terminal")
        }
      }
    }

    // Terminal Output Buffer with real text selection
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .border(1.dp, ideColors.border)
        .padding(8.dp),
    ) {
      SelectionContainer(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(lines) { line ->
            val color =
              when (line.kind) {
                TerminalLineKind.COMMAND -> MaterialTheme.colorScheme.primary
                TerminalLineKind.STDOUT -> MaterialTheme.colorScheme.onBackground
                TerminalLineKind.STDERR -> MaterialTheme.colorScheme.error
                TerminalLineKind.SYSTEM -> ideColors.warning
              }
            Text(
              text = line.text,
              style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp, lineHeight = 16.sp),
              color = color,
            )
          }
        }
      }
    }

    // Recent Command History Quick Chips
    if (history.isNotEmpty()) {
      LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(history.asReversed().take(8)) { past ->
          AssistChip(
            onClick = { commandInput = past },
            label = { Text(past, fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelSmall) },
          )
        }
      }
    }

    // Command Input Bar
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = commandInput,
        onValueChange = { commandInput = it },
        placeholder = { Text("Enter shell command (e.g. ls -la, pwd, env)...") },
        singleLine = true,
        textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 13.sp),
        modifier = Modifier.weight(1f).testTag("terminal_command_input"),
      )
      if (isRunning) {
        Button(onClick = onCancel, modifier = Modifier.testTag("terminal_cancel_btn")) {
          Icon(Icons.Default.Stop, contentDescription = "Stop process")
          Spacer(Modifier.width(4.dp))
          Text("Stop")
        }
      } else {
        Button(
          onClick = {
            val cmd = commandInput.trim()
            if (cmd.isNotEmpty()) {
              if (requiresConfirmation(cmd)) {
                pendingScriptConfirm = cmd
              } else {
                commandInput = ""
                onExecute(cmd)
              }
            }
          },
          modifier = Modifier.testTag("terminal_run_btn"),
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run command")
        }
      }
    }
  }

  pendingScriptConfirm?.let { cmd ->
    ConfirmActionDialog(
      title = "Execute Project Script?",
      message = "Confirm running local shell script command:\n$cmd",
      confirmLabel = "Execute",
      onConfirm = {
        pendingScriptConfirm = null
        commandInput = ""
        onExecute(cmd)
      },
      onDismiss = { pendingScriptConfirm = null },
    )
  }

  if (showEnvDialog) {
    AlertDialog(
      onDismissRequest = { showEnvDialog = false },
      title = { Text("Terminal Environment") },
      text = {
        LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          items(envVars.entries.toList()) { (k, v) ->
            Text("$k=$v", fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelSmall)
          }
        }
      },
      confirmButton = { TextButton(onClick = { showEnvDialog = false }) { Text("Close") } },
    )
  }
}
