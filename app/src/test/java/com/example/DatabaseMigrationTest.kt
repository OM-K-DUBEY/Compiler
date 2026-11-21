package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.DevForgeDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

  @Test
  fun migrate1To2PreservesExistingUserDataAndCreatesNewTables(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbFile = File(context.getDatabasePath("migration_test_1_2.db").absolutePath)
    dbFile.parentFile?.mkdirs()
    if (dbFile.exists()) dbFile.delete()

    // Create genuine SQLite schema version 1 matching original DevForge v1 tables exactly
    val helper =
      object : SQLiteOpenHelper(context, dbFile.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE projects (
              id TEXT NOT NULL PRIMARY KEY,
              name TEXT NOT NULL,
              packageName TEXT NOT NULL,
              projectType TEXT NOT NULL,
              rootPath TEXT NOT NULL,
              sizeBytes INTEGER NOT NULL,
              fileCount INTEGER NOT NULL,
              createdAtMillis INTEGER NOT NULL,
              lastModifiedMillis INTEGER NOT NULL,
              lastOpenedMillis INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE build_history (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              projectId TEXT NOT NULL,
              projectName TEXT NOT NULL,
              buildSystem TEXT NOT NULL,
              buildType TEXT NOT NULL,
              status TEXT NOT NULL,
              durationMillis INTEGER NOT NULL,
              outputLog TEXT NOT NULL,
              artifactPath TEXT,
              errorCount INTEGER NOT NULL,
              warningCount INTEGER NOT NULL,
              timestampMillis INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE ai_messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              projectId TEXT,
              role TEXT NOT NULL,
              content TEXT NOT NULL,
              providerLabel TEXT NOT NULL,
              modelId TEXT NOT NULL,
              promptTokens INTEGER NOT NULL,
              completionTokens INTEGER NOT NULL,
              hasAgentPlan INTEGER NOT NULL,
              planJson TEXT,
              timestampMillis INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE checkpoints (
              id TEXT NOT NULL PRIMARY KEY,
              projectId TEXT NOT NULL,
              description TEXT NOT NULL,
              snapshotDirPath TEXT NOT NULL,
              affectedFilesJson TEXT NOT NULL,
              createdAtMillis INTEGER NOT NULL,
              isRolledBack INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE editor_tabs (
              id TEXT NOT NULL PRIMARY KEY,
              projectId TEXT NOT NULL,
              relativePath TEXT NOT NULL,
              recoveryContent TEXT,
              isDirty INTEGER NOT NULL,
              cursorOffset INTEGER NOT NULL,
              sortOrder INTEGER NOT NULL,
              updatedAtMillis INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE sdk_installs (
              packageId TEXT NOT NULL PRIMARY KEY,
              displayName TEXT NOT NULL,
              category TEXT NOT NULL,
              version TEXT NOT NULL,
              installPath TEXT NOT NULL,
              sizeBytes INTEGER NOT NULL,
              sha256 TEXT NOT NULL,
              isExecutableOnDevice INTEGER NOT NULL,
              installedAtMillis INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            "INSERT INTO projects VALUES ('p1', 'LegacyApp', 'com.legacy.app', 'ANDROID_KOTLIN', '/tmp/p1', 1024, 10, 1000, 1500, 2000)"
          )
          db.execSQL(
            "INSERT INTO ai_messages (projectId, role, content, providerLabel, modelId, promptTokens, completionTokens, hasAgentPlan, planJson, timestampMillis) " +
              "VALUES ('p1', 'USER', 'Keep my chat history', 'Online Chat', 'gemini-2.5-flash', 12, 0, 0, NULL, 1500)"
          )
          db.execSQL(
            "INSERT INTO build_history (projectId, projectName, buildSystem, buildType, status, durationMillis, outputLog, artifactPath, errorCount, warningCount, timestampMillis) " +
              "VALUES ('p1', 'LegacyApp', 'Android Gradle', 'Debug', 'SUCCEEDED', 500, 'OK', NULL, 0, 0, 1600)"
          )
          db.execSQL(
            "INSERT INTO checkpoints VALUES ('c1', 'p1', 'Initial', '/tmp/snap', '[]', 1700, 0)"
          )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
      }
    helper.writableDatabase.close()

    // Open with Room v2 and MIGRATION_1_2
    val migratedDb =
      Room.databaseBuilder(context, DevForgeDatabase::class.java, dbFile.absolutePath)
        .addMigrations(DevForgeDatabase.MIGRATION_1_2)
        .allowMainThreadQueries()
        .build()

    val projects = migratedDb.projectDao().observeAll().first()
    assertEquals(1, projects.size)
    assertEquals("LegacyApp", projects.first().name)

    val messages = migratedDb.aiMessageDao().observeThread("p1", "default").first()
    assertEquals(1, messages.size)
    assertEquals("Keep my chat history", messages.first().content)
    assertEquals("", messages.first().providerId)
    assertEquals("default", messages.first().conversationId)

    val builds = migratedDb.buildHistoryDao().observeAll().first()
    assertEquals(1, builds.size)

    val checkpoint = migratedDb.checkpointDao().getById("c1")
    assertTrue(checkpoint != null)

    // Verify new v2 tables exist & are writable
    assertTrue(migratedDb.aiProviderDao().getAll().isEmpty())
    assertTrue(migratedDb.mcpServerDao().getAll().isEmpty())
    assertTrue(migratedDb.mappingVaultDao().observeAll().first().isEmpty())

    migratedDb.close()
    dbFile.delete()
  }
}
