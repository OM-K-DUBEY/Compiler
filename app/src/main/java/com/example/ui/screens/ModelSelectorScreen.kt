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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.provider.AIModel
import com.example.ai.provider.AIProviderConfig
import com.example.ai.provider.CapabilitySupport
import com.example.ai.provider.ModelAvailabilityStatus
import com.example.core.preferences.IdePreferences
import com.example.ui.components.IdePanel
import com.example.ui.components.IdeSectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.state.OperationFeedback
import com.example.ui.theme.LocalIdeColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSelectorScreen(
  providers: List<AIProviderConfig>,
  models: List<AIModel>,
  preferences: IdePreferences,
  activeConversationKey: String,
  conversationModels: Map<String, String>,
  feedback: OperationFeedback?,
  onDiscoverModels: (String) -> Unit,
  onAddManualModel: (
    providerId: String,
    modelId: String,
    displayName: String,
    contextWindow: Int?,
    maxOutputTokens: Int?,
    vision: CapabilitySupport,
    tools: CapabilitySupport,
    streaming: CapabilitySupport,
  ) -> Unit,
  onVerifyModel: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
  onSetDefaultChatModel: (String, String) -> Unit,
  onSetAgentModel: (String) -> Unit,
  onSetConversationModel: (String) -> Unit,
) {
  val ideColors = LocalIdeColors.current
  var selectedProviderId by remember { mutableStateOf<String?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  var showAddManualDialog by remember { mutableStateOf(false) }

  val providerMap = remember(providers) { providers.associateBy { it.id } }
  val activeConvComposite = conversationModels[activeConversationKey]

  val filteredModels =
    remember(models, selectedProviderId, searchQuery) {
      models.filter { m ->
        (selectedProviderId == null || m.providerId == selectedProviderId) &&
          (searchQuery.isBlank() ||
            m.modelId.contains(searchQuery, ignoreCase = true) ||
            m.displayName.contains(searchQuery, ignoreCase = true))
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      IdePanel(modifier = Modifier.fillMaxWidth()) {
        IdeSectionHeader(
          title = "MODEL REGISTRY & SWITCHING",
          trailing = {
            Button(
              onClick = { showAddManualDialog = true },
              enabled = providers.isNotEmpty(),
              modifier = Modifier.testTag("add_manual_model_btn"),
            ) {
              Icon(Icons.Default.Add, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("Manual Model")
            }
          },
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Default Chat: ${preferences.defaultChatModelCompositeId ?: "Provider Default"} • Coding Agent: ${preferences.agentModelCompositeId ?: "Same as Chat"}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          label = { Text("Search discovered or manual models...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("model_search_input"),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          FilterChip(
            selected = selectedProviderId == null,
            onClick = { selectedProviderId = null },
            label = { Text("All Providers (${models.size})") },
          )
          providers.forEach { p ->
            FilterChip(
              selected = selectedProviderId == p.id,
              onClick = { selectedProviderId = if (selectedProviderId == p.id) null else p.id },
              label = { Text(p.displayName) },
            )
          }
        }
        selectedProviderId?.let { pId ->
          Spacer(Modifier.height(6.dp))
          OutlinedButton(onClick = { onDiscoverModels(pId) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Refresh Models From ${providerMap[pId]?.displayName ?: "Provider"}")
          }
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

    if (filteredModels.isEmpty()) {
      item {
        IdePanel(modifier = Modifier.fillMaxWidth()) {
          Text(
            "No models match the filter. Add a provider and tap Discover Models or add a custom model ID manually.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    items(filteredModels, key = { it.compositeId }) { model ->
      val provName = providerMap[model.providerId]?.displayName ?: model.providerId
      val isDefaultChat = preferences.defaultChatModelCompositeId == model.compositeId
      val isAgent = preferences.agentModelCompositeId == model.compositeId
      val isConv = activeConvComposite == model.compositeId
      val statusColor =
        when (model.availabilityStatus) {
          ModelAvailabilityStatus.VERIFIED -> ideColors.success
          ModelAvailabilityStatus.DISCOVERED, ModelAvailabilityStatus.MANUAL -> MaterialTheme.colorScheme.primary
          ModelAvailabilityStatus.REMOVED, ModelAvailabilityStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
        }

      IdePanel(modifier = Modifier.fillMaxWidth().testTag("model_card_${model.modelId}")) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              "$provName • ${model.modelId}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          StatusPill(model.availabilityStatus.name, statusColor)
        }
        Spacer(Modifier.height(6.dp))
        Text(
          "Context: ${model.contextWindow?.toString() ?: "Unknown"} • Max Out: ${model.maxOutputTokens?.toString() ?: "Unknown"} • Vision: ${model.visionCapability.name} • Tools: ${model.toolCallingCapability.name} • Stream: ${model.streamingCapability.name}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (isDefaultChat) StatusPill("DEFAULT CHAT", ideColors.success)
          if (isAgent) StatusPill("CODING AGENT", MaterialTheme.colorScheme.secondary)
          if (isConv) StatusPill("THIS CHAT", MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(onClick = { onSetDefaultChatModel(model.providerId, model.compositeId) }) {
            Icon(Icons.Default.Chat, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Default Chat")
          }
          OutlinedButton(onClick = { onSetConversationModel(model.compositeId) }) {
            Text("Use In Chat")
          }
          OutlinedButton(onClick = { onSetAgentModel(model.compositeId) }) {
            Icon(Icons.Default.Build, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Agent Model")
          }
          OutlinedButton(onClick = { onVerifyModel(model.compositeId) }) {
            Icon(Icons.Default.Verified, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Verify")
          }
          IconButton(onClick = { onDeleteModel(model.compositeId) }) {
            Icon(Icons.Default.Delete, contentDescription = "Remove model", tint = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (showAddManualDialog && providers.isNotEmpty()) {
    ManualModelDialog(
      providers = providers,
      initialProviderId = selectedProviderId ?: providers.first().id,
      onSave = { pId, mId, name, ctx, out, vis, tools, stream ->
        onAddManualModel(pId, mId, name, ctx, out, vis, tools, stream)
        showAddManualDialog = false
      },
      onDismiss = { showAddManualDialog = false },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualModelDialog(
  providers: List<AIProviderConfig>,
  initialProviderId: String,
  onSave: (String, String, String, Int?, Int?, CapabilitySupport, CapabilitySupport, CapabilitySupport) -> Unit,
  onDismiss: () -> Unit,
) {
  var providerId by remember { mutableStateOf(initialProviderId) }
  var modelId by remember { mutableStateOf("") }
  var displayName by remember { mutableStateOf("") }
  var ctxText by remember { mutableStateOf("") }
  var outText by remember { mutableStateOf("") }
  var vision by remember { mutableStateOf(CapabilitySupport.UNKNOWN) }
  var tools by remember { mutableStateOf(CapabilitySupport.UNKNOWN) }
  var streaming by remember { mutableStateOf(CapabilitySupport.SUPPORTED) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Custom Model") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          providers.forEach { p ->
            FilterChip(
              selected = providerId == p.id,
              onClick = { providerId = p.id },
              label = { Text(p.displayName) },
            )
          }
        }
        OutlinedTextField(
          value = modelId,
          onValueChange = { modelId = it },
          label = { Text("Model ID (e.g. gpt-4o, gemini-2.5-pro)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("manual_model_id_input"),
        )
        OutlinedTextField(
          value = displayName,
          onValueChange = { displayName = it },
          label = { Text("Display Name (optional)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = ctxText,
            onValueChange = { ctxText = it.filter { c -> c.isDigit() } },
            label = { Text("Context Window") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = outText,
            onValueChange = { outText = it.filter { c -> c.isDigit() } },
            label = { Text("Max Output") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        Text("Tool Calling Support", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CapabilitySupport.entries.forEach { c ->
            FilterChip(selected = tools == c, onClick = { tools = c }, label = { Text(c.name) })
          }
        }
        Text("Vision Support", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CapabilitySupport.entries.forEach { c ->
            FilterChip(selected = vision == c, onClick = { vision = c }, label = { Text(c.name) })
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            providerId,
            modelId,
            displayName.ifBlank { modelId },
            ctxText.toIntOrNull(),
            outText.toIntOrNull(),
            vision,
            tools,
            streaming,
          )
        },
        enabled = modelId.isNotBlank(),
      ) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
