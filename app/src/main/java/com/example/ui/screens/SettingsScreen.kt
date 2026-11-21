package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.preferences.IdePreferences
import com.example.core.security.SigningKeyInfo
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.navigation.IdeRoute
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  preferences: IdePreferences,
  providerCount: Int,
  mcpServerCount: Int,
  signingKeyInfo: SigningKeyInfo?,
  onSetDarkTheme: (Boolean) -> Unit,
  onSaveEditorSettings: (Int, Int, Boolean, Boolean) -> Unit,
  onSetAllowCodeUpload: (Boolean) -> Unit,
  onSetAutoFallback: (Boolean) -> Unit,
  onSaveCustomToolchainPath: (String) -> Unit,
  onEnsureSigningKey: () -> Unit,
  onNavigate: (IdeRoute) -> Unit,
) {
  val ideColors = LocalIdeColors.current

  var fontSize by remember(preferences.editorFontSizeSp) { mutableIntStateOf(preferences.editorFontSizeSp) }
  var tabSize by remember(preferences.editorTabSize) { mutableIntStateOf(preferences.editorTabSize) }
  var wordWrap by remember(preferences.editorWordWrap) { mutableStateOf(preferences.editorWordWrap) }
  var autoSave by remember(preferences.autoSaveEnabled) { mutableStateOf(preferences.autoSaveEnabled) }
  var toolchainPath by remember(preferences.customToolchainPath) { mutableStateOf(preferences.customToolchainPath) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "APPEARANCE & CODE EDITOR")
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Dark Developer IDE Theme", style = MaterialTheme.typography.bodyMedium)
          Switch(
            checked = preferences.darkTheme,
            onCheckedChange = onSetDarkTheme,
            modifier = Modifier.testTag("settings_dark_theme_switch"),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Word Wrap", style = MaterialTheme.typography.bodyMedium)
          Switch(checked = wordWrap, onCheckedChange = { wordWrap = it })
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Auto-save with Crash Recovery", style = MaterialTheme.typography.bodyMedium)
          Switch(checked = autoSave, onCheckedChange = { autoSave = it })
        }
        Text("Editor Font Size: ${fontSize}sp", style = MaterialTheme.typography.labelMedium)
        Slider(
          value = fontSize.toFloat(),
          onValueChange = { fontSize = it.toInt() },
          valueRange = 10f..22f,
        )
        Text("Tab Width: $tabSize spaces", style = MaterialTheme.typography.labelMedium)
        Slider(
          value = tabSize.toFloat(),
          onValueChange = { tabSize = it.toInt() },
          valueRange = 2f..8f,
        )
        Button(onClick = { onSaveEditorSettings(fontSize, tabSize, wordWrap, autoSave) }) {
          Text("Apply Editor Settings")
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "AI GOVERNANCE, MCP & PROTECTION")
        Spacer(Modifier.height(6.dp))
        Text(
          "Configured Providers: $providerCount • Registered MCP Servers: $mcpServerCount\nDefault Chat Model: ${preferences.defaultChatModelCompositeId ?: "Auto"} • Agent Model: ${preferences.agentModelCompositeId ?: "Same as Chat"}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { onNavigate(IdeRoute.AI_PROVIDERS) }, modifier = Modifier.testTag("settings_open_providers_btn")) {
            Text("AI Providers")
          }
          OutlinedButton(onClick = { onNavigate(IdeRoute.MODEL_SELECTOR) }, modifier = Modifier.testTag("settings_open_models_btn")) {
            Text("Model Selector")
          }
          OutlinedButton(onClick = { onNavigate(IdeRoute.MCP_MANAGER) }, modifier = Modifier.testTag("settings_open_mcp_btn")) {
            Text("MCP Servers")
          }
          OutlinedButton(onClick = { onNavigate(IdeRoute.PROTECTION) }, modifier = Modifier.testTag("settings_open_protection_btn")) {
            Text("Code Protection")
          }
          OutlinedButton(onClick = { onNavigate(IdeRoute.MAPPING_VAULT) }, modifier = Modifier.testTag("settings_open_vault_btn")) {
            Text("Mapping Vault")
          }
        }
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Allow sending project files to Online AI", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          Switch(checked = preferences.allowUploadingProjectFilesToOnlineAi, onCheckedChange = onSetAllowCodeUpload)
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            "Opt-in auto-fallback to backup configured provider on error (never silent)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
          )
          Switch(checked = preferences.autoFallbackOnFailure, onCheckedChange = onSetAutoFallback)
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "CUSTOM TOOLCHAIN & SIGNING KEYSTORE")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = toolchainPath,
          onValueChange = { toolchainPath = it },
          label = { Text("Extra Executable PATH (colon-separated, e.g. Termux bin)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSaveCustomToolchainPath(toolchainPath) }) {
          Text("Save Toolchain Path")
        }

        Spacer(Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Release Archive Signing Key (RSA-2048)", style = MaterialTheme.typography.titleSmall)
          StatusPill(
            text = if (signingKeyInfo != null) "KEY READY" else "UNCHECKED",
            color = if (signingKeyInfo != null) ideColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        signingKeyInfo?.let { info ->
          Spacer(Modifier.height(4.dp))
          Text(
            "Alias: ${info.alias}\nSHA-256: ${info.sha256Fingerprint}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onEnsureSigningKey) {
          Text("Generate / Inspect Keystore Key")
        }
      }
    }
  }
}
