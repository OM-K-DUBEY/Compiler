package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.OnlineProviderType
import com.example.core.security.SigningKeyInfo
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  preferences: IdePreferences,
  signingKeyInfo: SigningKeyInfo?,
  onSetDarkTheme: (Boolean) -> Unit,
  onSaveEditorSettings: (Int, Int, Boolean, Boolean) -> Unit,
  onSaveOnlineConfig: (OnlineProviderType, String, String, String?) -> Unit,
  onClearApiKey: () -> Unit,
  onSetAllowCodeUpload: (Boolean) -> Unit,
  onSaveCustomToolchainPath: (String) -> Unit,
  onEnsureSigningKey: () -> Unit,
) {
  val ideColors = LocalIdeColors.current

  var fontSize by remember(preferences.editorFontSizeSp) { mutableIntStateOf(preferences.editorFontSizeSp) }
  var tabSize by remember(preferences.editorTabSize) { mutableIntStateOf(preferences.editorTabSize) }
  var wordWrap by remember(preferences.editorWordWrap) { mutableStateOf(preferences.editorWordWrap) }
  var autoSave by remember(preferences.autoSaveEnabled) { mutableStateOf(preferences.autoSaveEnabled) }

  var provider by remember(preferences.onlineProvider) { mutableStateOf(preferences.onlineProvider) }
  var modelId by remember(preferences.onlineModelId) { mutableStateOf(preferences.onlineModelId) }
  var endpointUrl by remember(preferences.customEndpointUrl) { mutableStateOf(preferences.customEndpointUrl) }
  var apiKeyInput by remember { mutableStateOf("") }
  var keySavedNotice by remember { mutableStateOf<String?>(null) }

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
        IdeSectionHeader(title = "ONLINE AI ENGINE & ENCRYPTED KEYSTORE")
        Spacer(Modifier.height(8.dp))
        Text("Online Provider", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          OnlineProviderType.entries.forEach { p ->
            FilterChip(
              selected = provider == p,
              onClick = {
                provider = p
                modelId = p.defaultModel
                endpointUrl = p.defaultBaseUrl
              },
              label = { Text(p.displayName) },
            )
          }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = modelId,
          onValueChange = { modelId = it },
          label = { Text("Model ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = endpointUrl,
          onValueChange = { endpointUrl = it },
          label = { Text("Base API Endpoint URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = apiKeyInput,
          onValueChange = { apiKeyInput = it },
          label = {
            Text(
              if (preferences.hasApiKey) "API Key (encrypted in AndroidKeyStore — enter to replace)"
              else "API Key (stored encrypted via AndroidKeyStore AES-256-GCM)"
            )
          },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("settings_api_key_input"),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Button(
            onClick = {
              onSaveOnlineConfig(provider, modelId, endpointUrl, apiKeyInput.takeIf { it.isNotBlank() })
              if (apiKeyInput.isNotBlank()) {
                apiKeyInput = ""
                keySavedNotice = "API key encrypted & saved in AndroidKeyStore"
              } else {
                keySavedNotice = "Provider settings updated"
              }
            },
            modifier = Modifier.testTag("settings_save_ai_btn"),
          ) {
            Text("Save AI Config")
          }
          if (preferences.hasApiKey) {
            OutlinedButton(
              onClick = {
                onClearApiKey()
                keySavedNotice = "Stored API key cleared"
              },
            ) {
              Text("Clear Key", color = MaterialTheme.colorScheme.error)
            }
          }
        }
        keySavedNotice?.let {
          Spacer(Modifier.height(4.dp))
          Text(it, style = MaterialTheme.typography.labelSmall, color = ideColors.success)
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
