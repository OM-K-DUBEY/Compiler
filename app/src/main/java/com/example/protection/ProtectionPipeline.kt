package com.example.protection

import com.example.compiler.BuildVariant
import com.example.core.common.AppDispatchers
import com.example.core.database.BuildProtectionReportDao
import com.example.core.database.BuildProtectionReportEntity
import com.example.core.database.ProjectEntity
import com.example.core.database.ProtectionProfileDao
import com.example.core.database.ProtectionProfileEntity
import com.example.sdk.SdkManagerRepository
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProtectionToolRegistry {
  val adapters: List<ProtectionToolAdapter> =
    listOf(
      AndroidR8Adapter(),
      FlutterObfuscationAdapter(),
      JsTsProtectionAdapter(),
      PythonProtectionAdapter(),
      CppNativeStripAdapter(),
      AssetAndWebProtectionAdapter(),
    )

  fun probeAll(
    sdkRepo: SdkManagerRepository,
    customToolchainPath: String,
    projectRoot: File,
  ): List<ToolAvailability> =
    adapters.map { it.probeAvailability(sdkRepo, customToolchainPath, projectRoot) }
}

data class SavedProtectionProfile(
  val projectId: String,
  val mode: ProtectionProfileMode,
  val config: ProtectionConfig,
  val keepRules: String,
)

class ProtectionProfileManager(
  private val profileDao: ProtectionProfileDao,
  private val dispatchers: AppDispatchers,
) {
  fun observeProfile(projectId: String): Flow<SavedProtectionProfile> =
    profileDao.observeForProject(projectId).map { entity ->
      entity?.toDomain()
        ?: SavedProtectionProfile(
          projectId = projectId,
          mode = ProtectionProfileMode.BALANCED,
          config = ProtectionConfig.forProfile(ProtectionProfileMode.BALANCED),
          keepRules = "",
        )
    }

  suspend fun getEffectiveProfile(projectId: String, variant: BuildVariant): SavedProtectionProfile =
    withContext(dispatchers.io) {
      val existing = profileDao.getForProject(projectId)?.toDomain()
      if (existing != null) return@withContext existing
      val defaultMode =
        if (variant == BuildVariant.RELEASE) ProtectionProfileMode.BALANCED
        else ProtectionProfileMode.NONE
      SavedProtectionProfile(
        projectId = projectId,
        mode = defaultMode,
        config = ProtectionConfig.forProfile(defaultMode),
        keepRules = "",
      )
    }

  suspend fun saveProfile(
    projectId: String,
    mode: ProtectionProfileMode,
    customConfig: ProtectionConfig? = null,
    keepRules: String? = null,
  ) =
    withContext(dispatchers.io) {
      val existing = profileDao.getForProject(projectId)
      val effectiveConfig =
        if (mode == ProtectionProfileMode.CUSTOM && customConfig != null) customConfig
        else ProtectionConfig.forProfile(mode)
      val mergedRules = keepRules ?: existing?.proguardKeepRules.orEmpty()
      profileDao.upsert(
        ProtectionProfileEntity(
          projectId = projectId,
          profileMode = mode.name,
          customConfigJson = configToJson(effectiveConfig),
          proguardKeepRules = mergedRules,
          updatedAtMillis = System.currentTimeMillis(),
        )
      )
    }

  private fun ProtectionProfileEntity.toDomain(): SavedProtectionProfile {
    val mode =
      ProtectionProfileMode.entries.firstOrNull { it.name == profileMode }
        ?: ProtectionProfileMode.BALANCED
    val cfg =
      if (mode == ProtectionProfileMode.CUSTOM) jsonToConfig(customConfigJson)
      else ProtectionConfig.forProfile(mode)
    return SavedProtectionProfile(projectId, mode, cfg, proguardKeepRules)
  }

  private fun configToJson(c: ProtectionConfig): String =
    JSONObject().apply {
      put("r8", c.enableAndroidR8)
      put("shrinkRes", c.enableResourceShrinking)
      put("flutter", c.enableFlutterObfuscate)
      put("jsMin", c.enableJsMinification)
      put("jsObf", c.enableJsObfuscator)
      put("nativeStrip", c.enableNativeStrip)
      put("webMin", c.enableWebMinification)
      put("assetEnc", c.enableOptInAssetEncryption)
    }.toString()

  private fun jsonToConfig(raw: String): ProtectionConfig =
    runCatching {
      val o = JSONObject(raw.ifBlank { "{}" })
      ProtectionConfig(
        enableAndroidR8 = o.optBoolean("r8", true),
        enableResourceShrinking = o.optBoolean("shrinkRes", true),
        enableFlutterObfuscate = o.optBoolean("flutter", true),
        enableJsMinification = o.optBoolean("jsMin", true),
        enableJsObfuscator = o.optBoolean("jsObf", false),
        enableNativeStrip = o.optBoolean("nativeStrip", true),
        enableWebMinification = o.optBoolean("webMin", true),
        enableOptInAssetEncryption = o.optBoolean("assetEnc", false),
      )
    }.getOrDefault(ProtectionConfig())
}

object ProtectionCompatibilityChecker {
  fun check(
    mode: ProtectionProfileMode,
    config: ProtectionConfig,
    frameworks: Set<DetectedFramework>,
    variant: BuildVariant,
    availabilities: List<ToolAvailability>,
  ): List<ProtectionCompatibilityIssue> {
    val issues = mutableListOf<ProtectionCompatibilityIssue>()
    if (mode == ProtectionProfileMode.STRONG) {
      issues +=
        ProtectionCompatibilityIssue(
          severity = "WARNING",
          title = "Strong Profile Compatibility Notice",
          description =
            "Strong protection enables aggressive shrinking and optional asset encryption. No obfuscation makes reverse engineering impossible — verify reflection/JNI keep rules and runtime asset decryption before release.",
        )
    }
    if (variant == BuildVariant.DEBUG && mode != ProtectionProfileMode.NONE) {
      issues +=
        ProtectionCompatibilityIssue(
          severity = "WARNING",
          title = "Protection Active on Debug Build",
          description = "Obfuscating Debug builds slows incremental compilation and complicates stack traces. Debug builds normally use None.",
        )
    }
    if (DetectedFramework.ANDROID_GRADLE in frameworks && config.enableAndroidR8) {
      issues +=
        ProtectionCompatibilityIssue(
          severity = "INFO",
          title = "R8 Bytecode Scope",
          description = "R8 protects Java/Kotlin classes and methods. XML layouts, JSON configs, images, and raw assets are NOT protected by R8.",
        )
    }
    availabilities.filter { !it.isAvailable }.forEach { tool ->
      val relevant =
        (tool.toolId == "flutter_obfuscate" && DetectedFramework.FLUTTER in frameworks) ||
          (tool.toolId == "python_protection" && DetectedFramework.PYTHON_APP in frameworks) ||
          (tool.toolId == "cpp_llvm_strip" && DetectedFramework.NATIVE_CPP in frameworks)
      if (relevant) {
        issues +=
          ProtectionCompatibilityIssue(
            severity = "WARNING",
            title = "${tool.displayName} Unavailable",
            description = tool.reasonIfUnavailable ?: "Tool binary not found in current environment.",
          )
      }
    }
    return issues
  }
}

object ProtectionArtifactVerifier {
  private val forbiddenEntries =
    listOf("mapping.txt", "usage.txt", "seeds.txt", ".keystore", ".jks", ".p12", ".env", ".pem")

  fun verifyArchiveClean(archiveFile: File): Pair<Boolean, String> {
    if (!archiveFile.exists() || archiveFile.length() == 0L) {
      return false to "Build output artifact missing or empty: ${archiveFile.name}"
    }
    val ext = archiveFile.extension.lowercase()
    if (ext !in setOf("apk", "aab", "zip", "jar")) {
      return true to "Non-archive artifact verified (${archiveFile.length()} bytes)."
    }

    val leaked = mutableListOf<String>()
    var entryCount = 0
    ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        entryCount++
        val lower = entry.name.lowercase()
        if (forbiddenEntries.any { lower.endsWith(it) || lower.contains("/$it") } ||
          lower.endsWith(".symbols") || lower.endsWith(".js.map") || lower.endsWith(".debug")
        ) {
          leaked += entry.name
        }
        zis.closeEntry()
      }
    }
    if (entryCount == 0) {
      return false to "Archive ${archiveFile.name} contains zero ZIP entries."
    }
    return if (leaked.isNotEmpty()) {
      false to "SECURITY LEAK: Private mapping/secret file(s) found inside ${archiveFile.name}: ${leaked.joinToString()}"
    } else {
      true to "Verified clean archive (${archiveFile.name}, $entryCount entries, 0 leaked mappings/keys)."
    }
  }
}

object ProtectionReportGenerator {
  fun toJson(report: ProtectionExecutionReport): String =
    JSONObject().apply {
      put("buildId", report.buildId)
      put("projectId", report.projectId)
      put("profileMode", report.profileMode.name)
      put("detectedFrameworks", JSONArray(report.detectedFrameworks.map { it.name }))
      put("artifactLeakCheckPassed", report.artifactLeakCheckPassed)
      put("artifactLeakDetails", report.artifactLeakDetails)
      put("summaryText", report.summaryText)
      put(
        "steps",
        JSONArray().apply {
          report.steps.forEach { s ->
            put(
              JSONObject()
                .put("toolId", s.toolId)
                .put("toolName", s.toolName)
                .put("targetSummary", s.targetSummary)
                .put("status", s.status.name)
                .put("detail", s.detail)
                .put("processedFiles", s.processedFiles)
            )
          }
        },
      )
    }.toString()
}

class ProtectionPipeline(
  private val sdkManagerRepository: SdkManagerRepository,
  val profileManager: ProtectionProfileManager,
  val mappingVault: SymbolAndMappingVault,
  private val reportDao: BuildProtectionReportDao,
  private val dispatchers: AppDispatchers,
) {
  val toolRegistry = ProtectionToolRegistry()

  fun observeReports(projectId: String): Flow<List<BuildProtectionReportEntity>> =
    reportDao.observeForProject(projectId)

  suspend fun buildPlan(
    project: ProjectEntity,
    variant: BuildVariant,
    customToolchainPath: String,
  ): ProtectionPlan =
    withContext(dispatchers.io) {
      val root = File(project.rootPath)
      val byLang = FileTypeDetector.scanProjectFiles(root)
      val frameworks = FrameworkDetector.detectFrameworks(root, byLang)
      val savedProfile = profileManager.getEffectiveProfile(project.id, variant)
      val availabilities = toolRegistry.probeAll(sdkManagerRepository, customToolchainPath, root)
      val issues =
        ProtectionCompatibilityChecker.check(
          savedProfile.mode,
          savedProfile.config,
          frameworks,
          variant,
          availabilities,
        )
      ProtectionPlan(
        projectId = project.id,
        profileMode = savedProfile.mode,
        config = savedProfile.config,
        detectedLanguages = byLang.keys,
        detectedFrameworks = frameworks,
        fileCountsByLanguage = byLang.mapValues { it.value.size },
        toolAvailabilities = availabilities,
        compatibilityIssues = issues,
      )
    }

  suspend fun executePreBuildAndCollect(
    project: ProjectEntity,
    variant: BuildVariant,
    customToolchainPath: String,
    appendLog: (String) -> Unit,
  ): Triple<ProtectionPlan, List<ProtectionStepResult>, File> =
    withContext(dispatchers.io) {
      val root = File(project.rootPath)
      val byLang = FileTypeDetector.scanProjectFiles(root)
      val frameworks = FrameworkDetector.detectFrameworks(root, byLang)
      val saved = profileManager.getEffectiveProfile(project.id, variant)
      val availabilities = toolRegistry.probeAll(sdkManagerRepository, customToolchainPath, root)
      val issues =
        ProtectionCompatibilityChecker.check(saved.mode, saved.config, frameworks, variant, availabilities)

      val plan =
        ProtectionPlan(
          projectId = project.id,
          profileMode = saved.mode,
          config = saved.config,
          detectedLanguages = byLang.keys,
          detectedFrameworks = frameworks,
          fileCountsByLanguage = byLang.mapValues { it.value.size },
          toolAvailabilities = availabilities,
          compatibilityIssues = issues,
        )

      val stagingDir = File(root, ".devforge_protection/staging").apply {
        deleteRecursively()
        mkdirs()
      }

      appendLog("[Protection] Profile: ${saved.mode.displayName} | Frameworks: ${frameworks.joinToString { it.displayName }}")
      if (saved.mode == ProtectionProfileMode.NONE) {
        val skipped =
          ProtectionStepResult("none", "Protection Engine", "All targets", ToolExecutionStatus.SKIPPED, "Profile set to None.")
        return@withContext Triple(plan, listOf(skipped), stagingDir)
      }

      val ctx =
        ProtectionExecutionContext(
          projectRoot = root,
          stagingDir = stagingDir,
          config = saved.config,
          profileMode = saved.mode,
          detectedFrameworks = frameworks,
          filesByLanguage = byLang,
          keepRules = saved.keepRules,
          customToolchainPath = customToolchainPath,
          isReleaseVariant = variant == BuildVariant.RELEASE,
        )

      val steps = mutableListOf<ProtectionStepResult>()
      for (adapter in toolRegistry.adapters) {
        val relevantLang = adapter.supportedLanguages.any { it in byLang.keys }
        val relevantFramework = adapter.supportedFrameworks.any { it in frameworks }
        if (!relevantLang && !relevantFramework) continue
        val step = adapter.execute(ctx, sdkManagerRepository)
        steps += step
        appendLog("[Protection:${step.toolName}] ${step.status.name} — ${step.detail}")
      }

      Triple(plan, steps, stagingDir)
    }

  suspend fun finalizeAndArchiveReport(
    project: ProjectEntity,
    buildId: Long,
    variant: BuildVariant,
    plan: ProtectionPlan,
    steps: List<ProtectionStepResult>,
    artifactFiles: List<File>,
    appendLog: (String) -> Unit,
  ): ProtectionExecutionReport =
    withContext(dispatchers.io) {
      // Archive any generated mapping / symbol files into encrypted Mapping Vault
      var archivedCount = 0
      for (step in steps) {
        for ((type, path) in step.generatedMappingPaths) {
          val file = File(path)
          if (file.exists() && file.isFile) {
            mappingVault.storeMappingFile(
              projectId = project.id,
              buildId = buildId,
              versionName = "1.0",
              applicationId = project.packageName,
              toolName = step.toolName,
              toolVersion = step.toolId,
              buildVariant = variant.label,
              artifactType = type,
              sourceFile = file,
            ).onSuccess { archivedCount++ }
          }
        }
      }
      if (archivedCount > 0) {
        appendLog("[MappingVault] Encrypted & archived $archivedCount mapping/symbol artifact(s).")
      }

      // Verify all produced public artifacts contain zero private mappings/keys
      var leakPassed = true
      val leakDetails = StringBuilder()
      for (art in artifactFiles) {
        val (ok, msg) = ProtectionArtifactVerifier.verifyArchiveClean(art)
        if (!ok) leakPassed = false
        if (leakDetails.isNotEmpty()) leakDetails.append(" | ")
        leakDetails.append(msg)
      }
      if (artifactFiles.isEmpty()) {
        leakDetails.append("No packaged APK/AAB/ZIP produced on this run (pre-flight/check only).")
      }

      val summary =
        "Profile ${plan.profileMode.name}: ${steps.count { it.status == ToolExecutionStatus.APPLIED }} applied, " +
          "${steps.count { it.status == ToolExecutionStatus.CONFIGURED_FOR_BUILD }} configured, " +
          "${steps.count { it.status == ToolExecutionStatus.UNAVAILABLE }} unavailable."

      val report =
        ProtectionExecutionReport(
          buildId = buildId,
          projectId = project.id,
          profileMode = plan.profileMode,
          detectedFrameworks = plan.detectedFrameworks,
          steps = steps,
          compatibilityIssues = plan.compatibilityIssues,
          artifactLeakCheckPassed = leakPassed,
          artifactLeakDetails = leakDetails.toString(),
          summaryText = summary,
          createdAtMillis = System.currentTimeMillis(),
        )

      reportDao.insert(
        BuildProtectionReportEntity(
          buildId = buildId,
          projectId = project.id,
          profileMode = plan.profileMode.name,
          detectedFrameworks = plan.detectedFrameworks.joinToString(",") { it.name },
          reportJson = ProtectionReportGenerator.toJson(report),
          createdAtMillis = report.createdAtMillis,
        )
      )
      report
    }
}
