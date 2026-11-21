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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.core.common.DeviceHardwareSnapshot
import com.example.core.common.Formatters
import com.example.sdk.SdkCategory
import com.example.sdk.SdkPackageSpec
import com.example.sdk.SdkPackageUiState
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SdkManagerScreen(
  packages: List<SdkPackageUiState>,
  hardware: DeviceHardwareSnapshot,
  sdkStorageBytes: Long,
  reachabilityMap: Map<String, String>,
  onInstall: (SdkPackageSpec) -> Unit,
  onCancelDownload: (String) -> Unit,
  onUninstall: (String) -> Unit,
  onCheckReachability: (SdkPackageSpec) -> Unit,
  onRefreshHardware: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var selectedCategory by remember { mutableStateOf<SdkCategory?>(null) }

  val filtered =
    remember(packages, selectedCategory) {
      if (selectedCategory == null) packages else packages.filter { it.spec.category == selectedCategory }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "TOOLCHAIN STORAGE & RUNTIME ENVIRONMENT",
          trailing = {
            IconButton(onClick = onRefreshHardware) {
              Icon(Icons.Default.Refresh, contentDescription = "Refresh SDK storage")
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "SDK Cache Used: ${Formatters.formatBytes(sdkStorageBytes)} • Free Storage: ${Formatters.formatBytes(hardware.availableStorageBytes)} / ${Formatters.formatBytes(hardware.totalStorageBytes)}",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          "Device ABIs: ${hardware.supportedAbis.joinToString(", ")} • Android API ${hardware.androidSdkInt}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Android 10+ SELinux prevents direct execution of downloaded native ELF binaries inside app-private storage. DevForge probes PATH & custom toolchain directories for real executable binaries and clearly indicates device compatibility per package.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
          selected = selectedCategory == null,
          onClick = { selectedCategory = null },
          label = { Text("All (${packages.size})") },
        )
        SdkCategory.entries.forEach { cat ->
          FilterChip(
            selected = selectedCategory == cat,
            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
            label = { Text(cat.label) },
          )
        }
      }
    }

    items(filtered, key = { it.spec.id }) { item ->
      val spec = item.spec
      IdePanel(modifier = Modifier.fillMaxWidth().testTag("sdk_card_${spec.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              "v${spec.version} • ~${Formatters.formatBytes(spec.approximateSizeBytes)} • probe: ${spec.executableProbeName}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          when {
            item.isSystemDetected -> StatusPill("SYSTEM EXEC", ideColors.success)
            item.isInstalledLocally && item.isExecutableOnDevice -> StatusPill("INSTALLED EXEC", ideColors.success)
            item.isInstalledLocally -> StatusPill("ARCHIVE SAVED", ideColors.warning)
            else -> StatusPill("NOT INSTALLED", MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }

        Spacer(Modifier.height(6.dp))
        Text(spec.androidExecutionNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        item.systemBinaryPath?.let { path ->
          Spacer(Modifier.height(4.dp))
          Text("System binary: $path", style = MaterialTheme.typography.labelSmall, color = ideColors.success)
        }
        item.recordedSha256?.takeIf { it.isNotBlank() }?.let { sha ->
          Spacer(Modifier.height(4.dp))
          Text("SHA-256: $sha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        reachabilityMap[spec.id]?.let { reach ->
          Spacer(Modifier.height(4.dp))
          Text("Remote check: $reach", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(8.dp))
        if (item.isDownloading) {
          LinearProgressIndicator(progress = { item.downloadProgress }, modifier = Modifier.fillMaxWidth())
          Spacer(Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              "${item.statusMessage ?: "Downloading"} ${Formatters.formatBytes(item.downloadedBytes)} / ${Formatters.formatBytes(item.totalBytes)}",
              style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(onClick = { onCancelDownload(spec.id) }) {
              Icon(Icons.Default.Close, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Cancel")
            }
          }
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!item.isInstalledLocally) {
              Button(
                onClick = { onInstall(spec) },
                modifier = Modifier.testTag("sdk_install_${spec.id}"),
              ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Download & Verify")
              }
            } else {
              OutlinedButton(onClick = { onInstall(spec) }) {
                Text("Re-verify / Update")
              }
              OutlinedButton(onClick = { onUninstall(spec.id) }) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("Remove", color = MaterialTheme.colorScheme.error)
              }
            }
            OutlinedButton(onClick = { onCheckReachability(spec) }) {
              Text("Check Mirror")
            }
          }
        }
      }
    }
  }
}
