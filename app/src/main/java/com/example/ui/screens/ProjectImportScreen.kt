package com.example.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.example.core.security.ZipPolicyLimits
import com.example.core.storage.ProjectTemplateType
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.ProjectsUiState
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectImportScreen(
  uiState: ProjectsUiState,
  onImportZip: (Uri, String) -> Unit,
  onCreateProject: (String, String, ProjectTemplateType) -> Unit,
  onClearBanner: () -> Unit,
  onOpenEditor: () -> Unit,
) {
  val context = LocalContext.current
  val ideColors = LocalIdeColors.current
  val defaultLimits = remember { ZipPolicyLimits() }

  var projectName by remember { mutableStateOf("DevStarterApp") }
  var packageName by remember { mutableStateOf("com.devforge.starter") }
  var selectedTemplate by remember { mutableStateOf(ProjectTemplateType.ANDROID_KOTLIN) }

  val importZipLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        val nameHint =
          runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
              val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
          }.getOrNull() ?: "ImportedWorkspace.zip"
        onImportZip(uri, nameHint)
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (uiState.isLoading) {
      item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }

    if (uiState.statusMessage != null || uiState.errorMessage != null) {
      item {
        val isErr = uiState.errorMessage != null
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = uiState.errorMessage ?: uiState.statusMessage.orEmpty(),
              color = if (isErr) MaterialTheme.colorScheme.error else ideColors.success,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            if (!isErr) {
              OutlinedButton(onClick = onOpenEditor) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Open Editor")
              }
            }
            TextButton(onClick = onClearBanner) { Text("Dismiss") }
          }
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "IMPORT ZIP ARCHIVE (SAF)",
          trailing = { StatusPill("HARDENED", ideColors.success) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "Select a project ZIP from device storage or cloud document providers. DevForge validates every ZIP entry before writing to the sandbox.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Default.Security, contentDescription = null, tint = ideColors.success)
          Text(
            "Security Policy: Max ${defaultLimits.maxEntries} entries • Max ${defaultLimits.maxTotalUncompressedBytes / (1024 * 1024)} MB uncompressed • Zip-Slip & symlink traversal blocked.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(10.dp))
        Button(
          onClick = { importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
          modifier = Modifier.fillMaxWidth().testTag("project_import_zip_btn"),
        ) {
          Icon(Icons.Default.FileUpload, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Choose ZIP Archive")
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "CREATE FROM TEMPLATE")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = projectName,
          onValueChange = { projectName = it },
          label = { Text("Workspace Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("import_template_name_input"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = packageName,
          onValueChange = { packageName = it },
          label = { Text("Package / Bundle ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("import_template_package_input"),
        )
        Spacer(Modifier.height(10.dp))
        Text("Language / Framework Template", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ProjectTemplateType.entries.forEach { tmpl ->
            FilterChip(
              selected = selectedTemplate == tmpl,
              onClick = { selectedTemplate = tmpl },
              label = { Text("${tmpl.displayName} (${tmpl.primaryLanguage})") },
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Button(
          onClick = { onCreateProject(projectName, packageName, selectedTemplate) },
          enabled = projectName.isNotBlank(),
          modifier = Modifier.fillMaxWidth().testTag("import_create_template_btn"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Initialize ${selectedTemplate.displayName} Workspace")
        }
      }
    }
  }
}
