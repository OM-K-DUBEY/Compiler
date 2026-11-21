package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    ProjectEntity::class,
    BuildHistoryEntity::class,
    AiMessageEntity::class,
    CheckpointEntity::class,
    EditorTabEntity::class,
    SdkInstallEntity::class,
    AiProviderEntity::class,
    AiModelEntity::class,
    McpServerEntity::class,
    McpAuditLogEntity::class,
    ProtectionProfileEntity::class,
    MappingVaultEntity::class,
    BuildProtectionReportEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
abstract class DevForgeDatabase : RoomDatabase() {
  abstract fun projectDao(): ProjectDao
  abstract fun buildHistoryDao(): BuildHistoryDao
  abstract fun aiMessageDao(): AiMessageDao
  abstract fun checkpointDao(): CheckpointDao
  abstract fun editorTabDao(): EditorTabDao
  abstract fun sdkInstallDao(): SdkInstallDao
  abstract fun aiProviderDao(): AiProviderDao
  abstract fun aiModelDao(): AiModelDao
  abstract fun mcpServerDao(): McpServerDao
  abstract fun mcpAuditLogDao(): McpAuditLogDao
  abstract fun protectionProfileDao(): ProtectionProfileDao
  abstract fun mappingVaultDao(): MappingVaultDao
  abstract fun buildProtectionReportDao(): BuildProtectionReportDao

  companion object {
    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE ai_messages ADD COLUMN providerId TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE ai_messages ADD COLUMN conversationId TEXT NOT NULL DEFAULT 'default'")

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ai_providers` (
              `id` TEXT NOT NULL,
              `displayName` TEXT NOT NULL,
              `providerType` TEXT NOT NULL,
              `baseUrl` TEXT NOT NULL,
              `encryptedApiKey` TEXT NOT NULL,
              `apiVersion` TEXT NOT NULL,
              `authMethod` TEXT NOT NULL,
              `customAuthHeaderName` TEXT NOT NULL,
              `defaultModelId` TEXT NOT NULL,
              `requestTimeoutSeconds` INTEGER NOT NULL,
              `maxOutputTokens` INTEGER NOT NULL,
              `customHeadersJson` TEXT NOT NULL,
              `customConfigJson` TEXT NOT NULL,
              `supportsStreaming` INTEGER NOT NULL,
              `isEnabled` INTEGER NOT NULL,
              `lastConnectionStatus` TEXT NOT NULL,
              `lastConnectionMessage` TEXT NOT NULL,
              `lastTestedAtMillis` INTEGER NOT NULL,
              `createdAtMillis` INTEGER NOT NULL,
              `updatedAtMillis` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ai_models` (
              `compositeId` TEXT NOT NULL,
              `providerId` TEXT NOT NULL,
              `modelId` TEXT NOT NULL,
              `displayName` TEXT NOT NULL,
              `contextWindow` INTEGER,
              `maxOutputTokens` INTEGER,
              `inputModalities` TEXT NOT NULL,
              `outputModalities` TEXT NOT NULL,
              `supportsVision` TEXT NOT NULL,
              `supportsToolCalling` TEXT NOT NULL,
              `supportsStreaming` TEXT NOT NULL,
              `availabilityStatus` TEXT NOT NULL,
              `isManual` INTEGER NOT NULL,
              `lastVerifiedAtMillis` INTEGER NOT NULL,
              `updatedAtMillis` INTEGER NOT NULL,
              PRIMARY KEY(`compositeId`)
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcp_servers` (
              `id` TEXT NOT NULL,
              `displayName` TEXT NOT NULL,
              `endpointUrl` TEXT NOT NULL,
              `transportType` TEXT NOT NULL,
              `authType` TEXT NOT NULL,
              `encryptedToken` TEXT NOT NULL,
              `customHeadersJson` TEXT NOT NULL,
              `timeoutSeconds` INTEGER NOT NULL,
              `isEnabled` INTEGER NOT NULL,
              `allowedToolsJson` TEXT NOT NULL,
              `allowAllTools` INTEGER NOT NULL,
              `negotiatedProtocolVersion` TEXT NOT NULL,
              `serverCapabilitiesJson` TEXT NOT NULL,
              `connectionStatus` TEXT NOT NULL,
              `lastError` TEXT NOT NULL,
              `lastConnectedAtMillis` INTEGER NOT NULL,
              `updatedAtMillis` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcp_audit_logs` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `serverId` TEXT NOT NULL,
              `serverName` TEXT NOT NULL,
              `eventType` TEXT NOT NULL,
              `toolName` TEXT NOT NULL,
              `details` TEXT NOT NULL,
              `approvedByUser` INTEGER NOT NULL,
              `timestampMillis` INTEGER NOT NULL
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `protection_profiles` (
              `projectId` TEXT NOT NULL,
              `profileMode` TEXT NOT NULL,
              `customConfigJson` TEXT NOT NULL,
              `proguardKeepRules` TEXT NOT NULL,
              `updatedAtMillis` INTEGER NOT NULL,
              PRIMARY KEY(`projectId`)
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mapping_vault` (
              `id` TEXT NOT NULL,
              `projectId` TEXT NOT NULL,
              `buildId` INTEGER NOT NULL,
              `versionName` TEXT NOT NULL,
              `applicationId` TEXT NOT NULL,
              `toolName` TEXT NOT NULL,
              `toolVersion` TEXT NOT NULL,
              `buildVariant` TEXT NOT NULL,
              `artifactType` TEXT NOT NULL,
              `originalFileName` TEXT NOT NULL,
              `encryptedArchivePath` TEXT NOT NULL,
              `sha256` TEXT NOT NULL,
              `sizeBytes` INTEGER NOT NULL,
              `createdAtMillis` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `build_protection_reports` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `buildId` INTEGER NOT NULL,
              `projectId` TEXT NOT NULL,
              `profileMode` TEXT NOT NULL,
              `detectedFrameworks` TEXT NOT NULL,
              `reportJson` TEXT NOT NULL,
              `createdAtMillis` INTEGER NOT NULL
            )
            """.trimIndent()
          )
        }
      }

    fun create(context: Context): DevForgeDatabase {
      return Room.databaseBuilder(
          context.applicationContext,
          DevForgeDatabase::class.java,
          "devforge_ide.db",
        )
        .addMigrations(MIGRATION_1_2)
        .build()
    }
  }
}
