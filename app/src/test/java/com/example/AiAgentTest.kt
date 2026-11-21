package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CodingAgentEngine
import com.example.ai.ProjectContextManager
import com.example.core.common.AppDispatchers
import com.example.core.database.DevForgeDatabase
import com.example.core.storage.ProjectTemplateType
import com.example.core.storage.ProjectWorkspaceManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiAgentTest {

  private lateinit var context: Context
  private lateinit var db: DevForgeDatabase
  private lateinit var workspaceManager: ProjectWorkspaceManager
  private lateinit var agentEngine: CodingAgentEngine
  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    workspaceManager = ProjectWorkspaceManager(context, db.projectDao(), dispatchers)
    agentEngine = CodingAgentEngine(workspaceManager, db.checkpointDao(), dispatchers)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun agentParsesPlanComputesDiffAppliesWithCheckpointAndRollsBack() = runBlocking {
    val project = workspaceManager.createProject("AgentDemo", "com.test.agent", ProjectTemplateType.KOTLIN_CLI)
    val root = File(project.rootPath)
    val originalMain = workspaceManager.readFileText(root, "src/main/kotlin/Main.kt")

    val aiResponse =
      """
      Here is the proposed update:
      ```json_agent_plan
      {
        "summary": "Add helper and update Main.kt",
        "steps": ["Create Helper.kt", "Update Main.kt"],
        "patches": [
          {
            "path": "src/main/kotlin/Helper.kt",
            "operation": "CREATE",
            "rationale": "New helper utility",
            "content": "package com.test.agent\nfun helper() = 42\n"
          },
          {
            "path": "src/main/kotlin/Main.kt",
            "operation": "MODIFY",
            "rationale": "Call helper",
            "content": "package com.test.agent\nfun main() { println(helper()) }\n"
          }
        ]
      }
      ```
      """.trimIndent()

    val plan = agentEngine.extractAgentPlan(aiResponse)
    assertNotNull(plan)
    assertEquals(2, plan!!.patches.size)

    val previews = agentEngine.buildPatchPreviews(project, plan)
    assertEquals(2, previews.size)
    assertTrue(previews[1].diffLines.isNotEmpty())

    val checkpoint = agentEngine.applyPlanWithCheckpoint(project, plan)
    assertTrue(File(root, "src/main/kotlin/Helper.kt").exists())
    assertTrue(workspaceManager.readFileText(root, "src/main/kotlin/Main.kt").contains("helper()"))

    val rolledBack = agentEngine.rollbackCheckpoint(project, checkpoint.id)
    assertTrue(rolledBack)
    assertFalse(File(root, "src/main/kotlin/Helper.kt").exists())
    assertEquals(originalMain, workspaceManager.readFileText(root, "src/main/kotlin/Main.kt"))
  }

  @Test
  fun contextManagerBuildsBudgetedSnapshot() = runBlocking {
    val project = workspaceManager.createProject("CtxProj", "com.test.ctx", ProjectTemplateType.ANDROID_KOTLIN)
    val ctxMgr = ProjectContextManager(workspaceManager)
    val snapshot = ctxMgr.buildContext(project, "settings.gradle.kts", null, maxChars = 4000)
    assertTrue(snapshot.fileTreeLines.isNotEmpty())
    assertTrue(snapshot.includedFiles.containsKey("settings.gradle.kts"))
    assertTrue(snapshot.estimatedTokens > 0)
  }
}
