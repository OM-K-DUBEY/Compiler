package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.AppDispatchers
import com.example.core.common.RedactingLogger
import com.example.core.database.DevForgeDatabase
import com.example.core.security.PathSandbox
import com.example.core.security.ZipPolicyLimits
import com.example.core.security.ZipSecurityValidator
import com.example.core.storage.ProjectTemplateType
import com.example.core.storage.ProjectWorkspaceManager
import com.example.core.storage.ZipArchiveManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityAndZipTest {

  private lateinit var db: DevForgeDatabase
  private lateinit var workspaceManager: ProjectWorkspaceManager
  private lateinit var zipManager: ZipArchiveManager
  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    workspaceManager = ProjectWorkspaceManager(context, db.projectDao(), dispatchers)
    zipManager = ZipArchiveManager(workspaceManager, dispatchers)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test(expected = SecurityException::class)
  fun pathSandboxRejectsTraversal() {
    val root = File("/tmp/devforge_sandbox_test")
    PathSandbox.resolveSafe(root, "../../etc/passwd")
  }

  @Test(expected = SecurityException::class)
  fun pathSandboxRejectsAbsolutePath() {
    val root = File("/tmp/devforge_sandbox_test")
    PathSandbox.resolveSafe(root, "/etc/passwd")
  }

  @Test
  fun zipImportRejectsPathTraversalEntry() = runBlocking {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(ZipEntry("../../evil.sh"))
      zos.write("echo pwn".toByteArray())
      zos.closeEntry()
    }

    var failed = false
    try {
      zipManager.importZip(ByteArrayInputStream(baos.toByteArray()))
    } catch (e: SecurityException) {
      failed = true
      assertTrue(e.message.orEmpty().contains("traversal", ignoreCase = true))
    }
    assertTrue("Expected SecurityException on zip slip entry", failed)
  }

  @Test
  fun zipImportEnforcesEntryLimits() = runBlocking {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      for (i in 1..5) {
        zos.putNextEntry(ZipEntry("file_$i.txt"))
        zos.write("hello".toByteArray())
        zos.closeEntry()
      }
    }

    var failed = false
    try {
      zipManager.importZip(
        ByteArrayInputStream(baos.toByteArray()),
        limits = ZipPolicyLimits(maxEntries = 3),
      )
    } catch (e: SecurityException) {
      failed = true
    }
    assertTrue("Expected entry limit SecurityException", failed)
  }

  @Test
  fun projectExportAndBackupRestoreRoundtrip(): Unit = runBlocking {
    val project = workspaceManager.createProject("ZipRoundtrip", "com.test.zip", ProjectTemplateType.WEB_APP)
    val root = File(project.rootPath)
    val backup = zipManager.createBackup(project.id, root)
    assertTrue(backup.file.exists())

    // Mutate project file on disk
    workspaceManager.writeFileText(root, "index.html", "<h1>Broken</h1>")
    assertEquals("<h1>Broken</h1>", workspaceManager.readFileText(root, "index.html"))

    // Restore verified SHA-256 backup
    val restoredCount = zipManager.restoreBackup(backup.file, root)
    assertTrue(restoredCount >= 3)
    val restoredHtml = workspaceManager.readFileText(root, "index.html")
    assertTrue(restoredHtml.contains("<title>ZipRoundtrip</title>"))

    // Export ZIP and re-import safely
    val exported = ByteArrayOutputStream()
    val exportedCount = zipManager.exportProjectToStream(root, exported)
    assertTrue(exportedCount >= 3)
    val reimported = zipManager.importZip(ByteArrayInputStream(exported.toByteArray()))
    assertTrue(File(reimported.workspaceDir, "index.html").exists())
    reimported.workspaceDir.deleteRecursively()
  }

  @Test
  fun redactingLoggerMasksSecrets() {
    val raw = "Failed request with Authorization: Bearer sk-abc1234567890xyz and AIzaSyD1234567890abcdefghij"
    val clean = RedactingLogger.redact(raw)
    assertFalse(clean.contains("sk-abc1234567890xyz"))
    assertFalse(clean.contains("AIzaSyD1234567890abcdefghij"))
    assertTrue(clean.contains("[REDACTED]"))
  }

  @Test
  fun zipValidatorAcceptsNormalRelativePath() {
    assertEquals("src/main/Main.kt", ZipSecurityValidator.validateEntryName("src\\main\\Main.kt"))
  }
}
