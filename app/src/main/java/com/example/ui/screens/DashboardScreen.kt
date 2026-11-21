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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.compiler.BuildSystemType
import com.example.core.common.DeviceHardwareSnapshot
import com.example.core.common.Formatters
import com.example.core.database.BuildHistoryEntity
import com.example.core.database.ProjectEntity
import com.example.core.preferences.IdePreferences
import com.example.sdk.SdkPackageUiState
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.navigation.IdeRoute
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
  activeProject: ProjectEntity?,
  projects: List<ProjectEntity>,
  detectedSystems: List<BuildSystemType>,
  recentBuilds: List<BuildHistoryEntity>,
  sdkPackages: List<SdkPackageUiState>,
  hardware: DeviceHardwareSnapshot,
  preferences: IdePreferences,
  onNavigate: (IdeRoute) -> Unit,
  onOpenProject: (String) -> Unit,
  onRefreshHardware: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  val installedToolCount = sdkPackages.count { it.isInstalledLocally || it.isSystemDetected }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "ACTIVE WORKSPACE",
          trailing = {
            if (activeProject != null) {
              StatusPill(
                text = activeProject.projectType,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          },
        )
        Spacer(Modifier.height(8.dp))
        if (activeProject == null) {
          Text(
            "No workspace open yet. Create a project from a template or import a ZIP archive to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(10.dp))
          Button(
            onClick = { onNavigate(IdeRoute.PROJECTS) },
            modifier = Modifier.testTag("dashboard_open_projects_btn"),
          ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create or Import Project")
          }
        } else {
          Text(
            activeProject.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "${activeProject.packageName} • ${activeProject.fileCount} files • ${Formatters.formatBytes(activeProject.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (detectedSystems.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              detectedSystems.forEach { sys ->
                StatusPill(text = sys.displayName, color = ideColors.success)
              }
            }
          }
          Spacer(Modifier.height(10.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = { onNavigate(IdeRoute.EDITOR) },
              modifier = Modifier.testTag("dashboard_open_editor_btn"),
            ) {
              Icon(Icons.Default.Code, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("Open Editor")
            }
            FilledTonalButton(
              onClick = { onNavigate(IdeRoute.BUILD) },
              modifier = Modifier.testTag("dashboard_open_build_btn"),
            ) {
              Icon(Icons.Default.Build, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("Build")
            }
            OutlinedButton(
              onClick = { onNavigate(IdeRoute.TERMINAL) },
              modifier = Modifier.testTag("dashboard_open_terminal_btn"),
            ) {
              Icon(Icons.Default.Terminal, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("Terminal")
            }
            OutlinedButton(
              onClick = { onNavigate(IdeRoute.AI) },
              modifier = Modifier.testTag("dashboard_open_ai_btn"),
            ) {
              Icon(Icons.Default.AutoAwesome, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("AI Agent")
            }
          }
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "DEVICE & ENVIRONMENT TELEMETRY",
          trailing = {
            IconButton(onClick = onRefreshHardware) {
              Icon(Icons.Default.Refresh, contentDescription = "Refresh hardware status")
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Available RAM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              "${Formatters.formatBytes(hardware.availableRamBytes)} / ${Formatters.formatBytes(hardware.totalRamBytes)}",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text("Free Storage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              Formatters.formatBytes(hardware.availableStorageBytes),
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(modifier = Modifier.weight(1f)) {
            Text("AI Provider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              "${preferences.onlineProvider.displayName} (${preferences.onlineModelId.ifBlank { "default" }})",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text("Toolchains", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              "$installedToolCount detected / ${sdkPackages.size} cataloged",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          StatusPill(
            text = if (hardware.isOnline) "Network: Online" else "Network: Offline",
            color = if (hardware.isOnline) ideColors.success else ideColors.warning,
          )
          StatusPill(
            text = "Android ${hardware.androidRelease} (API ${hardware.androidSdkInt})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(onClick = { onNavigate(IdeRoute.SDK) }) {
            Icon(Icons.Default.Extension, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("SDKs")
          }
        }
      }
    }

    if (projects.isNotEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          IdeSectionHeader(
            title = "RECENT WORKSPACES (${projects.size})",
            trailing = {
              OutlinedButton(onClick = { onNavigate(IdeRoute.PROJECTS) }) {
                Text("All Projects")
              }
            },
          )
          Spacer(Modifier.height(8.dp))
          projects.take(4).forEachIndexed { idx, proj ->
            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = ideColors.border)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(proj.name, style = MaterialTheme.typography.titleSmall)
                Text(
                  "${proj.fileCount} files • ${Formatters.formatBytes(proj.sizeBytes)} • ${Formatters.formatTimestamp(proj.lastModifiedMillis)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              OutlinedButton(
                onClick = {
                  onOpenProject(proj.id)
                  onNavigate(IdeRoute.EDITOR)
                },
              ) {
                Text(if (proj.id == activeProject?.id) "Active" else "Open")
              }
            }
          }
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "RECENT BUILDS",
          trailing = {
            OutlinedButton(onClick = { onNavigate(IdeRoute.BUILD) }) {
              Text("Build Center")
            }
          },
        )
        Spacer(Modifier.height(8.dp))
        if (recentBuilds.isEmpty()) {
          Text(
            "No builds executed yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(recentBuilds.take(5), key = { it.id }) { build ->
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${build.projectName} — ${build.buildSystem}", style = MaterialTheme.typography.titleSmall)
            Text(
              "${build.buildType} • ${Formatters.formatDurationMs(build.durationMillis)} • ${Formatters.formatTimestamp(build.timestampMillis)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          val statusColor =
            when (build.status) {
              "SUCCEEDED" -> ideColors.success
              "FAILED" -> MaterialTheme.colorScheme.error
              "CANCELLED" -> ideColors.warning
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
          StatusPill(text = build.status, color = statusColor)
        }
      }
    }
  }
}
