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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.example.core.common.Formatters
import com.example.core.database.ProjectEntity
import com.example.core.storage.BackupRecord
import com.example.core.storage.ProjectTemplateType
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.ProjectsUiState
import com.example.ui.theme.LocalIdeColors
import java.io.File

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
  projects: List<ProjectEntity>,
  activeProjectId: String?,
  uiState: ProjectsUiState,
  onCreateProject: (String, String, ProjectTemplateType) -> Unit,
  onImportZip: (Uri, String) -> Unit,
  onExportZip: (ProjectEntity, Uri) -> Unit,
  onOpenProjectInEditor: (String) -> Unit,
  onRenameProject: (String, String) -> Unit,
  onDuplicateProject: (String) -> Unit,
  onDeleteProject: (String) -> Unit,
  onCreateBackup: (ProjectEntity) -> Unit,
  onRestoreBackup: (ProjectEntity, File) -> Unit,
  onLoadBackups: (String) -> Unit,
  onClearBanner: () -> Unit,
  onOpenImportScreen: () -> Unit,
) {
  val context = LocalContext.current
  val ideColors = LocalIdeColors.current

  var showCreateDialog by remember { mutableStateOf(false) }
  var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
  var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }
  var backupsTarget by remember { mutableStateOf<ProjectEntity?>(null) }
  var exportTarget by remember { mutableStateOf<ProjectEntity?>(null) }

  val importZipLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        val nameHint =
          runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
              val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
          }.getOrNull() ?: "ImportedProject.zip"
        onImportZip(uri, nameHint)
      }
    }

  val exportZipLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
      val target = exportTarget
      if (uri != null && target != null) {
        onExportZip(target, uri)
      }
      exportTarget = null
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = { showCreateDialog = true },
          modifier = Modifier.weight(1f).testTag("create_project_button"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("New Project")
        }
        FilledTonalButton(
          onClick = { importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
          modifier = Modifier.weight(1f).testTag("import_zip_button"),
        ) {
          Icon(Icons.Default.FileUpload, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Import ZIP")
        }
        OutlinedButton(
          onClick = onOpenImportScreen,
          modifier = Modifier.testTag("open_project_import_screen_btn"),
        ) {
          Text("Wizard")
        }
      }
    }

    if (uiState.isLoading) {
      item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }

    if (uiState.statusMessage != null || uiState.errorMessage != null) {
      item {
        val isError = uiState.errorMessage != null
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = uiState.errorMessage ?: uiState.statusMessage.orEmpty(),
              color = if (isError) MaterialTheme.colorScheme.error else ideColors.success,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearBanner) { Text("Dismiss") }
          }
        }
      }
    }

    if (projects.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("No local projects", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(4.dp))
          Text(
            "Create a starter Android, Kotlin, Java, Flutter, or Web workspace, or import an existing ZIP securely via Storage Access Framework.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(projects, key = { it.id }) { proj ->
      val isActive = proj.id == activeProjectId
      IdePanel(modifier = Modifier.fillMaxWidth().testTag("project_card_${proj.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(proj.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              if (isActive) {
                StatusPill(text = "ACTIVE", color = ideColors.success)
              }
            }
            Text(
              "${proj.projectType} • ${proj.packageName}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "${proj.fileCount} files • ${Formatters.formatBytes(proj.sizeBytes)} • Modified ${Formatters.formatTimestamp(proj.lastModifiedMillis)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Button(
            onClick = { onOpenProjectInEditor(proj.id) },
            modifier = Modifier.testTag("open_project_${proj.id}"),
          ) {
            Icon(Icons.Default.Code, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Open")
          }
          OutlinedButton(
            onClick = {
              exportTarget = proj
              exportZipLauncher.launch("${proj.name.replace(' ', '_')}.zip")
            },
          ) {
            Icon(Icons.Default.Archive, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Export ZIP")
          }
          OutlinedButton(
            onClick = {
              onLoadBackups(proj.id)
              backupsTarget = proj
            },
          ) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Backups")
          }
          IconButton(onClick = { renameTarget = proj }) {
            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename project")
          }
          IconButton(onClick = { onDuplicateProject(proj.id) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate project")
          }
          IconButton(onClick = { deleteTarget = proj }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete project", tint = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateProjectDialog(
      onCreate = { name, pkg, template ->
        showCreateDialog = false
        onCreateProject(name, pkg, template)
      },
      onDismiss = { showCreateDialog = false },
    )
  }

  renameTarget?.let { target ->
    var newName by remember(target.id) { mutableStateOf(target.name) }
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      title = { Text("Rename Project") },
      text = {
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("Project Name") },
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onRenameProject(target.id, newName)
            renameTarget = null
          },
        ) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
      },
    )
  }

  deleteTarget?.let { target ->
    ConfirmActionDialog(
      title = "Delete Project",
      message = "Permanently delete '${target.name}' and all files in its workspace?",
      confirmLabel = "Delete",
      onConfirm = {
        onDeleteProject(target.id)
        deleteTarget = null
      },
      onDismiss = { deleteTarget = null },
    )
  }

  backupsTarget?.let { target ->
    BackupsDialog(
      project = target,
      backups = uiState.backupsForSelected.filter { it.projectId == target.id },
      onCreateBackup = { onCreateBackup(target) },
      onRestoreBackup = { backup -> onRestoreBackup(target, backup.file) },
      onDismiss = { backupsTarget = null },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProjectDialog(
  onCreate: (String, String, ProjectTemplateType) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf("MyDevApp") }
  var packageName by remember { mutableStateOf("com.devforge.myapp") }
  var template by remember { mutableStateOf(ProjectTemplateType.ANDROID_KOTLIN) }
  var expanded by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create New Project") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Project Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_project_name_input"),
        )
        OutlinedTextField(
          value = packageName,
          onValueChange = { packageName = it },
          label = { Text("Package / Namespace") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_project_package_input"),
        )
        ExposedDropdownMenuBox(
          expanded = expanded,
          onExpandedChange = { expanded = !expanded },
        ) {
          OutlinedTextField(
            value = template.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Project Template") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
          )
          ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProjectTemplateType.entries.forEach { item ->
              DropdownMenuItem(
                text = { Text("${item.displayName} (${item.primaryLanguage})") },
                onClick = {
                  template = item
                  expanded = false
                },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(name, packageName, template) },
        modifier = Modifier.testTag("confirm_create_project_btn"),
      ) { Text("Create") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun BackupsDialog(
  project: ProjectEntity,
  backups: List<BackupRecord>,
  onCreateBackup: () -> Unit,
  onRestoreBackup: (BackupRecord) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Backups — ${project.name}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCreateBackup, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.Backup, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Create SHA-256 Snapshot")
        }
        IdeSectionHeader(title = "LOCAL SNAPSHOTS (${backups.size})")
        if (backups.isEmpty()) {
          Text("No backups yet for this project.", style = MaterialTheme.typography.bodySmall)
        } else {
          backups.take(5).forEach { rec ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(Formatters.formatTimestamp(rec.createdAtMillis), style = MaterialTheme.typography.labelMedium)
                Text(
                  "${Formatters.formatBytes(rec.sizeBytes)} • SHA ${rec.sha256.take(10)}...",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              OutlinedButton(onClick = { onRestoreBackup(rec) }) {
                Icon(Icons.Default.Restore, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Restore")
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
