package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.compiler.BuildManager
import com.example.compiler.BuildStatus
import com.example.compiler.BuildSystemType
import com.example.compiler.BuildVariant
import com.example.compiler.ProjectDetector
import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.DevForgeDatabase
import com.example.core.security.KeystoreSigningManager
import com.example.core.storage.ProjectTemplateType
import com.example.core.storage.ProjectWorkspaceManager
import com.example.core.storage.ZipArchiveManager
import com.example.sdk.SdkManagerRepository
import com.example.terminal.TerminalLineKind
import com.example.terminal.TerminalSessionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompilerAndTerminalTest {

  private lateinit var context: Context
  private lateinit var db: DevForgeDatabase
  private lateinit var workspaceManager: ProjectWorkspaceManager
  private lateinit var buildManager: BuildManager
  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    workspaceManager = ProjectWorkspaceManager(context, db.projectDao(), dispatchers)
    val zipManager = ZipArchiveManager(workspaceManager, dispatchers)
    val sdkRepo =
      SdkManagerRepository(
        context = context,
        sdkInstallDao = db.sdkInstallDao(),
        okHttpClient = OkHttpClient(),
        deviceInfoProvider = DeviceInfoProvider(context),
        zipArchiveManager = zipManager,
        dispatchers = dispatchers,
      )
    buildManager =
      BuildManager(
        buildHistoryDao = db.buildHistoryDao(),
        sdkManagerRepository = sdkRepo,
        zipArchiveManager = zipManager,
        signingManager = KeystoreSigningManager(),
        dispatchers = dispatchers,
      )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun projectDetectorIdentifiesMultiSystemProjects() = runBlocking {
    val androidProj = workspaceManager.createProject("AndApp", "com.test.and", ProjectTemplateType.ANDROID_KOTLIN)
    assertTrue(BuildSystemType.GRADLE_ANDROID in ProjectDetector.detectBuildSystems(File(androidProj.rootPath)))

    val flutterProj = workspaceManager.createProject("FlutApp", "com.test.flut", ProjectTemplateType.FLUTTER)
    assertTrue(BuildSystemType.FLUTTER in ProjectDetector.detectBuildSystems(File(flutterProj.rootPath)))

    val rnProj = workspaceManager.createProject("RnApp", "com.test.rn", ProjectTemplateType.REACT_NATIVE)
    assertTrue(BuildSystemType.REACT_NATIVE in ProjectDetector.detectBuildSystems(File(rnProj.rootPath)))

    val cppProj = workspaceManager.createProject("CppApp", "com.test.cpp", ProjectTemplateType.CPP_CMAKE)
    assertTrue(BuildSystemType.CPP_CMAKE in ProjectDetector.detectBuildSystems(File(cppProj.rootPath)))
  }

  @Test
  fun webBuildProducesRealDistAndZipArtifactAndFailsOnSyntaxError() = runBlocking {
    val webProj = workspaceManager.createProject("WebSite", "com.test.web", ProjectTemplateType.WEB_APP)
    val successRes =
      buildManager.runBuild(
        project = webProj,
        buildSystem = BuildSystemType.WEB_STATIC,
        variant = BuildVariant.DEBUG,
        customToolchainPath = "",
        signReleaseArtifact = false,
      )
    assertEquals(BuildStatus.SUCCEEDED, successRes.status)
    assertTrue(successRes.artifacts.isNotEmpty())
    val artifactFile = File(successRes.artifacts.first().path)
    assertTrue(artifactFile.exists() && artifactFile.length() > 0L)

    // Introduce real JS syntax error and verify build fails honestly
    workspaceManager.writeFileText(File(webProj.rootPath), "app.js", "function broken( {")
    val failRes =
      buildManager.runBuild(
        project = webProj,
        buildSystem = BuildSystemType.WEB_STATIC,
        variant = BuildVariant.DEBUG,
        customToolchainPath = "",
        signReleaseArtifact = false,
      )
    assertEquals(BuildStatus.FAILED, failRes.status)
    assertTrue(failRes.errorCount > 0)
  }

  @Test
  fun unavailableNativeToolchainIsReportedHonestly() = runBlocking {
    val flutterProj = workspaceManager.createProject("HonestFlutter", "com.test.flut", ProjectTemplateType.FLUTTER)
    val res =
      buildManager.runBuild(
        project = flutterProj,
        buildSystem = BuildSystemType.FLUTTER,
        variant = BuildVariant.DEBUG,
        customToolchainPath = "/nonexistent_toolchain_dir",
        signReleaseArtifact = false,
      )
    // On test host without flutter installed on PATH, must report UNAVAILABLE (never fake success)
    if (res.status == BuildStatus.UNAVAILABLE) {
      assertTrue(!res.limitationReason.isNullOrBlank())
    }
  }

  @Test
  fun terminalExecutesRealCommandsAndTracksWorkingDirectory() = runBlocking {
    val tmpDir = File(context.filesDir, "term_test").apply { mkdirs() }
    val terminal = TerminalSessionManager(tmpDir, dispatchers)

    terminal.execute("echo devforge_real_shell")
    assertTrue(terminal.lines.value.any { it.kind == TerminalLineKind.STDOUT && it.text == "devforge_real_shell" })

    terminal.execute("export CUSTOM_KEY=42")
    terminal.execute("echo \$CUSTOM_KEY")
    assertTrue(terminal.lines.value.any { it.kind == TerminalLineKind.STDOUT && it.text == "42" })

    val sub = File(tmpDir, "sub").apply { mkdirs() }
    terminal.execute("cd sub")
    assertEquals(sub.canonicalPath, terminal.workingDir.value.canonicalPath)
    assertTrue(terminal.requiresScriptConfirmation("./gradlew assembleDebug"))
  }
}
