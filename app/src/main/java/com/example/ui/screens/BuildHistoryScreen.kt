package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.compiler.BuildErrorDiagnosticClassifier
import com.example.core.common.Formatters
import com.example.core.database.BuildHistoryEntity
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildHistoryScreen(
  history: List<BuildHistoryEntity>,
  onNavigateToFileLine: (String, Int) -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var statusFilter by remember { mutableStateOf<String?>(null) }
  var expandedId by remember { mutableStateOf<Long?>(null) }

  val filtered =
    remember(history, statusFilter) {
      if (statusFilter == null) history else history.filter { it.status == statusFilter }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "BUILD HISTORY (${history.size})")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          FilterChip(
            selected = statusFilter == null,
            onClick = { statusFilter = null },
            label = { Text("All (${history.size})") },
          )
          listOf("SUCCEEDED", "FAILED", "UNAVAILABLE", "CANCELLED").forEach { st ->
            FilterChip(
              selected = statusFilter == st,
              onClick = { statusFilter = if (statusFilter == st) null else st },
              label = { Text(st) },
            )
          }
        }
      }
    }

    if (filtered.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text(
            "No matching build records found.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(filtered, key = { it.id }) { item ->
      val isExpanded = expandedId == item.id
      val statusColor =
        when (item.status) {
          "SUCCEEDED" -> ideColors.success
          "FAILED" -> MaterialTheme.colorScheme.error
          else -> ideColors.warning
        }

      IdePanel(modifier = Modifier.fillMaxWidth().testTag("build_history_card_${item.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${item.projectName} — ${item.buildSystem}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              "${item.buildType} • ${Formatters.formatDurationMs(item.durationMillis)} • E:${item.errorCount} W:${item.warningCount} • ${Formatters.formatTimestamp(item.timestampMillis)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          StatusPill(item.status, statusColor)
        }

        item.artifactPath?.let { path ->
          Spacer(Modifier.height(4.dp))
          Text("Artifact: $path", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { expandedId = if (isExpanded) null else item.id }) {
          Text(if (isExpanded) "Hide Raw Output & Diagnostics" else "Inspect Logs & Diagnostics")
        }

        if (isExpanded) {
          val diagnostics = remember(item.id, item.outputLog) { BuildErrorDiagnosticClassifier.classify(item.outputLog) }
          if (diagnostics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Classified Diagnostics (${diagnostics.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            diagnostics.take(10).forEach { diag ->
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
          Spacer(Modifier.height(8.dp))
          Text("Raw Output", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.background)
              .border(1.dp, ideColors.border)
              .padding(8.dp),
          ) {
            Text(
              item.outputLog.ifBlank { "(Empty log)" }.take(6000),
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }
    }
  }
}
