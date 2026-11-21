package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    ProjectEntity::class,
    BuildHistoryEntity::class,
    AiMessageEntity::class,
    CheckpointEntity::class,
    EditorTabEntity::class,
    SdkInstallEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class DevForgeDatabase : RoomDatabase() {
  abstract fun projectDao(): ProjectDao
  abstract fun buildHistoryDao(): BuildHistoryDao
  abstract fun aiMessageDao(): AiMessageDao
  abstract fun checkpointDao(): CheckpointDao
  abstract fun editorTabDao(): EditorTabDao
  abstract fun sdkInstallDao(): SdkInstallDao

  companion object {
    fun create(context: Context): DevForgeDatabase {
      return Room.databaseBuilder(
          context.applicationContext,
          DevForgeDatabase::class.java,
          "devforge_ide.db",
        )
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()
    }
  }
}
