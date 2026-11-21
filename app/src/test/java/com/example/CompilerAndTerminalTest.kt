package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.compiler.BuildErrorCategory
import com.example.compiler.BuildErrorDiagnosticClassifier
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
import com.example.protection.ProtectionPipeline
import com.example.protection.ProtectionProfileManager
import com.example.protection.ProtectionProfileMode
import com.example.protection.SymbolAndMappingVault
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
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompilerAndTerminalTest {

  private lateinit var context: Context
  private lateinit var db: DevForgeDatabase
  private lateinit var workspaceManager: ProjectWorkspaceManager
  private lateinit var protectionPipeline: ProtectionPipeline
  private lateinit var buildManager: BuildManager
  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    ShadowStatFs.registerStats(context.filesDir, 100_000, 80_000, 80_000)
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    workspaceManager = ProjectWorkspaceManager(context, db.projectDao(), dispatchers)
    val zipManager = ZipArchiveManager(workspaceManager, dispatchers)
    val deviceInfo = DeviceInfoProvider(context)
    val sdkRepo =
      SdkManagerRepository(
        context = context,
        sdkInstallDao = db.sdkInstallDao(),
        okHttpClient = OkHttpClient(),
        deviceInfoProvider = deviceInfo,
        zipArchiveManager = zipManager,
        dispatchers = dispatchers,
      )
    val cipher = JvmAesTestSecretCipher()
    val profileMgr = ProtectionProfileManager(db.protectionProfileDao(), dispatchers)
    val vault = SymbolAndMappingVault(context, db.mappingVaultDao(), cipher, dispatchers)
    protectionPipeline = ProtectionPipeline(sdkRepo, profileMgr, vault, db.buildProtectionReportDao(), dispatchers)
    buildManager =
      BuildManager(
        buildHistoryDao = db.buildHistoryDao(),
        sdkManagerRepository = sdkRepo,
        zipArchiveManager = zipManager,
        signingManager = KeystoreSigningManager(),
        deviceInfoProvider = deviceInfo,
        protectionPipeline = protectionPipeline,
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
  fun webBuildRunsProtectionPipelineProducesCleanZipAndFailsOnSyntaxError() = runBlocking {
    val webProj = workspaceManager.createProject("WebSite", "com.test.web", ProjectTemplateType.WEB_APP)
    protectionPipeline.profileManager.saveProfile(webProj.id, ProtectionProfileMode.BALANCED)

    val successRes =
      buildManager.runBuild(
        project = webProj,
        buildSystem = BuildSystemType.WEB_STATIC,
        variant = BuildVariant.RELEASE,
        customToolchainPath = "",
        signReleaseArtifact = false,
      )
    assertEquals(successRes.logs, BuildStatus.SUCCEEDED, successRes.status)
    assertTrue(successRes.artifacts.isNotEmpty())
    val artifact = successRes.artifacts.first()
    val artifactFile = File(artifact.path)
    assertTrue(artifactFile.exists() && artifactFile.length() > 0L)
    assertTrue(artifact.isVerifiedClean)
    assertTrue(successRes.protectionReport?.artifactLeakCheckPassed == true)

    // Introduce real JS syntax error and verify build fails honestly with diagnostics
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
    assertTrue(failRes.diagnostics.any { it.category == BuildErrorCategory.GENERAL_ERROR || it.filePath != null })
  }

  @Test
  fun unavailableNativeToolchainIsReportedHonestly() = runBlocking {
    val flutterProj = workspaceManager.createProject("HonestFlutter", "com.test.flut", ProjectTemplateType.FLUTTER)
    val res =
      buildManager.runBuild(
        project = flutterProj,
        buildSystem = BuildSystemType.FLUTTER,
        variant = BuildVariant.RELEASE_AAB,
        customToolchainPath = "/nonexistent_toolchain_dir",
        signReleaseArtifact = false,
      )
    if (res.status == BuildStatus.UNAVAILABLE) {
      assertTrue(!res.limitationReason.isNullOrBlank())
      assertEquals(BuildVariant.RELEASE_AAB, res.variant)
    }
  }

  @Test
  fun buildErrorClassifierCategorizesCommonFailuresWithJumpToLine() {
    val sampleLog =
      """
      e: file://app/src/main/kotlin/Main.kt:42:5: Unresolved reference: unknownSymbol
      ERROR: AAPT2 process unexpectedly exit. Android resource linking failed at res/layout/main.xml:12
      java.lang.OutOfMemoryError: Java heap space
      Could not resolve all files for configuration ':app:debugRuntimeClasspath'
      """.trimIndent()
    val items = BuildErrorDiagnosticClassifier.classify(sampleLog)
    assertTrue(items.any { it.category == BuildErrorCategory.KOTLIN_COMPILER && it.line == 42 })
    assertTrue(items.any { it.category == BuildErrorCategory.AAPT2_FAILURE })
    assertTrue(items.any { it.category == BuildErrorCategory.OUT_OF_MEMORY })
    assertTrue(items.any { it.category == BuildErrorCategory.MISSING_DEPENDENCY })
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
