package com.example.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
  @Query("SELECT * FROM projects ORDER BY lastOpenedMillis DESC")
  fun observeAll(): Flow<List<ProjectEntity>>

  @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): ProjectEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(project: ProjectEntity)

  @Query("DELETE FROM projects WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface BuildHistoryDao {
  @Query("SELECT * FROM build_history ORDER BY timestampMillis DESC LIMIT 100")
  fun observeAll(): Flow<List<BuildHistoryEntity>>

  @Query("SELECT * FROM build_history WHERE projectId = :projectId ORDER BY timestampMillis DESC LIMIT 50")
  fun observeForProject(projectId: String): Flow<List<BuildHistoryEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entry: BuildHistoryEntity): Long

  @Query("DELETE FROM build_history WHERE projectId = :projectId")
  suspend fun clearForProject(projectId: String)
}

@Dao
interface AiMessageDao {
  @Query(
    "SELECT * FROM ai_messages WHERE ((:projectId IS NULL AND projectId IS NULL) OR projectId = :projectId) AND conversationId = :conversationId ORDER BY timestampMillis ASC, id ASC"
  )
  fun observeThread(projectId: String?, conversationId: String = "default"): Flow<List<AiMessageEntity>>

  @Query("SELECT DISTINCT conversationId FROM ai_messages WHERE (:projectId IS NULL AND projectId IS NULL) OR projectId = :projectId ORDER BY conversationId ASC")
  fun observeConversations(projectId: String?): Flow<List<String>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(message: AiMessageEntity): Long

  @Query("DELETE FROM ai_messages WHERE ((:projectId IS NULL AND projectId IS NULL) OR projectId = :projectId) AND conversationId = :conversationId")
  suspend fun clearThread(projectId: String?, conversationId: String = "default")
}

@Dao
interface CheckpointDao {
  @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY createdAtMillis DESC")
  fun observeForProject(projectId: String): Flow<List<CheckpointEntity>>

  @Query("SELECT * FROM checkpoints WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): CheckpointEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(checkpoint: CheckpointEntity)

  @Query("UPDATE checkpoints SET isRolledBack = 1 WHERE id = :id")
  suspend fun markRolledBack(id: String)
}

@Dao
interface EditorTabDao {
  @Query("SELECT * FROM editor_tabs WHERE projectId = :projectId ORDER BY sortOrder ASC")
  suspend fun getTabsForProject(projectId: String): List<EditorTabEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(tab: EditorTabEntity)

  @Query("DELETE FROM editor_tabs WHERE id = :tabId")
  suspend fun deleteById(tabId: String)

  @Query("DELETE FROM editor_tabs WHERE projectId = :projectId")
  suspend fun deleteForProject(projectId: String)
}

@Dao
interface SdkInstallDao {
  @Query("SELECT * FROM sdk_installs ORDER BY installedAtMillis DESC")
  fun observeAll(): Flow<List<SdkInstallEntity>>

  @Query("SELECT * FROM sdk_installs WHERE packageId = :id LIMIT 1")
  suspend fun getById(id: String): SdkInstallEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(pkg: SdkInstallEntity)

  @Query("DELETE FROM sdk_installs WHERE packageId = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface AiProviderDao {
  @Query("SELECT * FROM ai_providers ORDER BY createdAtMillis ASC")
  fun observeAll(): Flow<List<AiProviderEntity>>

  @Query("SELECT * FROM ai_providers ORDER BY createdAtMillis ASC")
  suspend fun getAll(): List<AiProviderEntity>

  @Query("SELECT * FROM ai_providers WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): AiProviderEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(provider: AiProviderEntity)

  @Query("DELETE FROM ai_providers WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface AiModelDao {
  @Query("SELECT * FROM ai_models ORDER BY providerId ASC, displayName ASC, modelId ASC")
  fun observeAll(): Flow<List<AiModelEntity>>

  @Query("SELECT * FROM ai_models WHERE providerId = :providerId ORDER BY displayName ASC, modelId ASC")
  fun observeForProvider(providerId: String): Flow<List<AiModelEntity>>

  @Query("SELECT * FROM ai_models WHERE providerId = :providerId")
  suspend fun getForProvider(providerId: String): List<AiModelEntity>

  @Query("SELECT * FROM ai_models WHERE compositeId = :compositeId LIMIT 1")
  suspend fun getByCompositeId(compositeId: String): AiModelEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(models: List<AiModelEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(model: AiModelEntity)

  @Query("DELETE FROM ai_models WHERE compositeId = :compositeId")
  suspend fun deleteByCompositeId(compositeId: String)

  @Query("DELETE FROM ai_models WHERE providerId = :providerId")
  suspend fun deleteForProvider(providerId: String)
}

@Dao
interface McpServerDao {
  @Query("SELECT * FROM mcp_servers ORDER BY displayName ASC")
  fun observeAll(): Flow<List<McpServerEntity>>

  @Query("SELECT * FROM mcp_servers ORDER BY displayName ASC")
  suspend fun getAll(): List<McpServerEntity>

  @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): McpServerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(server: McpServerEntity)

  @Query("DELETE FROM mcp_servers WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface McpAuditLogDao {
  @Query("SELECT * FROM mcp_audit_logs ORDER BY timestampMillis DESC, id DESC LIMIT 200")
  fun observeAll(): Flow<List<McpAuditLogEntity>>

  @Query("SELECT * FROM mcp_audit_logs WHERE serverId = :serverId ORDER BY timestampMillis DESC, id DESC LIMIT 100")
  fun observeForServer(serverId: String): Flow<List<McpAuditLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entry: McpAuditLogEntity): Long

  @Query("DELETE FROM mcp_audit_logs WHERE serverId = :serverId")
  suspend fun clearForServer(serverId: String)
}

@Dao
interface ProtectionProfileDao {
  @Query("SELECT * FROM protection_profiles WHERE projectId = :projectId LIMIT 1")
  fun observeForProject(projectId: String): Flow<ProtectionProfileEntity?>

  @Query("SELECT * FROM protection_profiles WHERE projectId = :projectId LIMIT 1")
  suspend fun getForProject(projectId: String): ProtectionProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(profile: ProtectionProfileEntity)
}

@Dao
interface MappingVaultDao {
  @Query("SELECT * FROM mapping_vault ORDER BY createdAtMillis DESC")
  fun observeAll(): Flow<List<MappingVaultEntity>>

  @Query("SELECT * FROM mapping_vault WHERE projectId = :projectId ORDER BY createdAtMillis DESC")
  fun observeForProject(projectId: String): Flow<List<MappingVaultEntity>>

  @Query("SELECT * FROM mapping_vault WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): MappingVaultEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: MappingVaultEntity)

  @Query("DELETE FROM mapping_vault WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface BuildProtectionReportDao {
  @Query("SELECT * FROM build_protection_reports ORDER BY createdAtMillis DESC LIMIT 50")
  fun observeAll(): Flow<List<BuildProtectionReportEntity>>

  @Query("SELECT * FROM build_protection_reports WHERE projectId = :projectId ORDER BY createdAtMillis DESC LIMIT 25")
  fun observeForProject(projectId: String): Flow<List<BuildProtectionReportEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(report: BuildProtectionReportEntity): Long
}
