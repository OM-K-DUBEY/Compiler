package com.example.ai

import com.example.core.common.AppDispatchers
import com.example.core.database.CheckpointDao
import com.example.core.database.CheckpointEntity
import com.example.core.database.ProjectEntity
import com.example.core.security.PathSandbox
import com.example.core.storage.ProjectWorkspaceManager
import com.example.editor.DiffEngine
import com.example.editor.DiffLine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PatchPreview(
  val patch: FilePatch,
  val currentContent: String,
  val diffLines: List<DiffLine>,
)

class CodingAgentEngine(
  private val workspaceManager: ProjectWorkspaceManager,
  private val checkpointDao: CheckpointDao,
  private val dispatchers: AppDispatchers,
) {

  fun extractAgentPlan(responseText: String): AgentPlan? {
    val fencedRegex = Regex("""```(?:json_agent_plan|json)\s*(\{[\s\S]*?\})\s*```""")
    val match = fencedRegex.find(responseText)
    val jsonCandidate =
      match?.groupValues?.getOrNull(1)
        ?: responseText.indexOf('{').takeIf { it >= 0 }?.let { start ->
          val end = responseText.lastIndexOf('}')
          if (end > start) responseText.substring(start, end + 1) else null
        }
        ?: return null

    return runCatching {
      val root = JSONObject(jsonCandidate)
      val summary = root.optString("summary", "")
      val patchesArr = root.optJSONArray("patches") ?: return null
      val stepsArr = root.optJSONArray("steps") ?: JSONArray()

      val steps = buildList {
        for (i in 0 until stepsArr.length()) {
          val s = stepsArr.optString(i).trim()
          if (s.isNotEmpty()) add(s)
        }
      }

      val patches = buildList {
        for (i in 0 until patchesArr.length()) {
          val obj = patchesArr.getJSONObject(i)
          val path = obj.optString("path").trim()
          if (path.isEmpty()) continue
          val opName = obj.optString("operation", "MODIFY").uppercase()
          val op = PatchOperation.entries.firstOrNull { it.name == opName } ?: PatchOperation.MODIFY
          add(
            FilePatch(
              relativePath = path,
              operation = op,
              rationale = obj.optString("rationale", ""),
              newContent = obj.optString("content", ""),
            )
          )
        }
      }

      if (patches.isEmpty()) null else AgentPlan(summary, steps, patches)
    }.getOrNull()
  }

  suspend fun buildPatchPreviews(project: ProjectEntity, plan: AgentPlan): List<PatchPreview> =
    withContext(dispatchers.io) {
      val root = File(project.rootPath)
      plan.patches.map { patch ->
        val existing =
          runCatching {
            val f = PathSandbox.resolveSafe(root, patch.relativePath)
            if (f.exists() && f.isFile) f.readText(Charsets.UTF_8) else ""
          }.getOrDefault("")
        val updated = if (patch.operation == PatchOperation.DELETE) "" else patch.newContent
        PatchPreview(
          patch = patch,
          currentContent = existing,
          diffLines = DiffEngine.computeDiff(existing, updated),
        )
      }
    }

  suspend fun applyPlanWithCheckpoint(
    project: ProjectEntity,
    plan: AgentPlan,
  ): CheckpointEntity =
    withContext(dispatchers.io) {
      val root = File(project.rootPath)
      val checkpointId = UUID.randomUUID().toString()
      val snapshotDir = File(root, ".devforge_checkpoints/$checkpointId").apply { mkdirs() }
      val metaArr = JSONArray()

      // Snapshot existing state before mutating
      for (patch in plan.patches) {
        val target = PathSandbox.resolveSafe(root, patch.relativePath)
        val existed = target.exists() && target.isFile
        if (existed) {
          val backupFile = PathSandbox.resolveSafe(snapshotDir, patch.relativePath)
          backupFile.parentFile?.mkdirs()
          target.copyTo(backupFile, overwrite = true)
        }
        metaArr.put(
          JSONObject()
            .put("path", patch.relativePath)
            .put("existed", existed)
        )
      }

      val checkpoint =
        CheckpointEntity(
          id = checkpointId,
          projectId = project.id,
          description = plan.summary.ifBlank { "AI Agent patch (${plan.patches.size} files)" },
          snapshotDirPath = snapshotDir.absolutePath,
          affectedFilesJson = metaArr.toString(),
          createdAtMillis = System.currentTimeMillis(),
          isRolledBack = false,
        )
      checkpointDao.upsert(checkpoint)

      // Apply validated patches
      for (patch in plan.patches) {
        when (patch.operation) {
          PatchOperation.CREATE, PatchOperation.MODIFY -> {
            workspaceManager.writeFileText(root, patch.relativePath, patch.newContent)
          }
          PatchOperation.DELETE -> {
            workspaceManager.deleteEntry(root, patch.relativePath)
          }
        }
      }
      workspaceManager.refreshMetadata(project.id)
      checkpoint
    }

  suspend fun rollbackCheckpoint(project: ProjectEntity, checkpointId: String): Boolean =
    withContext(dispatchers.io) {
      val checkpoint = checkpointDao.getById(checkpointId) ?: return@withContext false
      val root = File(project.rootPath)
      val snapshotDir = File(checkpoint.snapshotDirPath)
      val metaArr = JSONArray(checkpoint.affectedFilesJson)

      for (i in 0 until metaArr.length()) {
        val obj = metaArr.getJSONObject(i)
        val relPath = obj.getString("path")
        val existed = obj.getBoolean("existed")
        val target = PathSandbox.resolveSafe(root, relPath)
        if (existed) {
          val backup = PathSandbox.resolveSafe(snapshotDir, relPath)
          if (backup.exists()) {
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
          }
        } else {
          if (target.exists()) target.delete()
        }
      }

      checkpointDao.markRolledBack(checkpointId)
      workspaceManager.refreshMetadata(project.id)
      true
    }
}
