package com.example.mcp

enum class McpTransportType(val displayName: String) {
  STREAMABLE_HTTP("Streamable HTTP"),
  LEGACY_HTTP_SSE("Legacy HTTP + SSE"),
  STDIO_LOCAL("Local STDIO Process"),
}

enum class McpAuthType(val displayName: String) {
  NONE("No Auth"),
  BEARER("Bearer Token"),
  CUSTOM_HEADER("Custom Header"),
}

enum class McpConnectionStatus {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR,
  UNSUPPORTED,
}

enum class ToolRiskLevel {
  STANDARD,
  SENSITIVE,
}

data class McpServerConfig(
  val id: String,
  val displayName: String,
  val endpointUrl: String,
  val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP,
  val authType: McpAuthType = McpAuthType.NONE,
  val encryptedToken: String = "",
  val customHeaders: Map<String, String> = emptyMap(),
  val timeoutSeconds: Int = 30,
  val isEnabled: Boolean = true,
  val allowedTools: Set<String> = emptySet(),
  val allowAllTools: Boolean = false,
  val negotiatedProtocolVersion: String = "",
  val serverCapabilitiesJson: String = "",
  val connectionStatus: McpConnectionStatus = McpConnectionStatus.DISCONNECTED,
  val lastError: String = "",
  val lastConnectedAtMillis: Long = 0L,
) {
  val hasToken: Boolean get() = encryptedToken.isNotBlank()

  override fun toString(): String =
    "McpServerConfig(id=$id, name=$displayName, url=$endpointUrl, transport=$transportType, status=$connectionStatus)"
}

data class McpToolDefinition(
  val serverId: String,
  val serverName: String,
  val name: String,
  val description: String,
  val inputSchemaJson: String,
  val isAllowed: Boolean = false,
  val riskLevel: ToolRiskLevel = ToolRiskLevel.STANDARD,
  val riskReason: String? = null,
)

data class McpToolExecutionResult(
  val serverId: String,
  val toolName: String,
  val contentText: String,
  val wrappedUntrustedText: String,
  val isError: Boolean,
  val injectionWarning: String? = null,
)

data class PendingMcpToolApproval(
  val callId: String,
  val serverId: String,
  val serverName: String,
  val toolName: String,
  val argumentsJson: String,
  val riskReason: String,
)
