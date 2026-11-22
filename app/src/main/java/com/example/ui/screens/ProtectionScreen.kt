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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.example.core.database.BuildProtectionReportEntity
import com.example.core.database.ProjectEntity
import com.example.protection.ProtectionConfig
import com.example.protection.ProtectionPlan
import com.example.protection.ProtectionProfileMode
import com.example.protection.SavedProtectionProfile
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.OperationFeedback
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionScreen(
  activeProject: ProjectEntity?,
  savedProfile: SavedProtectionProfile?,
  plan: ProtectionPlan?,
  reports: List<BuildProtectionReportEntity>,
  feedback: OperationFeedback?,
  onRefreshPlan: () -> Unit,
  onSaveProfile: (ProtectionProfileMode, ProtectionConfig?, String?) -> Unit,
  onOpenMappingVault: () -> Unit,
) {
  val ideColors = LocalIdeColors.current

  if (activeProject == null) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("No Active Project", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(6.dp))
          Text(
            "Open a workspace first to scan file types, detect frameworks, and configure code protection & obfuscation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    return
  }

  var selectedMode by remember(savedProfile?.projectId, savedProfile?.mode) {
    mutableStateOf(savedProfile?.mode ?: ProtectionProfileMode.BALANCED)
  }
  var customConfig by remember(savedProfile?.projectId, savedProfile?.config) {
    mutableStateOf(savedProfile?.config ?: ProtectionConfig.forProfile(selectedMode))
  }
  var keepRules by remember(savedProfile?.projectId, savedProfile?.keepRules) {
    mutableStateOf(savedProfile?.keepRules.orEmpty())
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "UNIVERSAL CODE PROTECTION & OBFUSCATION",
          trailing = {
            Row {
              IconButton(onClick = onRefreshPlan) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan project")
              }
              OutlinedButton(onClick = onOpenMappingVault, modifier = Modifier.testTag("open_mapping_vault_btn")) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Vault")
              }
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Protection combines language-specific shrinking, obfuscation, stripping, and asset minification. No obfuscation technique makes reverse engineering impossible — keep rules, reflection targets, and mapping backups should always be tested before release.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (feedback != null) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text(
            feedback.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (feedback.isError) MaterialTheme.colorScheme.error else ideColors.success,
          )
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "PROTECTION PROFILE (${activeProject.name})")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ProtectionProfileMode.entries.forEach { mode ->
            FilterChip(
              selected = selectedMode == mode,
              onClick = {
                selectedMode = mode
                if (mode != ProtectionProfileMode.CUSTOM) {
                  customConfig = ProtectionConfig.forProfile(mode)
                }
              },
              label = { Text(mode.displayName) },
              modifier = Modifier.testTag("protection_mode_${mode.name.lowercase()}"),
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(selectedMode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(10.dp))
        Text("Tool Toggles ${if (selectedMode == ProtectionProfileMode.CUSTOM) "(Custom)" else "(Preset Preview)"}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val isCustom = selectedMode == ProtectionProfileMode.CUSTOM
        ToolToggleRow("Android R8 / ProGuard Bytecode Shrinking", customConfig.enableAndroidR8, isCustom) {
          customConfig = customConfig.copy(enableAndroidR8 = it)
        }
        ToolToggleRow("Android Resource Shrinking", customConfig.enableResourceShrinking, isCustom) {
          customConfig = customConfig.copy(enableResourceShrinking = it)
        }
        ToolToggleRow("Flutter Dart --obfuscate & --split-debug-info", customConfig.enableFlutterObfuscate, isCustom) {
          customConfig = customConfig.copy(enableFlutterObfuscate = it)
        }
        ToolToggleRow("JS / TS Built-in Minifier (+ external Terser if available)", customConfig.enableJsMinification, isCustom) {
          customConfig = customConfig.copy(enableJsMinification = it)
        }
        ToolToggleRow("JS External Obfuscator (javascript-obfuscator if available)", customConfig.enableJsObfuscator, isCustom) {
          customConfig = customConfig.copy(enableJsObfuscator = it)
        }
        ToolToggleRow("HTML / CSS / JSON Minification (not encryption)", customConfig.enableWebMinification, isCustom) {
          customConfig = customConfig.copy(enableWebMinification = it)
        }
        ToolToggleRow("Opt-in AES-256-GCM Asset Encryption (Web/Bundle)", customConfig.enableOptInAssetEncryption, isCustom) {
          customConfig = customConfig.copy(enableOptInAssetEncryption = it)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = keepRules,
          onValueChange = { keepRules = it },
          label = { Text("Custom ProGuard / R8 Keep Rules (merged with framework rules)") },
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth().testTag("proguard_keep_rules_input"),
        )
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { onSaveProfile(selectedMode, customConfig, keepRules) },
          modifier = Modifier.fillMaxWidth().testTag("save_protection_profile_btn"),
        ) {
          Icon(Icons.Default.Save, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Save Protection Profile")
        }
      }
    }

    if (plan != null) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          IdeSectionHeader(title = "DETECTED LANGUAGES & FRAMEWORKS")
          Spacer(Modifier.height(6.dp))
          if (plan.detectedFrameworks.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              plan.detectedFrameworks.forEach { fw ->
                StatusPill(fw.displayName, MaterialTheme.colorScheme.primary)
              }
            }
            Spacer(Modifier.height(6.dp))
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            plan.fileCountsByLanguage.entries.forEach { (lang, count) ->
              StatusPill("${lang.displayName}: $count", ideColors.success)
            }
          }
        }
      }

      if (plan.compatibilityIssues.isNotEmpty()) {
        item {
          IdePanel(modifier = Modifier.fillMaxWidth()) {
            IdeSectionHeader(title = "COMPATIBILITY & SCOPE WARNINGS (${plan.compatibilityIssues.size})")
            Spacer(Modifier.height(6.dp))
            plan.compatibilityIssues.forEachIndexed { idx, issue ->
              if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = ideColors.border)
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                  if (issue.severity == "WARNING") Icons.Default.Warning else Icons.Default.Shield,
                  contentDescription = null,
                  tint = if (issue.severity == "WARNING") ideColors.warning else MaterialTheme.colorScheme.primary,
                )
                Text(issue.title, style = MaterialTheme.typography.titleSmall)
              }
              Text(issue.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }

      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          IdeSectionHeader(title = "TOOL AVAILABILITY REGISTRY")
          Spacer(Modifier.height(6.dp))
          plan.toolAvailabilities.forEachIndexed { idx, tool ->
            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = ideColors.border)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(tool.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
              StatusPill(
                text = if (tool.isAvailable) tool.versionOrPath.ifBlank { "AVAILABLE" } else "UNAVAILABLE",
                color = if (tool.isAvailable) ideColors.success else ideColors.warning,
              )
            }
            tool.reasonIfUnavailable?.let { msg ->
              Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }

    if (reports.isNotEmpty()) {
      item { IdeSectionHeader(title = "RECENT PROTECTION REPORTS (${reports.size})") }
      items(reports.take(5), key = { it.id }) { rep ->
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("Profile: ${rep.profileMode} • Frameworks: ${rep.detectedFrameworks.ifBlank { "Standard" }}", style = MaterialTheme.typography.titleSmall)
          Text(Formatters.formatTimestamp(rep.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(4.dp))
          Text(rep.reportJson.take(400), fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@Composable
private fun ToolToggleRow(
  label: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}
