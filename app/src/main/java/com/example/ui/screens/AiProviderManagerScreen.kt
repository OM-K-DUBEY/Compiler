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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.example.ai.provider.AIProviderConfig
import com.example.ai.provider.AuthMethod
import com.example.ai.provider.ConnectionStatus
import com.example.ai.provider.ProviderTestResult
import com.example.ai.provider.ProviderUrlValidator
import com.example.core.preferences.IdePreferences
import com.example.core.preferences.OnlineProviderType
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.OperationFeedback
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiProviderManagerScreen(
  providers: List<AIProviderConfig>,
  preferences: IdePreferences,
  feedback: OperationFeedback?,
  testResults: Map<String, ProviderTestResult>,
  onSaveProvider: (
    id: String?,
    displayName: String,
    providerType: OnlineProviderType,
    baseUrl: String,
    newPlainApiKey: String?,
    clearApiKey: Boolean,
    apiVersion: String,
    authMethod: AuthMethod,
    customAuthHeaderName: String,
    defaultModelId: String,
    requestTimeoutSeconds: Int,
    maxOutputTokens: Int,
    customHeaders: Map<String, String>,
    supportsStreaming: Boolean,
    isEnabled: Boolean,
  ) -> Unit,
  onSetProviderEnabled: (String, Boolean) -> Unit,
  onDeleteProvider: (String) -> Unit,
  onClearApiKey: (String) -> Unit,
  onTestConnection: (String) -> Unit,
  onDiscoverModels: (String) -> Unit,
  onSetDefaultProvider: (String, String) -> Unit,
  onOpenModelSelector: () -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
  var showAddDialog by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<AIProviderConfig?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "ONLINE AI PROVIDERS",
          trailing = {
            OutlinedButton(onClick = onOpenModelSelector, modifier = Modifier.testTag("providers_open_models_btn")) {
              Icon(Icons.Default.Tune, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Models")
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "All provider keys are encrypted with AES-256-GCM in AndroidKeyStore. HTTPS is enforced for public hosts; HTTP is permitted only for localhost/LAN development with an explicit security warning.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { showAddDialog = true },
          modifier = Modifier.fillMaxWidth().testTag("add_ai_provider_btn"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Add AI Provider")
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

    if (providers.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text("No AI Providers Configured", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(4.dp))
          Text(
            "Add Google Gemini, OpenAI, Anthropic, or any Custom OpenAI-compatible / REST endpoint to start chatting and using the Coding Agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(providers, key = { it.id }) { prov ->
      val isDefault = preferences.defaultProviderId == prov.id
      val latestTest = testResults[prov.id]
      IdePanel(modifier = Modifier.fillMaxWidth().testTag("provider_card_${prov.id}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(prov.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              if (isDefault) StatusPill("DEFAULT", MaterialTheme.colorScheme.primary)
            }
            Text(
              "${prov.providerType.displayName} • ${prov.baseUrl}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "Default model: ${prov.defaultModelId.ifBlank { "None" }} • Auth: ${prov.authMethod.displayName} • Key: ${if (prov.hasApiKey) "Encrypted" else "Not set"}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = prov.isEnabled,
            onCheckedChange = { onSetProviderEnabled(prov.id, it) },
          )
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          val statusColor =
            when (prov.lastConnectionStatus) {
              ConnectionStatus.CONNECTED -> ideColors.success
              ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
              ConnectionStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
          StatusPill(prov.lastConnectionStatus.name, statusColor)
          val note = latestTest?.message ?: prov.lastConnectionMessage
          if (note.isNotBlank()) {
            Text(
              note,
              style = MaterialTheme.typography.labelSmall,
              color = if (prov.lastConnectionStatus == ConnectionStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
          }
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(
            onClick = { onTestConnection(prov.id) },
            modifier = Modifier.testTag("test_provider_${prov.id}"),
          ) {
            Icon(Icons.Default.NetworkCheck, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Test Connection")
          }
          OutlinedButton(
            onClick = { onDiscoverModels(prov.id) },
            modifier = Modifier.testTag("discover_models_${prov.id}"),
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Discover Models")
          }
          if (!isDefault) {
            OutlinedButton(
              onClick = {
                val modelComposite =
                  prov.defaultModelId.takeIf { it.isNotBlank() }?.let { "${prov.id}::$it" }.orEmpty()
                onSetDefaultProvider(prov.id, modelComposite)
              },
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Set Default")
            }
          }
          IconButton(onClick = { editingProvider = prov }) {
            Icon(Icons.Default.Edit, contentDescription = "Edit provider")
          }
          if (prov.hasApiKey) {
            IconButton(onClick = { onClearApiKey(prov.id) }) {
              Icon(Icons.Default.KeyOff, contentDescription = "Clear API key")
            }
          }
          IconButton(onClick = { deleteTarget = prov }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete provider", tint = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (showAddDialog || editingProvider != null) {
    ProviderEditorDialog(
      existing = editingProvider,
      onSave = { name, type, url, key, clearKey, apiVer, auth, customHdr, defModel, timeout, maxTok, hdrMap, stream, enabled ->
        onSaveProvider(
          editingProvider?.id,
          name,
          type,
          url,
          key,
          clearKey,
          apiVer,
          auth,
          customHdr,
          defModel,
          timeout,
          maxTok,
          hdrMap,
          stream,
          enabled,
        )
        showAddDialog = false
        editingProvider = null
      },
      onDismiss = {
        showAddDialog = false
        editingProvider = null
      },
    )
  }

  deleteTarget?.let { target ->
    ConfirmActionDialog(
      title = "Delete AI Provider",
      message = "Delete '${target.displayName}', its encrypted API key, and all discovered models associated with it?",
      confirmLabel = "Delete",
      onConfirm = {
        onDeleteProvider(target.id)
        deleteTarget = null
      },
      onDismiss = { deleteTarget = null },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorDialog(
  existing: AIProviderConfig?,
  onSave: (
    displayName: String,
    providerType: OnlineProviderType,
    baseUrl: String,
    newPlainApiKey: String?,
    clearApiKey: Boolean,
    apiVersion: String,
    authMethod: AuthMethod,
    customAuthHeaderName: String,
    defaultModelId: String,
    timeoutSec: Int,
    maxTokens: Int,
    customHeaders: Map<String, String>,
    streaming: Boolean,
    enabled: Boolean,
  ) -> Unit,
  onDismiss: () -> Unit,
) {
  var providerType by remember(existing) { mutableStateOf(existing?.providerType ?: OnlineProviderType.GEMINI) }
  var displayName by remember(existing) { mutableStateOf(existing?.displayName ?: providerType.displayName) }
  var baseUrl by remember(existing) { mutableStateOf(existing?.baseUrl ?: providerType.defaultBaseUrl) }
  var apiKey by remember { mutableStateOf("") }
  var clearKey by remember { mutableStateOf(false) }
  var showApiKey by remember { mutableStateOf(false) }
  var apiVersion by remember(existing) { mutableStateOf(existing?.apiVersion ?: if (providerType == OnlineProviderType.ANTHROPIC) "2023-06-01" else "") }
  var authMethod by remember(existing) { mutableStateOf(existing?.authMethod ?: AIProviderConfig.defaultAuthFor(providerType)) }
  var customAuthHeaderName by remember(existing) { mutableStateOf(existing?.customAuthHeaderName.orEmpty()) }
  var defaultModelId by remember(existing) { mutableStateOf(existing?.defaultModelId ?: providerType.defaultModel) }
  var timeoutText by remember(existing) { mutableStateOf((existing?.requestTimeoutSeconds ?: 60).toString()) }
  var maxTokensText by remember(existing) { mutableStateOf((existing?.maxOutputTokens ?: 4096).toString()) }
  var customHeadersText by remember(existing) {
    mutableStateOf(existing?.customHeaders?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
  }
  var supportsStreaming by remember(existing) { mutableStateOf(existing?.supportsStreaming ?: true) }
  var isEnabled by remember(existing) { mutableStateOf(existing?.isEnabled ?: true) }

  val urlValidation = remember(baseUrl) { ProviderUrlValidator.validate(baseUrl) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (existing == null) "Add AI Provider" else "Edit ${existing.displayName}") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Provider Type", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          OnlineProviderType.entries.forEach { t ->
            FilterChip(
              selected = providerType == t,
              onClick = {
                providerType = t
                if (existing == null) {
                  displayName = t.displayName
                  baseUrl = t.defaultBaseUrl
                  defaultModelId = t.defaultModel
                  authMethod = AIProviderConfig.defaultAuthFor(t)
                  apiVersion = if (t == OnlineProviderType.ANTHROPIC) "2023-06-01" else ""
                }
              },
              label = { Text(t.displayName) },
            )
          }
        }
        OutlinedTextField(
          value = displayName,
          onValueChange = { displayName = it },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("provider_name_input"),
        )
        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it },
          label = { Text("Base URL") },
          singleLine = true,
          isError = baseUrl.isNotBlank() && !urlValidation.isValid,
          modifier = Modifier.fillMaxWidth().testTag("provider_baseurl_input"),
        )
        if (!urlValidation.isValid && baseUrl.isNotBlank()) {
          Text(urlValidation.errorMessage.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } else if (urlValidation.securityWarning != null) {
          Text(urlValidation.securityWarning, style = MaterialTheme.typography.labelSmall, color = LocalIdeColors.current.warning)
        }
        OutlinedTextField(
          value = defaultModelId,
          onValueChange = { defaultModelId = it },
          label = { Text("Default Model ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            if (it.isNotBlank()) clearKey = false
          },
          label = {
            Text(
              when {
                clearKey -> "Stored key will be cleared on Save"
                existing?.hasApiKey == true -> "API Key (Encrypted — enter new key to replace)"
                else -> "API Key (encrypted via AndroidKeyStore)"
              }
            )
          },
          visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
              Icon(
                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (showApiKey) "Hide API key" else "Show API key",
              )
            }
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("provider_apikey_input"),
        )
        if (existing?.hasApiKey == true) {
          TextButton(onClick = { clearKey = !clearKey; if (clearKey) apiKey = "" }) {
            Text(if (clearKey) "Undo Clear Stored Key" else "Clear Stored API Key")
          }
        }
        Text("Authentication Method", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          AuthMethod.entries.forEach { method ->
            FilterChip(
              selected = authMethod == method,
              onClick = { authMethod = method },
              label = { Text(method.displayName) },
            )
          }
        }
        if (authMethod == AuthMethod.CUSTOM_HEADER) {
          OutlinedTextField(
            value = customAuthHeaderName,
            onValueChange = { customAuthHeaderName = it },
            label = { Text("Custom Auth Header Name (e.g. X-Api-Token)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        OutlinedTextField(
          value = apiVersion,
          onValueChange = { apiVersion = it },
          label = { Text("API Version (optional, e.g. 2023-06-01)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = timeoutText,
            onValueChange = { timeoutText = it.filter { ch -> ch.isDigit() } },
            label = { Text("Timeout (s)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = maxTokensText,
            onValueChange = { maxTokensText = it.filter { ch -> ch.isDigit() } },
            label = { Text("Max Output Tok") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        OutlinedTextField(
          value = customHeadersText,
          onValueChange = { customHeadersText = it },
          label = { Text("Custom Headers (Key: Value per line)") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Streaming SSE", style = MaterialTheme.typography.bodySmall)
          Switch(checked = supportsStreaming, onCheckedChange = { supportsStreaming = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("Enabled", style = MaterialTheme.typography.bodySmall)
          Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val parsedHeaders =
            customHeadersText.lines()
              .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
              }
              .toMap()
          onSave(
            displayName,
            providerType,
            baseUrl,
            apiKey.takeIf { it.isNotBlank() },
            clearKey,
            apiVersion,
            authMethod,
            customAuthHeaderName,
            defaultModelId,
            timeoutText.toIntOrNull() ?: 60,
            maxTokensText.toIntOrNull() ?: 4096,
            parsedHeaders,
            supportsStreaming,
            isEnabled,
          )
        },
        enabled = displayName.isNotBlank() && urlValidation.isValid,
        modifier = Modifier.testTag("save_provider_confirm_btn"),
      ) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
