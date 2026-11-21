package com.example.core.storage

import com.example.core.common.AppDispatchers
import com.example.core.security.PathSandbox
import com.example.core.security.ZipPolicyLimits
import com.example.core.security.ZipSecurityValidator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.withContext

data class ZipImportResult(
  val workspaceDir: File,
  val extractedFiles: Int,
  val extractedBytes: Long,
)

data class BackupRecord(
  val file: File,
  val projectId: String,
  val sha256: String,
  val sizeBytes: Long,
  val createdAtMillis: Long,
)

class ZipArchiveManager(
  private val workspaceManager: ProjectWorkspaceManager,
  private val dispatchers: AppDispatchers,
) {

  suspend fun importZip(
    zipInputStream: InputStream,
    limits: ZipPolicyLimits = ZipPolicyLimits(),
  ): ZipImportResult =
    withContext(dispatchers.io) {
      val workspaceId = UUID.randomUUID().toString()
      val destDir = File(workspaceManager.workspacesRoot, workspaceId).apply { mkdirs() }
      var entryCount = 0
      var totalBytes = 0L
      var filesCreated = 0

      try {
        ZipInputStream(BufferedInputStream(zipInputStream)).use { zis ->
          val buffer = ByteArray(8192)
          var entry: ZipEntry? = zis.nextEntry
          while (entry != null) {
            entryCount += 1
            val safeName = ZipSecurityValidator.validateEntryName(entry.name)
            if (safeName.isNotEmpty()) {
              val target = ZipSecurityValidator.validateDestination(destDir, safeName)
              if (entry.isDirectory) {
                target.mkdirs()
              } else {
                target.parentFile?.mkdirs()
                var entryBytes = 0L
                BufferedOutputStream(target.outputStream()).use { fos ->
                  while (true) {
                    val read = zis.read(buffer)
                    if (read == -1) break
                    entryBytes += read
                    totalBytes += read
                    ZipSecurityValidator.checkLimits(
                      entryCount = entryCount,
                      cumulativeBytes = totalBytes,
                      entryBytes = entryBytes,
                      compressedSize = entry.compressedSize,
                      limits = limits,
                    )
                    fos.write(buffer, 0, read)
                  }
                }
                filesCreated += 1
              }
            }
            zis.closeEntry()
            entry = zis.nextEntry
          }
        }
        flattenSingleTopFolderIfPresent(destDir)
        ZipImportResult(destDir, filesCreated, totalBytes)
      } catch (e: Exception) {
        destDir.deleteRecursively()
        throw e
      }
    }

  suspend fun exportProjectToStream(projectRoot: File, outputStream: OutputStream): Int =
    withContext(dispatchers.io) {
      var writtenFiles = 0
      ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
        val files =
          projectRoot.walkTopDown()
            .filter { it.isFile && !it.path.contains("${File.separator}build${File.separator}") }
            .toList()
        val buffer = ByteArray(8192)
        for (file in files) {
          val relPath = PathSandbox.relativePath(projectRoot, file)
          if (relPath.isEmpty()) continue
          val entry = ZipEntry(relPath).apply { time = file.lastModified() }
          zos.putNextEntry(entry)
          file.inputStream().use { fis ->
            while (true) {
              val read = fis.read(buffer)
              if (read == -1) break
              zos.write(buffer, 0, read)
            }
          }
          zos.closeEntry()
          writtenFiles += 1
        }
      }
      writtenFiles
    }

  suspend fun createBackup(projectId: String, projectRoot: File): BackupRecord =
    withContext(dispatchers.io) {
      val ts = System.currentTimeMillis()
      val outFile = File(workspaceManager.backupsRoot, "${projectId}_$ts.zip")
      outFile.outputStream().use { exportProjectToStream(projectRoot, it) }
      val sha = sha256File(outFile)
      File(workspaceManager.backupsRoot, "${projectId}_$ts.sha256").writeText(sha)
      BackupRecord(
        file = outFile,
        projectId = projectId,
        sha256 = sha,
        sizeBytes = outFile.length(),
        createdAtMillis = ts,
      )
    }

  suspend fun listBackups(projectId: String): List<BackupRecord> =
    withContext(dispatchers.io) {
      workspaceManager.backupsRoot
        .listFiles { f -> f.isFile && f.name.startsWith("${projectId}_") && f.name.endsWith(".zip") }
        ?.mapNotNull { zipFile ->
          val base = zipFile.name.removeSuffix(".zip")
          val ts = base.substringAfterLast('_').toLongOrNull() ?: zipFile.lastModified()
          val shaFile = File(workspaceManager.backupsRoot, "$base.sha256")
          val sha = if (shaFile.exists()) shaFile.readText().trim() else sha256File(zipFile)
          BackupRecord(zipFile, projectId, sha, zipFile.length(), ts)
        }
        ?.sortedByDescending { it.createdAtMillis }
        ?: emptyList()
    }

  suspend fun restoreBackup(backupFile: File, targetProjectRoot: File): Int =
    withContext(dispatchers.io) {
      val shaFile = File(backupFile.parentFile, backupFile.name.removeSuffix(".zip") + ".sha256")
      if (shaFile.exists()) {
        val expected = shaFile.readText().trim()
        val actual = sha256File(backupFile)
        if (!expected.equals(actual, ignoreCase = true)) {
          throw SecurityException("Backup integrity check failed (SHA-256 mismatch)")
        }
      }
      val staging = File(workspaceManager.workspacesRoot, "restore_${UUID.randomUUID()}")
      val imported = backupFile.inputStream().use { importZip(it) }
      try {
        targetProjectRoot.mkdirs()
        targetProjectRoot.listFiles()?.forEach { child ->
          if (child.name != ".devforge_checkpoints") child.deleteRecursively()
        }
        imported.workspaceDir.copyRecursively(targetProjectRoot, overwrite = true)
        imported.extractedFiles
      } finally {
        imported.workspaceDir.deleteRecursively()
        staging.deleteRecursively()
      }
    }

  private fun flattenSingleTopFolderIfPresent(destDir: File) {
    val entries = destDir.listFiles()?.toList().orEmpty()
    if (entries.size == 1 && entries.first().isDirectory) {
      val nested = entries.first()
      nested.listFiles()?.forEach { child ->
        val target = File(destDir, child.name)
        child.renameTo(target)
      }
      nested.delete()
    }
  }

  private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8192)
    file.inputStream().use { fis ->
      while (true) {
        val read = fis.read(buf)
        if (read == -1) break
        digest.update(buf, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
