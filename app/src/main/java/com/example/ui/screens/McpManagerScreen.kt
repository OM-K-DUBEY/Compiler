package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.common.Formatters
import com.example.core.database.McpAuditLogEntity
import com.example.mcp.McpAuthType
import com.example.mcp.McpConnectionStatus
import com.example.mcp.McpSecurityPolicy
import com.example.mcp.McpServerConfig
import com.example.mcp.McpToolDefinition
import com.example.mcp.McpTransportType
import com.example.mcp.ToolRiskLevel
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.OperationFeedback
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpManagerScreen(
  servers: List<McpServerConfig>,
  discoveredTools: Map<String, List<McpToolDefinition>>,
  serverLogs: Map<String, List<String>>,
  auditLogs: List<McpAuditLogEntity>,
  feedback: OperationFeedback?,
  onSaveServer: (
    id: String?,
    displayName: String,
    endpointUrl: String,
    transportType: McpTransportType,
    authType: McpAuthType,
    newPlainToken: String?,
    clearToken: Boolean,
    customHeaders: Map<String, String>,
    timeoutSeconds: Int,
    isEnabled: Boolean,
    allowAllTools: Boolean,
  ) -> Unit,
  onSetServerEnabled: (String, Boolean) -> Unit,
  onDeleteServer: (String) -> Unit,
  onConnect: (String) -> Unit,
  onDisconnect: (String) -> Unit,
  onTestConnection: (String) -> Unit,
  onRefreshTools: (String) -> Unit,
  onSetToolPermission: (String, String, Boolean) -> Unit,
  onSetAllowAllTools: (String, Boolean) -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var showAddDialog by remember { mutableStateOf(false) }
  var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
  var deleteTarget by remember { mutableStateOf<McpServerConfig?>(null) }
  var selectedLogsServerId by remember { mutableStateOf<String?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "MCP SERVER MANAGER (MODEL CONTEXT PROTOCOL)")
        Spacer(Modifier.height(6.dp))
        Text(
          "Connect remote Streamable HTTP or Legacy HTTP+SSE MCP servers. Tool permissions default to explicit allowlist. Sensitive tool calls require confirmation before execution and all external tool output is isolated as untrusted data.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { showAddDialog = true },
          modifier = Modifier.fillMaxWidth().testTag("add_mcp_server_btn"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Add MCP Server")
        }
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

    if (servers.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("No MCP Servers Registered", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(4.dp))
          Text(
            "Add an HTTPS MCP endpoint (or localhost HTTP dev server) to discover and authorize tools for the AI Coding Agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(servers, key = { it.id }) { srv ->
      val tools = discoveredTools[srv.id].orEmpty()
      val logs = serverLogs[srv.id].orEmpty()
      val statusColor =
        when (srv.connectionStatus) {
          McpConnectionStatus.CONNECTED -> ideColors.success
          McpConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
          McpConnectionStatus.ERROR, McpConnectionStatus.UNSUPPORTED -> MaterialTheme.colorScheme.error
          McpConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }

      IdePanel(modifier = Modifier.fillMaxWidth().testTag("mcp_server_card_${srv.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(srv.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              StatusPill(srv.connectionStatus.name, statusColor)
            }
            Text(
              "${srv.transportType.displayName} • ${srv.endpointUrl}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (srv.negotiatedProtocolVersion.isNotBlank()) {
              Text(
                "Protocol: ${srv.negotiatedProtocolVersion} • Auth: ${srv.authType.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Switch(checked = srv.isEnabled, onCheckedChange = { onSetServerEnabled(srv.id, it) })
        }

        if (srv.lastError.isNotBlank()) {
          Spacer(Modifier.height(4.dp))
          Text(
            srv.lastError,
            style = MaterialTheme.typography.labelSmall,
            color = if (srv.connectionStatus == McpConnectionStatus.ERROR || srv.connectionStatus == McpConnectionStatus.UNSUPPORTED) {
              MaterialTheme.colorScheme.error
            } else {
              ideColors.warning
            },
          )
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (srv.connectionStatus == McpConnectionStatus.CONNECTED) {
            OutlinedButton(onClick = { onDisconnect(srv.id) }) {
              Icon(Icons.Default.LinkOff, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Disconnect")
            }
            OutlinedButton(onClick = { onRefreshTools(srv.id) }) {
              Icon(Icons.Default.Refresh, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Refresh Tools (${tools.size})")
            }
          } else {
            Button(
              onClick = { onConnect(srv.id) },
              enabled = srv.isEnabled,
              modifier = Modifier.testTag("mcp_connect_${srv.id}"),
            ) {
              Icon(Icons.Default.Link, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Connect")
            }
          }
          OutlinedButton(onClick = { onTestConnection(srv.id) }, enabled = srv.isEnabled) {
            Icon(Icons.Default.NetworkCheck, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Test")
          }
          OutlinedButton(
            onClick = {
              selectedLogsServerId = if (selectedLogsServerId == srv.id) null else srv.id
            },
          ) {
            Text("Logs (${logs.size})")
          }
          IconButton(onClick = { editingServer = srv }) {
            Icon(Icons.Default.Edit, contentDescription = "Edit MCP server")
          }
          IconButton(onClick = { deleteTarget = srv }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete MCP server", tint = MaterialTheme.colorScheme.error)
          }
        }

        if (selectedLogsServerId == srv.id && logs.isNotEmpty()) {
          Spacer(Modifier.height(8.dp))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.background)
              .border(1.dp, ideColors.border)
              .padding(8.dp),
          ) {
            logs.takeLast(12).forEach { line ->
              Text(line, fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelSmall)
            }
          }
        }

        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Allow all tools on this server", style = MaterialTheme.typography.labelMedium)
          Switch(checked = srv.allowAllTools, onCheckedChange = { onSetAllowAllTools(srv.id, it) })
        }

        if (tools.isNotEmpty()) {
          Spacer(Modifier.height(6.dp))
          HorizontalDivider(color = ideColors.border)
          Spacer(Modifier.height(6.dp))
          Text("Discovered Tools (${tools.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          tools.forEach { tool ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text(tool.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                  StatusPill(
                    text = tool.riskLevel.name,
                    color = if (tool.riskLevel == ToolRiskLevel.SENSITIVE) ideColors.warning else ideColors.success,
                  )
                }
                if (tool.description.isNotBlank()) {
                  Text(
                    tool.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = tool.isAllowed || srv.allowAllTools,
                enabled = !srv.allowAllTools,
                onCheckedChange = { allowed -> onSetToolPermission(srv.id, tool.name, allowed) },
              )
            }
          }
        }
      }
    }

    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(title = "MCP AUDIT LOG (${auditLogs.size})")
        Spacer(Modifier.height(6.dp))
        if (auditLogs.isEmpty()) {
          Text(
            "No MCP connection or tool execution events recorded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          auditLogs.take(15).forEachIndexed { idx, log ->
            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = ideColors.border)
            Text(
              "[${log.eventType}] ${log.serverName}${if (log.toolName.isNotBlank()) " / ${log.toolName}" else ""}",
              style = MaterialTheme.typography.labelMedium,
              color = if (log.approvedByUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
              "${log.details} • ${Formatters.formatTimestamp(log.timestampMillis)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }

  if (showAddDialog || editingServer != null) {
    McpServerEditorDialog(
      existing = editingServer,
      onSave = { name, url, transport, auth, token, clearTok, hdrs, timeout, enabled, allowAll ->
        onSaveServer(
          editingServer?.id,
          name,
          url,
          transport,
          auth,
          token,
          clearTok,
          hdrs,
          timeout,
          enabled,
          allowAll,
        )
        showAddDialog = false
        editingServer = null
      },
      onDismiss = {
        showAddDialog = false
        editingServer = null
      },
    )
  }

  deleteTarget?.let { target ->
    ConfirmActionDialog(
      title = "Delete MCP Server",
      message = "Remove '${target.displayName}' and its encrypted credentials?",
      confirmLabel = "Delete",
      onConfirm = {
        onDeleteServer(target.id)
        deleteTarget = null
      },
      onDismiss = { deleteTarget = null },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerEditorDialog(
  existing: McpServerConfig?,
  onSave: (
    name: String,
    url: String,
    transport: McpTransportType,
    auth: McpAuthType,
    token: String?,
    clearToken: Boolean,
    customHeaders: Map<String, String>,
    timeout: Int,
    enabled: Boolean,
    allowAll: Boolean,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  var displayName by remember(existing) { mutableStateOf(existing?.displayName.orEmpty()) }
  var endpointUrl by remember(existing) { mutableStateOf(existing?.endpointUrl ?: "https://") }
  var transport by remember(existing) { mutableStateOf(existing?.transportType ?: McpTransportType.STREAMABLE_HTTP) }
  var authType by remember(existing) { mutableStateOf(existing?.authType ?: McpAuthType.NONE) }
  var token by remember { mutableStateOf("") }
  var clearTok by remember { mutableStateOf(false) }
  var showToken by remember { mutableStateOf(false) }
  var headersText by remember(existing) {
    mutableStateOf(existing?.customHeaders?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
  }
  var timeoutText by remember(existing) { mutableStateOf((existing?.timeoutSeconds ?: 30).toString()) }
  var enabled by remember(existing) { mutableStateOf(existing?.isEnabled ?: true) }
  var allowAll by remember(existing) { mutableStateOf(existing?.allowAllTools ?: false) }

  val urlValidation =
    remember(endpointUrl, transport) {
      McpSecurityPolicy.validateServerUrl(
        McpServerConfig(
          id = "",
          displayName = displayName,
          endpointUrl = endpointUrl,
          transportType = transport,
        )
      )
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existing == null) "Add MCP Server" else "Edit ${existing.displayName}") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = displayName,
          onValueChange = { displayName = it },
          label = { Text("Server Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("mcp_name_input"),
        )
        Text("Transport Protocol", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          McpTransportType.entries.forEach { t ->
            FilterChip(
              selected = transport == t,
              onClick = { transport = t },
              label = { Text(t.displayName) },
            )
          }
        }
        if (transport == McpTransportType.STDIO_LOCAL) {
          Text(
            "Android sandbox notice: Standard desktop local-subprocess stdio MCP servers are unavailable on unrooted Android. DevForge keeps STDIO honest and fails explicitly on connect.",
            style = MaterialTheme.typography.labelSmall,
            color = LocalIdeColors.current.warning,
          )
        } else {
          OutlinedTextField(
            value = endpointUrl,
            onValueChange = { endpointUrl = it },
            label = { Text("Endpoint URL (HTTPS or local dev HTTP)") },
            singleLine = true,
            isError = !urlValidation.first,
            modifier = Modifier.fillMaxWidth().testTag("mcp_url_input"),
          )
          if (urlValidation.second != null) {
            Text(
              urlValidation.second.orEmpty(),
              style = MaterialTheme.typography.labelSmall,
              color = if (urlValidation.first) LocalIdeColors.current.warning else MaterialTheme.colorScheme.error,
            )
          }
        }
        Text("Authentication", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          McpAuthType.entries.forEach { a ->
            FilterChip(selected = authType == a, onClick = { authType = a }, label = { Text(a.displayName) })
          }
        }
        if (authType != McpAuthType.NONE) {
          OutlinedTextField(
            value = token,
            onValueChange = {
              token = it
              if (it.isNotBlank()) clearTok = false
            },
            label = {
              Text(
                when {
                  clearTok -> "Stored token will be cleared on Save"
                  existing?.hasToken == true -> "Token (Encrypted — enter to replace)"
                  else -> "Bearer Token / API Key"
                }
              )
            },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { showToken = !showToken }) {
                Icon(
                  imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                  contentDescription = if (showToken) "Hide token" else "Show token",
                )
              }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          if (existing?.hasToken == true) {
            TextButton(onClick = { clearTok = !clearTok; if (clearTok) token = "" }) {
              Text(if (clearTok) "Undo Clear Stored Token" else "Clear Stored Token")
            }
          }
        }
        OutlinedTextField(
          value = headersText,
          onValueChange = { headersText = it },
          label = { Text("Custom Headers (Key: Value per line)") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = timeoutText,
          onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
          label = { Text("Request Timeout (seconds)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Enabled", style = MaterialTheme.typography.bodySmall)
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Allow all tools (less restrictive)", style = MaterialTheme.typography.bodySmall)
          Switch(checked = allowAll, onCheckedChange = { allowAll = it })
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val parsedHeaders =
            headersText.lines()
              .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
              }
              .toMap()
          onSave(
            displayName,
            endpointUrl,
            transport,
            authType,
            token.takeIf { it.isNotBlank() },
            clearTok,
            parsedHeaders,
            timeoutText.toIntOrNull() ?: 30,
            enabled,
            allowAll,
          )
        },
        enabled = displayName.isNotBlank() && urlValidation.first,
        modifier = Modifier.testTag("save_mcp_confirm_btn"),
      ) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
