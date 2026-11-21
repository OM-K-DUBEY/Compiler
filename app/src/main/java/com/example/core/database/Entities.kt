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
