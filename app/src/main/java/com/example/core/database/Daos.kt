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
  @Query("SELECT * FROM ai_messages WHERE (:projectId IS NULL AND projectId IS NULL) OR projectId = :projectId ORDER BY timestampMillis ASC, id ASC")
  fun observeThread(projectId: String?): Flow<List<AiMessageEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(message: AiMessageEntity): Long

  @Query("DELETE FROM ai_messages WHERE (:projectId IS NULL AND projectId IS NULL) OR projectId = :projectId")
  suspend fun clearThread(projectId: String?)
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
