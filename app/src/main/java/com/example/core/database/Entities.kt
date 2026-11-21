package com.example.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
  @PrimaryKey val id: String,
  val name: String,
  val packageName: String,
  val projectType: String,
  val rootPath: String,
  val sizeBytes: Long,
  val fileCount: Int,
  val createdAtMillis: Long,
  val lastModifiedMillis: Long,
  val lastOpenedMillis: Long,
)

@Entity(tableName = "build_history")
data class BuildHistoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val projectId: String,
  val projectName: String,
  val buildSystem: String,
  val buildType: String,
  val status: String,
  val durationMillis: Long,
  val outputLog: String,
  val artifactPath: String?,
  val errorCount: Int,
  val warningCount: Int,
  val timestampMillis: Long,
)

@Entity(tableName = "ai_messages")
data class AiMessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val projectId: String?,
  val role: String,
  val content: String,
  val providerLabel: String,
  val modelId: String,
  val promptTokens: Int,
  val completionTokens: Int,
  val hasAgentPlan: Boolean,
  val planJson: String?,
  val timestampMillis: Long,
  val providerId: String = "",
  val conversationId: String = "default",
)

@Entity(tableName = "checkpoints")
data class CheckpointEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val description: String,
  val snapshotDirPath: String,
  val affectedFilesJson: String,
  val createdAtMillis: Long,
  val isRolledBack: Boolean = false,
)

@Entity(tableName = "editor_tabs")
data class EditorTabEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val relativePath: String,
  val recoveryContent: String?,
  val isDirty: Boolean,
  val cursorOffset: Int,
  val sortOrder: Int,
  val updatedAtMillis: Long,
)

@Entity(tableName = "sdk_installs")
data class SdkInstallEntity(
  @PrimaryKey val packageId: String,
  val displayName: String,
  val category: String,
  val version: String,
  val installPath: String,
  val sizeBytes: Long,
  val sha256: String,
  val isExecutableOnDevice: Boolean,
  val installedAtMillis: Long,
)

@Entity(tableName = "ai_providers")
data class AiProviderEntity(
  @PrimaryKey val id: String,
  val displayName: String,
  val providerType: String,
  val baseUrl: String,
  val encryptedApiKey: String,
  val apiVersion: String,
  val authMethod: String,
  val customAuthHeaderName: String,
  val defaultModelId: String,
  val requestTimeoutSeconds: Int,
  val maxOutputTokens: Int,
  val customHeadersJson: String,
  val customConfigJson: String,
  val supportsStreaming: Boolean,
  val isEnabled: Boolean,
  val lastConnectionStatus: String,
  val lastConnectionMessage: String,
  val lastTestedAtMillis: Long,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
)

@Entity(tableName = "ai_models")
data class AiModelEntity(
  @PrimaryKey val compositeId: String,
  val providerId: String,
  val modelId: String,
  val displayName: String,
  val contextWindow: Int?,
  val maxOutputTokens: Int?,
  val inputModalities: String,
  val outputModalities: String,
  val supportsVision: String,
  val supportsToolCalling: String,
  val supportsStreaming: String,
  val availabilityStatus: String,
  val isManual: Boolean,
  val lastVerifiedAtMillis: Long,
  val updatedAtMillis: Long,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
  @PrimaryKey val id: String,
  val displayName: String,
  val endpointUrl: String,
  val transportType: String,
  val authType: String,
  val encryptedToken: String,
  val customHeadersJson: String,
  val timeoutSeconds: Int,
  val isEnabled: Boolean,
  val allowedToolsJson: String,
  val allowAllTools: Boolean,
  val negotiatedProtocolVersion: String,
  val serverCapabilitiesJson: String,
  val connectionStatus: String,
  val lastError: String,
  val lastConnectedAtMillis: Long,
  val updatedAtMillis: Long,
)

@Entity(tableName = "mcp_audit_logs")
data class McpAuditLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverId: String,
  val serverName: String,
  val eventType: String,
  val toolName: String,
  val details: String,
  val approvedByUser: Boolean,
  val timestampMillis: Long,
)

@Entity(tableName = "protection_profiles")
data class ProtectionProfileEntity(
  @PrimaryKey val projectId: String,
  val profileMode: String,
  val customConfigJson: String,
  val proguardKeepRules: String,
  val updatedAtMillis: Long,
)

@Entity(tableName = "mapping_vault")
data class MappingVaultEntity(
  @PrimaryKey val id: String,
  val projectId: String,
  val buildId: Long,
  val versionName: String,
  val applicationId: String,
  val toolName: String,
  val toolVersion: String,
  val buildVariant: String,
  val artifactType: String,
  val originalFileName: String,
  val encryptedArchivePath: String,
  val sha256: String,
  val sizeBytes: Long,
  val createdAtMillis: Long,
)

@Entity(tableName = "build_protection_reports")
data class BuildProtectionReportEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val buildId: Long,
  val projectId: String,
  val profileMode: String,
  val detectedFrameworks: String,
  val reportJson: String,
  val createdAtMillis: Long,
)
