package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.core.database.MappingVaultEntity
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.DecryptedMappingPreview
import com.example.ui.state.OperationFeedback
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@Composable
fun MappingVaultScreen(
  entries: List<MappingVaultEntity>,
  decryptedPreview: DecryptedMappingPreview?,
  feedback: OperationFeedback?,
  onDecryptAndPreview: (MappingVaultEntity) -> Unit,
  onDismissPreview: () -> Unit,
  onDeleteEntry: (String) -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var deleteTarget by remember { mutableStateOf<MappingVaultEntity?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "ENCRYPTED MAPPING & SYMBOL VAULT",
          trailing = { StatusPill("AES-256-GCM", ideColors.success) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Private R8 mapping files, Dart debug symbols, native symbol tables, and JS source maps are encrypted at rest via AndroidKeyStore and stored strictly outside public build outputs.",
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

    if (entries.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("Vault Is Empty", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(4.dp))
          Text(
            "Run a Release or Balanced/Strong protected build that produces mapping/symbol files to archive them securely here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(entries, key = { it.id }) { entry ->
      IdePanel(modifier = Modifier.fillMaxWidth().testTag("vault_entry_${entry.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("${entry.originalFileName} (${entry.toolName})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              "${entry.applicationId} • v${entry.versionName} • ${entry.buildVariant} • ${entry.artifactType}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "${Formatters.formatBytes(entry.sizeBytes)} • SHA-256 ${entry.sha256.take(14)}... • ${Formatters.formatTimestamp(entry.createdAtMillis)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          StatusPill("LOCKED", MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedButton(
            onClick = { onDecryptAndPreview(entry) },
            modifier = Modifier.weight(1f).testTag("decrypt_mapping_${entry.id}"),
          ) {
            Icon(Icons.Default.LockOpen, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Decrypt & Inspect")
          }
          IconButton(onClick = { deleteTarget = entry }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete mapping entry", tint = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  decryptedPreview?.let { preview ->
    AlertDialog(
      onDismissRequest = onDismissPreview,
      title = { Text(preview.title) },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("SHA-256: ${preview.sha256}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
          Text(
            preview.contentPreview.ifBlank { "(Empty mapping file)" },
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      },
      confirmButton = { TextButton(onClick = onDismissPreview) { Text("Close") } },
    )
  }

  deleteTarget?.let { target ->
    ConfirmActionDialog(
      title = "Delete Vault Entry",
      message = "Permanently delete encrypted mapping '${target.originalFileName}'? Crash stack traces for build #${target.buildId} can no longer be deobfuscated without another backup.",
      confirmLabel = "Delete",
      onConfirm = {
        onDeleteEntry(target.id)
        deleteTarget = null
      },
      onDismiss = { deleteTarget = null },
    )
  }
}
