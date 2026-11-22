package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import com.example.compiler.BuildStatus
import com.example.compiler.BuildSystemType
import com.example.compiler.BuildVariant
import com.example.core.common.Formatters
import com.example.core.database.BuildHistoryEntity
import com.example.core.database.ProjectEntity
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.WebPreviewDialog
import com.example.ui.state.BuildUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildManagerScreen(
  activeProject: ProjectEntity?,
  buildState: BuildUiState,
  isBuilding: Boolean,
  liveLogs: String,
  history: List<BuildHistoryEntity>,
  onRefreshDetection: () -> Unit,
  onSelectSystem: (BuildSystemType) -> Unit,
  onSelectVariant: (BuildVariant) -> Unit,
  onSetSignRelease: (Boolean) -> Unit,
  onRunBuild: () -> Unit,
  onEnqueueBackgroundBuild: () -> Unit,
  onCancelBuild: () -> Unit,
  onOpenWebPreview: () -> Unit,
  onCloseWebPreview: () -> Unit,
  onExportArtifact: (String, Uri) -> Unit,
  onNavigateToFileLine: (String, Int) -> Unit,
  onOpenProtection: () -> Unit,
  onOpenBuildHistory: () -> Unit,
) {
  val context = LocalContext.current
  val ideColors = LocalIdeColors.current
  var pendingExportPath by remember { mutableStateOf<String?>(null) }

  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
      val src = pendingExportPath
      if (uri != null && src != null) {
        onExportArtifact(src, uri)
      }
      pendingExportPath = null
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "BUILD CONFIGURATION",
          trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              OutlinedButton(onClick = onOpenProtection, modifier = Modifier.testTag("build_open_protection_btn")) {
                Text("Protection")
              }
              IconButton(onClick = onRefreshDetection) {
                Icon(Icons.Default.Refresh, contentDescription = "Re-detect build systems")
              }
            }
          },
        )
        if (activeProject == null) {
          Text(
            "Open a project first to inspect detected compilers and run builds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(activeProject.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(6.dp))
          if (buildState.detectedSystems.isEmpty()) {
            Text(
              "No supported build system detected in ${activeProject.name}.",
              style = MaterialTheme.typography.bodySmall,
              color = ideColors.warning,
            )
          } else {
            Text("Detected Adapters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              buildState.detectedSystems.forEach { sys ->
                FilterChip(
                  selected = buildState.selectedSystem == sys,
                  onClick = { onSelectSystem(sys) },
                  label = { Text(sys.displayName) },
                )
              }
            }
          }

          Spacer(Modifier.height(8.dp))
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BuildVariant.entries.forEach { variant ->
              FilterChip(
                selected = buildState.selectedVariant == variant,
                onClick = { onSelectVariant(variant) },
                label = { Text(variant.label) },
              )
            }
            if (buildState.selectedVariant == BuildVariant.RELEASE || buildState.selectedVariant == BuildVariant.RELEASE_AAB) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sign Output", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Switch(checked = buildState.signRelease, onCheckedChange = onSetSignRelease)
              }
            }
          }

          Spacer(Modifier.height(10.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isBuilding) {
              Button(
                onClick = onCancelBuild,
                modifier = Modifier.testTag("cancel_build_btn"),
              ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Cancel Build")
              }
            } else {
              Button(
                onClick = onRunBuild,
                enabled = buildState.selectedSystem != null,
                modifier = Modifier.testTag("run_build_btn"),
              ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Build Project")
              }
              OutlinedButton(
                onClick = onEnqueueBackgroundBuild,
                enabled = buildState.selectedSystem != null,
                modifier = Modifier.testTag("bg_build_btn"),
              ) {
                Text("Queue Background")
              }
            }
            if (BuildSystemType.WEB_STATIC in buildState.detectedSystems) {
              OutlinedButton(onClick = onOpenWebPreview, modifier = Modifier.testTag("launch_web_preview_btn")) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Web Runtime")
              }
            }
          }
          if (isBuilding) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
        }
      }
    }

    buildState.lastResult?.let { result ->
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Last Build Result", style = MaterialTheme.typography.titleSmall)
            val color =
              when (result.status) {
                BuildStatus.SUCCEEDED -> ideColors.success
                BuildStatus.FAILED -> MaterialTheme.colorScheme.error
                BuildStatus.CANCELLED, BuildStatus.UNAVAILABLE -> ideColors.warning
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              }
            StatusPill(result.status.label, color)
          }
          Spacer(Modifier.height(4.dp))
          Text(
            "Duration: ${Formatters.formatDurationMs(result.durationMillis)} • Errors: ${result.errorCount} • Warnings: ${result.warningCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          result.limitationReason?.let { reason ->
            Spacer(Modifier.height(6.dp))
            Text(reason, style = MaterialTheme.typography.bodySmall, color = ideColors.warning)
          }

          result.protectionReport?.let { rep ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              StatusPill(
                text = if (rep.artifactLeakCheckPassed) "NO LEAKS" else "LEAK FOUND",
                color = if (rep.artifactLeakCheckPassed) ideColors.success else MaterialTheme.colorScheme.error,
              )
              Text(rep.summaryText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            }
          }

          if (result.diagnostics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Classified Diagnostics (${result.diagnostics.size})", style = MaterialTheme.typography.labelMedium)
            result.diagnostics.take(8).forEach { diag ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 3.dp)
                  .background(MaterialTheme.colorScheme.background)
                  .border(1.dp, ideColors.border)
                  .let { mod ->
                    if (diag.filePath != null) mod.clickable { onNavigateToFileLine(diag.filePath, diag.line ?: 1) }
                    else mod
                  }
                  .padding(6.dp),
              ) {
                Text("${diag.category.label}: ${diag.suggestedFix}", style = MaterialTheme.typography.labelSmall, color = ideColors.warning)
                Text(diag.rawExcerpt, fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelSmall)
              }
            }
          }

          if (result.artifacts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Verified Output Artifacts", style = MaterialTheme.typography.labelMedium)
            result.artifacts.forEach { art ->
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(art.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    StatusPill(
                      text = if (art.isVerifiedClean) "CLEAN" else "WARNING",
                      color = if (art.isVerifiedClean) ideColors.success else MaterialTheme.colorScheme.error,
                    )
                  }
                  Text(
                    "${art.kind} • ${Formatters.formatBytes(art.sizeBytes)} • ${if (art.isSigned) "RSA-Signed" else "Unsigned"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(
                  onClick = {
                    pendingExportPath = art.path
                    exportLauncher.launch(art.name)
                  },
                ) {
                  Icon(Icons.Default.FileDownload, contentDescription = "Export ${art.name}")
                }
                IconButton(
                  onClick = {
                    val file = File(art.path)
                    if (file.exists()) {
                      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                      val intent =
                        Intent(Intent.ACTION_SEND).apply {
                          type = "application/octet-stream"
                          putExtra(Intent.EXTRA_STREAM, uri)
                          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                      context.startActivity(Intent.createChooser(intent, "Share ${art.name}"))
                    }
                  },
                ) {
                  Icon(Icons.Default.Share, contentDescription = "Share ${art.name}")
                }
              }
            }
          }
        }
      }
    }

    item {
      val textToShow = liveLogs.ifBlank { "Run a build to inspect real compiler or static pre-flight logs." }
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "COMPILER & PRE-FLIGHT OUTPUT",
          trailing = {
            IconButton(
              onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("DevForge Build Logs", textToShow))
              },
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = "Copy build logs")
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, ideColors.border)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        ) {
          textToShow.lines().forEach { line ->
            val color =
              when {
                line.contains("[ERROR]") || line.contains("error:") -> MaterialTheme.colorScheme.error
                line.contains("[WARNING]") || line.contains("UNAVAILABLE") -> ideColors.warning
                line.contains("succeeded") -> ideColors.success
                else -> MaterialTheme.colorScheme.onBackground
              }
            Text(
              line,
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelSmall,
              color = color,
            )
          }
        }
      }
    }

    if (history.isNotEmpty()) {
      item {
        IdeSectionHeader(
          title = "BUILD HISTORY (${history.size})",
          trailing = {
            OutlinedButton(onClick = onOpenBuildHistory) { Text("Full History") }
          },
        )
      }
      items(history.take(10), key = { it.id }) { item ->
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("${item.projectName} — ${item.buildSystem}", style = MaterialTheme.typography.titleSmall)
              Text(
                "${item.buildType} • ${Formatters.formatDurationMs(item.durationMillis)} • ${Formatters.formatTimestamp(item.timestampMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            StatusPill(
              text = item.status,
              color =
                when (item.status) {
                  "SUCCEEDED" -> ideColors.success
                  "FAILED" -> MaterialTheme.colorScheme.error
                  else -> ideColors.warning
                },
            )
          }
        }
      }
    }
  }

  buildState.webPreviewEntryPath?.let { path ->
    WebPreviewDialog(entryHtmlPath = path, onDismiss = onCloseWebPreview)
  }
}
