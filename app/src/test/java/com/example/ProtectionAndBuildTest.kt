package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.compiler.BuildErrorCategory
import com.example.compiler.BuildErrorDiagnosticClassifier
import com.example.compiler.BuildVariant
import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.DevForgeDatabase
import com.example.core.storage.ProjectTemplateType
import com.example.core.storage.ProjectWorkspaceManager
import com.example.core.storage.ZipArchiveManager
import com.example.protection.AndroidR8Adapter
import com.example.protection.AssetAndWebProtectionAdapter
import com.example.protection.CodeMinifier
import com.example.protection.CppNativeStripAdapter
import com.example.protection.DetectedFramework
import com.example.protection.FileTypeDetector
import com.example.protection.FlutterObfuscationAdapter
import com.example.protection.FrameworkDetector
import com.example.protection.JsTsProtectionAdapter
import com.example.protection.LanguageFamily
import com.example.protection.MappingArtifactType
import com.example.protection.OptInAssetCrypto
import com.example.protection.ProtectionArtifactVerifier
import com.example.protection.ProtectionCompatibilityChecker
import com.example.protection.ProtectionConfig
import com.example.protection.ProtectionExecutionContext
import com.example.protection.ProtectionPipeline
import com.example.protection.ProtectionProfileManager
import com.example.protection.ProtectionProfileMode
import com.example.protection.PythonProtectionAdapter
import com.example.protection.SymbolAndMappingVault
import com.example.protection.ToolExecutionStatus
import com.example.sdk.SdkManagerRepository
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class ProtectionAndBuildTest {

  private lateinit var context: Context
  private lateinit var db: DevForgeDatabase
  private lateinit var workspaceManager: ProjectWorkspaceManager
  private lateinit var sdkRepo: SdkManagerRepository
  private lateinit var cipher: JvmAesTestSecretCipher
  private lateinit var vault: SymbolAndMappingVault
  private lateinit var pipeline: ProtectionPipeline
  private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, DevForgeDatabase::class.java).allowMainThreadQueries().build()
    workspaceManager = ProjectWorkspaceManager(context, db.projectDao(), dispatchers)
    val zipMgr = ZipArchiveManager(workspaceManager, dispatchers)
    sdkRepo = SdkManagerRepository(context, db.sdkInstallDao(), OkHttpClient(), DeviceInfoProvider(context), zipMgr, dispatchers)
    cipher = JvmAesTestSecretCipher()
    vault = SymbolAndMappingVault(context, db.mappingVaultDao(), cipher, dispatchers)
    pipeline = ProtectionPipeline(sdkRepo, ProtectionProfileManager(db.protectionProfileDao(), dispatchers), vault, db.buildProtectionReportDao(), dispatchers)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun fileAndFrameworkDetectorHandlesAllSupportedTechStacks(): Unit = runBlocking {
    val andProj = workspaceManager.createProject("AndDet", "com.det.and", ProjectTemplateType.ANDROID_KOTLIN)
    val andFrameworks = FrameworkDetector.detectFrameworks(File(andProj.rootPath))
    assertTrue(DetectedFramework.ANDROID_GRADLE in andFrameworks)

    val fltProj = workspaceManager.createProject("FltDet", "com.det.flt", ProjectTemplateType.FLUTTER)
    assertTrue(DetectedFramework.FLUTTER in FrameworkDetector.detectFrameworks(File(fltProj.rootPath)))

    val rnProj = workspaceManager.createProject("RnDet", "com.det.rn", ProjectTemplateType.REACT_NATIVE)
    assertTrue(DetectedFramework.REACT_NATIVE in FrameworkDetector.detectFrameworks(File(rnProj.rootPath)))

    val cppProj = workspaceManager.createProject("CppDet", "com.det.cpp", ProjectTemplateType.CPP_CMAKE)
    assertTrue(DetectedFramework.NATIVE_CPP in FrameworkDetector.detectFrameworks(File(cppProj.rootPath)))

    val pyProj = workspaceManager.createProject("PyDet", "com.det.py", ProjectTemplateType.PYTHON_APP)
    assertTrue(DetectedFramework.PYTHON_APP in FrameworkDetector.detectFrameworks(File(pyProj.rootPath)))
  }

  @Test
  fun androidR8AdapterGeneratesKeepRulesAndArchivesMappingTxt(): Unit = runBlocking {
    val andProj = workspaceManager.createProject("R8Proj", "com.test.r8", ProjectTemplateType.ANDROID_KOTLIN)
    val root = File(andProj.rootPath)
    val fakeMap = File(root, "app/build/outputs/mapping/release/mapping.txt").apply {
      parentFile?.mkdirs()
      writeText("com.test.r8.MainActivity -> a.a:\n")
    }
    pipeline.profileManager.saveProfile(andProj.id, ProtectionProfileMode.BALANCED, keepRules = "-keep class com.test.r8.KeepMe { *; }")

    val (plan, steps, staging) = pipeline.executePreBuildAndCollect(andProj, BuildVariant.RELEASE, "") {}
    assertTrue(DetectedFramework.ANDROID_GRADLE in plan.detectedFrameworks)
    val rulesFile = File(staging, "devforge-r8-rules.pro")
    assertTrue(rulesFile.exists())
    val rulesText = rulesFile.readText()
    assertTrue(rulesText.contains("-keep class com.test.r8.KeepMe"))
    assertTrue(rulesText.contains("XML, JSON, images, and assets are NOT protected by R8"))

    val r8Step = steps.first { it.toolId == "android_r8" }
    assertEquals(ToolExecutionStatus.CONFIGURED_FOR_BUILD, r8Step.status)
    assertEquals(1, r8Step.generatedMappingPaths.size)

    pipeline.finalizeAndArchiveReport(andProj, 99L, BuildVariant.RELEASE, plan, steps, emptyList()) {}
    val archived = db.mappingVaultDao().observeAll().first()
    assertEquals(1, archived.size)
    assertEquals(MappingArtifactType.R8_MAPPING.name, archived.first().artifactType)
    val decrypted = vault.readDecryptedMapping(archived.first().id).getOrThrow()
    assertTrue(decrypted.contains("com.test.r8.MainActivity -> a.a:"))
    fakeMap.delete()
  }

  @Test
  fun jsTsAndWebMinificationPreservesStringLiteralsAndNeverOverwritesOriginals(): Unit = runBlocking {
    val webProj = workspaceManager.createProject("JsMin", "com.test.js", ProjectTemplateType.WEB_APP)
    val root = File(webProj.rootPath)
    val originalJs =
      """
      // Top comment should be stripped
      const url = "https://example.com//keep/me";
      const tmpl = `/* keep inside backticks */ ${'$'}{1 + 1}`;
      function greet(name) { return url + tmpl + name; }
      """.trimIndent()
    workspaceManager.writeFileText(root, "app.js", originalJs)

    val byLang = FileTypeDetector.scanProjectFiles(root)
    val staging = File(root, ".devforge_protection/staging").apply { mkdirs() }
    val ctx =
      ProtectionExecutionContext(
        projectRoot = root,
        stagingDir = staging,
        config = ProtectionConfig.forProfile(ProtectionProfileMode.BALANCED),
        profileMode = ProtectionProfileMode.BALANCED,
        detectedFrameworks = setOf(DetectedFramework.WEB_JS_TS),
        filesByLanguage = byLang,
        keepRules = "",
        customToolchainPath = "",
        isReleaseVariant = true,
      )

    val jsRes = JsTsProtectionAdapter().execute(ctx, sdkRepo)
    assertEquals(ToolExecutionStatus.APPLIED, jsRes.status)
    val stagedJs = File(staging, "js_protected/app.js").readText()
    assertFalse(stagedJs.contains("Top comment should be stripped"))
    assertTrue(stagedJs.contains("https://example.com//keep/me"))
    assertTrue(stagedJs.contains("/* keep inside backticks */"))
    assertTrue(CodeMinifier.validateBalancedDelimiters(stagedJs))
    // Original source file untouched
    assertEquals(originalJs, workspaceManager.readFileText(root, "app.js"))

    val assetRes = AssetAndWebProtectionAdapter().execute(ctx, sdkRepo)
    assertEquals(ToolExecutionStatus.APPLIED, assetRes.status)
    assertTrue(assetRes.detail.contains("NOT encryption"))
  }

  @Test
  fun optInAssetEncryptionRoundTripAndPythonCppFlutterHonestChecks() {
    val key = OptInAssetCrypto.generateKeyBytes()
    val secretData = "SECRET_ASSET_PAYLOAD_123".toByteArray()
    val encrypted = OptInAssetCrypto.encryptBytes(secretData, key)
    assertFalse(encrypted.contentEquals(secretData))
    val decrypted = OptInAssetCrypto.decryptBytes(encrypted, key)
    assertArrayEquals(secretData, decrypted)

    val tmpDir = File(context.cacheDir, "honest_probe_${System.currentTimeMillis()}").apply { mkdirs() }
    val pyProbe = PythonProtectionAdapter().probeAvailability(sdkRepo, "/nonexistent", tmpDir)
    assertFalse(pyProbe.isAvailable)
    assertTrue(pyProbe.reasonIfUnavailable!!.contains("PyArmor"))

    val cppProbe = CppNativeStripAdapter().probeAvailability(sdkRepo, "/nonexistent", tmpDir)
    if (!cppProbe.isAvailable) {
      assertTrue(cppProbe.reasonIfUnavailable!!.contains("llvm-strip"))
    }
    val fltProbe = FlutterObfuscationAdapter().probeAvailability(sdkRepo, "/nonexistent", tmpDir)
    if (!fltProbe.isAvailable) {
      assertTrue(fltProbe.reasonIfUnavailable!!.contains("Flutter"))
    }
    val issues =
      ProtectionCompatibilityChecker.check(
        ProtectionProfileMode.STRONG,
        ProtectionConfig.forProfile(ProtectionProfileMode.STRONG),
        setOf(DetectedFramework.ANDROID_GRADLE, DetectedFramework.PYTHON_APP),
        BuildVariant.DEBUG,
        listOf(pyProbe),
      )
    assertTrue(issues.any { it.title.contains("Strong Profile") })
    assertTrue(issues.any { it.title.contains("Debug Build") })
    assertTrue(issues.any { it.title.contains("R8 Bytecode Scope") })
    tmpDir.deleteRecursively()
  }

  @Test
  fun artifactVerifierCatchesLeakedMappingAndKeystoreFilesAndPassesCleanZip() {
    val dir = File(context.cacheDir, "leak_verify_${System.currentTimeMillis()}").apply { mkdirs() }
    val dirtyZip = File(dir, "dirty.zip")
    ZipOutputStream(FileOutputStream(dirtyZip)).use { zos ->
      zos.putNextEntry(ZipEntry("classes.dex"))
      zos.write(byteArrayOf(1, 2, 3))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("outputs/mapping.txt"))
      zos.write("secret mapping".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("upload.jks"))
      zos.write("keystore".toByteArray())
      zos.closeEntry()
    }
    val (dirtyOk, dirtyMsg) = ProtectionArtifactVerifier.verifyArchiveClean(dirtyZip)
    assertFalse(dirtyOk)
    assertTrue(dirtyMsg.contains("SECURITY LEAK"))
    assertTrue(dirtyMsg.contains("mapping.txt"))

    val cleanZip = File(dir, "clean.zip")
    ZipOutputStream(FileOutputStream(cleanZip)).use { zos ->
      zos.putNextEntry(ZipEntry("index.html"))
      zos.write("<html></html>".toByteArray())
      zos.closeEntry()
    }
    val (cleanOk, _) = ProtectionArtifactVerifier.verifyArchiveClean(cleanZip)
    assertTrue(cleanOk)
    dir.deleteRecursively()
  }

  @Test
  fun buildErrorClassifierCoversAllCategories() {
    val sample =
      """
      Error: JAVA_HOME is not set
      ERROR: Android SDK location not found
      Error: R8: Missing classes detected while running R8
      D8: Cannot fit requested classes in a single dex file (64K)
      Execution failed for task ':app:signReleaseBundle': keystore password invalid
      Permission denied (SELinux) exec format error
      @Composable invocations can only happen from the context of a @Composable function
      UnknownHostException: dl.google.com
      Gradle sync failed: settings.gradle.kts could not be evaluated
      """.trimIndent()
    val classified = BuildErrorDiagnosticClassifier.classify(sample)
    val categories = classified.map { it.category }.toSet()
    assertTrue(BuildErrorCategory.MISSING_JDK in categories)
    assertTrue(BuildErrorCategory.MISSING_SDK in categories)
    assertTrue(BuildErrorCategory.R8_FAILURE in categories)
    assertTrue(BuildErrorCategory.DEX_FAILURE in categories)
    assertTrue(BuildErrorCategory.SIGNING_ERROR in categories)
    assertTrue(BuildErrorCategory.NATIVE_EXEC_FAILURE in categories)
    assertTrue(BuildErrorCategory.COMPOSE_ERROR in categories)
    assertTrue(BuildErrorCategory.NETWORK_DEPENDENCY_FAILURE in categories)
    assertTrue(BuildErrorCategory.GRADLE_SYNC in categories)
  }
}
