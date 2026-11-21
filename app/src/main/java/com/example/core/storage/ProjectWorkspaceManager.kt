package com.example.core.storage

import android.content.Context
import com.example.core.common.AppDispatchers
import com.example.core.database.ProjectDao
import com.example.core.database.ProjectEntity
import com.example.core.security.PathSandbox
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class FileTreeNode(
  val name: String,
  val relativePath: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val lastModifiedMillis: Long,
  val depth: Int,
)

class ProjectWorkspaceManager(
  private val context: Context,
  private val projectDao: ProjectDao,
  private val dispatchers: AppDispatchers,
) {
  val workspacesRoot: File
    get() = File(context.filesDir, "workspaces").apply { mkdirs() }

  val exportsRoot: File
    get() = File(context.filesDir, "exports").apply { mkdirs() }

  val backupsRoot: File
    get() = File(context.filesDir, "backups").apply { mkdirs() }

  fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()

  suspend fun getProject(projectId: String): ProjectEntity? =
    withContext(dispatchers.io) { projectDao.getById(projectId) }

  suspend fun createProject(
    name: String,
    packageName: String,
    template: ProjectTemplateType,
  ): ProjectEntity =
    withContext(dispatchers.io) {
      val cleanName = name.trim().ifBlank { "UntitledProject" }
      val cleanPkg = packageName.trim().ifBlank { "com.devforge.app" }
      val id = UUID.randomUUID().toString()
      val rootDir = File(workspacesRoot, id).apply { mkdirs() }

      val files = ProjectTemplates.generateFiles(cleanName, cleanPkg, template)
      for ((relPath, content) in files) {
        val target = PathSandbox.resolveSafe(rootDir, relPath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
      }

      val (sizeBytes, fileCount) = computeDirectoryStats(rootDir)
      val now = System.currentTimeMillis()
      val entity =
        ProjectEntity(
          id = id,
          name = cleanName,
          packageName = cleanPkg,
          projectType = template.name,
          rootPath = rootDir.absolutePath,
          sizeBytes = sizeBytes,
          fileCount = fileCount,
          createdAtMillis = now,
          lastModifiedMillis = now,
          lastOpenedMillis = now,
        )
      projectDao.upsert(entity)
      entity
    }

  suspend fun registerImportedProject(
    name: String,
    rootDir: File,
    detectedType: String,
    packageName: String = "com.imported.app",
  ): ProjectEntity =
    withContext(dispatchers.io) {
      val id = rootDir.name
      val (sizeBytes, fileCount) = computeDirectoryStats(rootDir)
      val now = System.currentTimeMillis()
      val entity =
        ProjectEntity(
          id = id,
          name = name.trim().ifBlank { "ImportedProject" },
          packageName = packageName,
          projectType = detectedType,
          rootPath = rootDir.absolutePath,
          sizeBytes = sizeBytes,
          fileCount = fileCount,
          createdAtMillis = now,
          lastModifiedMillis = now,
          lastOpenedMillis = now,
        )
      projectDao.upsert(entity)
      entity
    }

  suspend fun markOpened(projectId: String) =
    withContext(dispatchers.io) {
      val existing = projectDao.getById(projectId) ?: return@withContext
      projectDao.upsert(existing.copy(lastOpenedMillis = System.currentTimeMillis()))
    }

  suspend fun renameProject(projectId: String, newName: String): ProjectEntity? =
    withContext(dispatchers.io) {
      val existing = projectDao.getById(projectId) ?: return@withContext null
      val clean = newName.trim().ifBlank { return@withContext existing }
      val updated = existing.copy(name = clean, lastModifiedMillis = System.currentTimeMillis())
      projectDao.upsert(updated)
      updated
    }

  suspend fun duplicateProject(projectId: String): ProjectEntity? =
    withContext(dispatchers.io) {
      val existing = projectDao.getById(projectId) ?: return@withContext null
      val srcDir = File(existing.rootPath)
      if (!srcDir.exists()) return@withContext null

      val newId = UUID.randomUUID().toString()
      val dstDir = File(workspacesRoot, newId).apply { mkdirs() }
      srcDir.copyRecursively(dstDir, overwrite = true)

      val (sizeBytes, fileCount) = computeDirectoryStats(dstDir)
      val now = System.currentTimeMillis()
      val copy =
        existing.copy(
          id = newId,
          name = "${existing.name} Copy",
          rootPath = dstDir.absolutePath,
          sizeBytes = sizeBytes,
          fileCount = fileCount,
          createdAtMillis = now,
          lastModifiedMillis = now,
          lastOpenedMillis = now,
        )
      projectDao.upsert(copy)
      copy
    }

  suspend fun deleteProject(projectId: String) =
    withContext(dispatchers.io) {
      val existing = projectDao.getById(projectId)
      if (existing != null) {
        File(existing.rootPath).deleteRecursively()
        projectDao.deleteById(projectId)
      }
    }

  suspend fun refreshMetadata(projectId: String): ProjectEntity? =
    withContext(dispatchers.io) {
      val existing = projectDao.getById(projectId) ?: return@withContext null
      val root = File(existing.rootPath)
      val (sizeBytes, fileCount) = computeDirectoryStats(root)
      val updated =
        existing.copy(
          sizeBytes = sizeBytes,
          fileCount = fileCount,
          lastModifiedMillis = System.currentTimeMillis(),
        )
      projectDao.upsert(updated)
      updated
    }

  suspend fun listFileTree(projectRoot: File): List<FileTreeNode> =
    withContext(dispatchers.io) {
      if (!projectRoot.exists() || !projectRoot.isDirectory) return@withContext emptyList()
      val nodes = mutableListOf<FileTreeNode>()

      fun walk(dir: File, depth: Int) {
        val children =
          dir.listFiles()
            ?.filter { it.name != ".git" && it.name != ".devforge_checkpoints" }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return
        for (child in children) {
          val rel = PathSandbox.relativePath(projectRoot, child)
          nodes +=
            FileTreeNode(
              name = child.name,
              relativePath = rel,
              isDirectory = child.isDirectory,
              sizeBytes = if (child.isDirectory) 0L else child.length(),
              lastModifiedMillis = child.lastModified(),
              depth = depth,
            )
          if (child.isDirectory) {
            walk(child, depth + 1)
          }
        }
      }

      walk(projectRoot, 0)
      nodes
    }

  suspend fun listAllSourceFiles(projectRoot: File): List<String> =
    withContext(dispatchers.io) {
      listFileTree(projectRoot).filter { !it.isDirectory }.map { it.relativePath }
    }

  suspend fun readFileText(projectRoot: File, relativePath: String): String =
    withContext(dispatchers.io) {
      val file = PathSandbox.resolveSafe(projectRoot, relativePath)
      if (!file.exists() || !file.isFile) {
        throw IllegalArgumentException("File not found: $relativePath")
      }
      if (file.length() > 2L * 1024L * 1024L) {
        throw IllegalStateException("File exceeds 2 MB in-editor text limit")
      }
      file.readText(Charsets.UTF_8)
    }

  suspend fun writeFileText(projectRoot: File, relativePath: String, content: String) =
    withContext(dispatchers.io) {
      val file = PathSandbox.resolveSafe(projectRoot, relativePath)
      file.parentFile?.mkdirs()
      file.writeText(content, Charsets.UTF_8)
    }

  suspend fun createFile(projectRoot: File, relativePath: String, initialContent: String = ""): File =
    withContext(dispatchers.io) {
      val file = PathSandbox.resolveSafe(projectRoot, relativePath)
      if (file.exists()) throw IllegalStateException("Target already exists: $relativePath")
      file.parentFile?.mkdirs()
      file.writeText(initialContent, Charsets.UTF_8)
      file
    }

  suspend fun createDirectory(projectRoot: File, relativePath: String): File =
    withContext(dispatchers.io) {
      val dir = PathSandbox.resolveSafe(projectRoot, relativePath)
      if (!dir.exists() && !dir.mkdirs()) {
        throw IllegalStateException("Failed to create directory: $relativePath")
      }
      dir
    }

  suspend fun deleteEntry(projectRoot: File, relativePath: String): Boolean =
    withContext(dispatchers.io) {
      val target = PathSandbox.resolveSafe(projectRoot, relativePath)
      if (!target.exists()) return@withContext false
      if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

  suspend fun renameOrMoveEntry(
    projectRoot: File,
    oldRelativePath: String,
    newRelativePath: String,
  ): Boolean =
    withContext(dispatchers.io) {
      val src = PathSandbox.resolveSafe(projectRoot, oldRelativePath)
      val dst = PathSandbox.resolveSafe(projectRoot, newRelativePath)
      if (!src.exists()) return@withContext false
      if (dst.exists()) throw IllegalStateException("Destination already exists: $newRelativePath")
      dst.parentFile?.mkdirs()
      src.renameTo(dst)
    }

  fun computeDirectoryStats(dir: File): Pair<Long, Int> {
    if (!dir.exists()) return 0L to 0
    var bytes = 0L
    var count = 0
    dir.walkTopDown().forEach { f ->
      if (f.isFile) {
        bytes += f.length()
        count += 1
      }
    }
    return bytes to count
  }
}
