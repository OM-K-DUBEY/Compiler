package com.example.compiler

import com.example.core.common.AppDispatchers
import com.example.core.common.DeviceInfoProvider
import com.example.core.database.BuildHistoryDao
import com.example.core.database.BuildHistoryEntity
import com.example.core.database.ProjectEntity
import com.example.core.security.KeystoreSigningManager
import com.example.core.storage.ZipArchiveManager
import com.example.editor.DiagnosticSeverity
import com.example.editor.DiagnosticsEngine
import com.example.protection.ProtectionArtifactVerifier
import com.example.protection.ProtectionExecutionReport
import com.example.protection.ProtectionPipeline
import com.example.sdk.SdkManagerRepository
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class BuildManager(
  private val buildHistoryDao: BuildHistoryDao,
  private val sdkManagerRepository: SdkManagerRepository,
  private val zipArchiveManager: ZipArchiveManager,
  private val signingManager: KeystoreSigningManager,
  private val deviceInfoProvider: DeviceInfoProvider,
  private val protectionPipeline: ProtectionPipeline,
  private val dispatchers: AppDispatchers,
) {
  private val activeProcess = AtomicReference<Process?>(null)
  private val _isBuilding = MutableStateFlow(false)
  val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

  private val _liveLogs = MutableStateFlow("")
  val liveLogs: StateFlow<String> = _liveLogs.asStateFlow()

  @Volatile private var cancelRequested = false

  fun observeHistory(projectId: String?): Flow<List<BuildHistoryEntity>> =
    if (projectId == null) buildHistoryDao.observeAll()
    else buildHistoryDao.observeForProject(projectId)

  fun cancelCurrentBuild() {
    cancelRequested = true
    activeProcess.getAndSet(null)?.destroyForcibly()
  }

  suspend fun runBuild(
    project: ProjectEntity,
    buildSystem: BuildSystemType,
    variant: BuildVariant,
    customToolchainPath: String,
    signReleaseArtifact: Boolean = false,
  ): BuildExecutionResult =
    withContext(dispatchers.io) {
      val startMillis = System.currentTimeMillis()
      cancelRequested = false
      _isBuilding.value = true
      val logBuffer = StringBuilder()

      fun appendLog(line: String) {
        logBuffer.append(line).append('\n')
        _liveLogs.value = logBuffer.toString()
      }

      try {
        val projectRoot = File(project.rootPath)
        appendLog("[DevForge Build] Project: ${project.name}")
        appendLog("[DevForge Build] Adapter: ${buildSystem.displayName} (${variant.label})")
        appendLog("[DevForge Build] Root: ${projectRoot.absolutePath}")

        // Resource & Memory pre-check
        val hw = deviceInfoProvider.snapshot(projectRoot)
        appendLog(
          "[Resources] Free RAM: ${hw.availableRamBytes / (1024 * 1024)} MB | Free Storage: ${hw.availableStorageBytes / (1024 * 1024)} MB",
        )
        if (hw.totalStorageBytes > 0L && hw.availableStorageBytes < 25L * 1024 * 1024) {
          val msg = "Insufficient free storage (<25 MB) to stage and verify build outputs."
          appendLog("[Resources] ERROR: $msg")
          return@withContext finalizeResult(
            project, buildSystem, variant, BuildStatus.FAILED, startMillis,
            logBuffer.toString(), emptyList(), 1, 0, msg, null,
          )
        }
        if (hw.totalRamBytes > 0L && hw.availableRamBytes < 128L * 1024 * 1024) {
          appendLog("[Resources] WARNING: Low available RAM (<128 MB); native compiler daemons may hit OOM.")
        }

        // Phase 1: Real static analysis & config verification on all source files
        val staticDiagnostics = runStaticPreflight(projectRoot, ::appendLog)
        val staticErrors = staticDiagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val staticWarnings = staticDiagnostics.count { it.severity == DiagnosticSeverity.WARNING }

        if (cancelRequested) {
          return@withContext finalizeResult(
            project, buildSystem, variant, BuildStatus.CANCELLED, startMillis,
            logBuffer.toString(), emptyList(), staticErrors, staticWarnings, null, null,
          )
        }

        // Phase 2: Universal Multi-Tool Protection Pipeline (pre-build)
        val (protectionPlan, protectionSteps, _) =
          protectionPipeline.executePreBuildAndCollect(project, variant, customToolchainPath, ::appendLog)

        // Phase 3: Adapter execution
        val rawExecResult =
          when (buildSystem) {
            BuildSystemType.WEB_STATIC ->
              executeWebBuild(projectRoot, variant, staticErrors, staticWarnings, signReleaseArtifact, ::appendLog)
            else ->
              executeToolchainOrReportLimitation(
                projectRoot = projectRoot,
                buildSystem = buildSystem,
                variant = variant,
                customToolchainPath = customToolchainPath,
                staticErrors = staticErrors,
                staticWarnings = staticWarnings,
                signReleaseArtifact = signReleaseArtifact,
                appendLog = ::appendLog,
              )
          }

        // Phase 4: Verify output artifacts and archive Protection Report & Mapping Vault entries
        val verifiedArtifacts =
          rawExecResult.artifacts.map { art ->
            val (ok, detail) = ProtectionArtifactVerifier.verifyArchiveClean(File(art.path))
            appendLog("[ArtifactVerify] $detail")
            art.copy(isVerifiedClean = ok)
          }

        val provisionalBuildId = System.currentTimeMillis()
        val report =
          protectionPipeline.finalizeAndArchiveReport(
            project = project,
            buildId = provisionalBuildId,
            variant = variant,
            plan = protectionPlan,
            steps = protectionSteps,
            artifactFiles = verifiedArtifacts.map { File(it.path) },
            appendLog = ::appendLog,
          )

        finalizeResult(
          project = project,
          buildSystem = buildSystem,
          variant = variant,
          status = rawExecResult.status,
          startMillis = startMillis,
          logs = logBuffer.toString(),
          artifacts = verifiedArtifacts,
          errorCount = rawExecResult.errorCount,
          warningCount = rawExecResult.warningCount,
          limitation = rawExecResult.limitationReason,
          protectionReport = report,
        )
      } finally {
        activeProcess.set(null)
        _isBuilding.value = false
      }
    }

  private fun runStaticPreflight(
    projectRoot: File,
    appendLog: (String) -> Unit,
  ): List<com.example.editor.CodeDiagnostic> {
    appendLog("[Pre-flight] Scanning project files for syntax & manifest errors...")
    val allDiagnostics = mutableListOf<com.example.editor.CodeDiagnostic>()
    val files =
      projectRoot.walkTopDown()
        .filter {
          it.isFile &&
            !it.path.contains("${File.separator}build${File.separator}") &&
            !it.path.contains("${File.separator}.devforge_checkpoints${File.separator}") &&
            !it.path.contains("${File.separator}.devforge_protection${File.separator}") &&
            it.length() <= 1024 * 1024
        }
        .toList()

    var checkedCount = 0
    for (file in files) {
      val rel = file.relativeTo(projectRoot).path.replace(File.separatorChar, '/')
      val ext = file.extension.lowercase()
      if (ext in setOf("kt", "kts", "java", "dart", "ts", "tsx", "js", "jsx", "py", "html", "css", "c", "cpp", "h", "hpp", "xml", "json", "gradle")) {
        checkedCount++
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
        val diags = DiagnosticsEngine.analyze(rel, content)
        allDiagnostics.addAll(diags)
        diags.filter { it.severity != DiagnosticSeverity.INFO }.forEach { d ->
          appendLog("[${d.severity}] ${d.filePath}:${d.line}:${d.column}: ${d.message}")
        }
      }
    }
    appendLog("[Pre-flight] Checked $checkedCount files -> ${allDiagnostics.count { it.severity == DiagnosticSeverity.ERROR }} error(s), ${allDiagnostics.count { it.severity == DiagnosticSeverity.WARNING }} warning(s)")
    return allDiagnostics
  }

  private suspend fun executeWebBuild(
    projectRoot: File,
    variant: BuildVariant,
    staticErrors: Int,
    staticWarnings: Int,
    signRelease: Boolean,
    appendLog: (String) -> Unit,
  ): BuildExecutionResult {
    if (staticErrors > 0) {
      appendLog("[WebAdapter] Build aborted due to $staticErrors static syntax error(s).")
      return BuildExecutionResult(
        BuildSystemType.WEB_STATIC, variant, BuildStatus.FAILED, 0L, "", emptyList(), staticErrors, staticWarnings,
      )
    }
    val distDir = File(projectRoot, "build/web/dist").apply {
      deleteRecursively()
      mkdirs()
    }
    val protectedJsDir = File(projectRoot, ".devforge_protection/staging/js_protected")
    val protectedWebDir = File(projectRoot, ".devforge_protection/staging/assets_processed")

    val webFiles =
      projectRoot.walkTopDown()
        .filter {
          it.isFile &&
            !it.path.contains("${File.separator}build${File.separator}") &&
            !it.path.contains("${File.separator}.devforge_protection${File.separator}") &&
            !it.path.contains("${File.separator}.devforge_checkpoints${File.separator}")
        }
        .filter { it.extension.lowercase() in setOf("html", "htm", "css", "js", "json", "svg", "png", "jpg", "webp") }
        .toList()

    for (src in webFiles) {
      val rel = src.relativeTo(projectRoot).path
      val dst = File(distDir, rel)
      dst.parentFile?.mkdirs()
      val stagedJs = File(protectedJsDir, rel)
      val stagedAsset = File(protectedWebDir, rel)
      val sourceToCopy =
        when {
          stagedJs.exists() && stagedJs.isFile -> stagedJs
          stagedAsset.exists() && stagedAsset.isFile -> stagedAsset
          else -> src
        }
      sourceToCopy.copyTo(dst, overwrite = true)
    }
    appendLog("[WebAdapter] Packaged ${webFiles.size} web asset(s) to ${distDir.absolutePath}")

    val artifacts = mutableListOf<BuildArtifact>()
    val bundleZip = File(projectRoot, "build/web/web-bundle-${variant.name.lowercase()}.zip")
    bundleZip.outputStream().use { zipArchiveManager.exportProjectToStream(distDir, it) }

    if ((variant == BuildVariant.RELEASE || variant == BuildVariant.RELEASE_AAB) && signRelease) {
      val signedZip = File(projectRoot, "build/web/web-bundle-release-signed.zip")
      val keyInfo = signingManager.signJarArchive(bundleZip, signedZip)
      appendLog("[Signer] Signed release bundle with RSA-2048 (${keyInfo.sha256Fingerprint.take(23)}...)")
      artifacts += BuildArtifact(signedZip.name, signedZip.absolutePath, signedZip.length(), "Signed Web Bundle", isSigned = true)
    } else {
      artifacts += BuildArtifact(bundleZip.name, bundleZip.absolutePath, bundleZip.length(), "Web Bundle (ZIP)", isSigned = false)
    }

    appendLog("[WebAdapter] Build succeeded.")
    return BuildExecutionResult(
      BuildSystemType.WEB_STATIC, variant, BuildStatus.SUCCEEDED, 0L, "", artifacts, 0, staticWarnings,
    )
  }

  private fun executeToolchainOrReportLimitation(
    projectRoot: File,
    buildSystem: BuildSystemType,
    variant: BuildVariant,
    customToolchainPath: String,
    staticErrors: Int,
    staticWarnings: Int,
    signReleaseArtifact: Boolean,
    appendLog: (String) -> Unit,
  ): BuildExecutionResult {
    val probeBinary =
      when (buildSystem) {
        BuildSystemType.GRADLE_ANDROID, BuildSystemType.GRADLE_JVM ->
          File(projectRoot, "gradlew").takeIf { it.exists() && it.canExecute() }
            ?: sdkManagerRepository.findExecutableOnDevice("gradle", customToolchainPath)
        BuildSystemType.FLUTTER -> sdkManagerRepository.findExecutableOnDevice("flutter", customToolchainPath)
        BuildSystemType.REACT_NATIVE -> sdkManagerRepository.findExecutableOnDevice("npm", customToolchainPath)
        BuildSystemType.CPP_CMAKE -> sdkManagerRepository.findExecutableOnDevice("cmake", customToolchainPath)
        BuildSystemType.PYTHON -> sdkManagerRepository.findExecutableOnDevice("python3", customToolchainPath)
        BuildSystemType.JAVA_STANDALONE -> sdkManagerRepository.findExecutableOnDevice("javac", customToolchainPath)
        BuildSystemType.KOTLIN_STANDALONE -> sdkManagerRepository.findExecutableOnDevice("kotlinc", customToolchainPath)
        BuildSystemType.WEB_STATIC -> null
      }

    if (probeBinary == null) {
      val reason =
        "No executable '${expectedToolName(buildSystem)}' binary found on PATH or in custom toolchain settings. " +
          "On unrooted Android 10+, SELinux blocks executing downloaded native ELF binaries inside app-private storage (W^X). " +
          "Pre-flight static syntax/manifest validation completed ($staticErrors error(s), $staticWarnings warning(s)). " +
          "To run full native compilation, attach an executable toolchain path (e.g. Termux/chroot) in Settings or export the project ZIP to Android Studio / desktop."
      appendLog("[Toolchain] UNAVAILABLE: $reason")
      return BuildExecutionResult(
        buildSystem = buildSystem,
        variant = variant,
        status = if (staticErrors > 0) BuildStatus.FAILED else BuildStatus.UNAVAILABLE,
        durationMillis = 0L,
        logs = "",
        artifacts = emptyList(),
        errorCount = staticErrors,
        warningCount = staticWarnings,
        limitationReason = reason,
      )
    }

    val command = buildCommandFor(buildSystem, variant, probeBinary.absolutePath, projectRoot)
    appendLog("[Exec] ${command.joinToString(" ")}")

    val pb = ProcessBuilder(command).directory(projectRoot).redirectErrorStream(true)
    if (customToolchainPath.isNotBlank()) {
      val currentPath = pb.environment()["PATH"].orEmpty()
      pb.environment()["PATH"] = "$customToolchainPath:$currentPath"
    }

    val process =
      try {
        pb.start()
      } catch (e: Exception) {
        appendLog("[Exec] Failed to start process: ${e.message}")
        return BuildExecutionResult(
          buildSystem, variant, BuildStatus.UNAVAILABLE, 0L, "", emptyList(),
          staticErrors, staticWarnings, e.message,
        )
      }

    activeProcess.set(process)
    var compilerErrors = staticErrors
    var compilerWarnings = staticWarnings

    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
      while (true) {
        if (cancelRequested) {
          process.destroyForcibly()
          appendLog("[Exec] Build cancelled by user.")
          return BuildExecutionResult(
            buildSystem, variant, BuildStatus.CANCELLED, 0L, "", emptyList(),
            compilerErrors, compilerWarnings,
          )
        }
        val line = reader.readLine() ?: break
        appendLog(line)
        val lower = line.lowercase()
        if (lower.contains("error:") || lower.startsWith("e:")) compilerErrors++
        if (lower.contains("warning:") || lower.startsWith("w:")) compilerWarnings++
      }
    }

    val exitCode = process.waitFor()
    appendLog("[Exec] Process exited with code $exitCode")
    if (cancelRequested) {
      return BuildExecutionResult(
        buildSystem, variant, BuildStatus.CANCELLED, 0L, "", emptyList(),
        compilerErrors, compilerWarnings,
      )
    }
    if (exitCode != 0) {
      return BuildExecutionResult(
        buildSystem, variant, BuildStatus.FAILED, 0L, "", emptyList(),
        maxOf(1, compilerErrors), compilerWarnings,
      )
    }

    val artifacts = discoverArtifacts(projectRoot, variant, signReleaseArtifact, appendLog)
    return BuildExecutionResult(
      buildSystem = buildSystem,
      variant = variant,
      status = BuildStatus.SUCCEEDED,
      durationMillis = 0L,
      logs = "",
      artifacts = artifacts,
      errorCount = compilerErrors,
      warningCount = compilerWarnings,
    )
  }

  private fun expectedToolName(type: BuildSystemType): String =
    when (type) {
      BuildSystemType.GRADLE_ANDROID, BuildSystemType.GRADLE_JVM -> "gradle"
      BuildSystemType.FLUTTER -> "flutter"
      BuildSystemType.REACT_NATIVE -> "npm"
      BuildSystemType.CPP_CMAKE -> "cmake"
      BuildSystemType.PYTHON -> "python3"
      BuildSystemType.JAVA_STANDALONE -> "javac"
      BuildSystemType.KOTLIN_STANDALONE -> "kotlinc"
      BuildSystemType.WEB_STATIC -> "builtin"
    }

  private fun buildCommandFor(
    type: BuildSystemType,
    variant: BuildVariant,
    bin: String,
    root: File,
  ): List<String> =
    when (type) {
      BuildSystemType.GRADLE_ANDROID ->
        when (variant) {
          BuildVariant.DEBUG -> listOf(bin, "assembleDebug")
          BuildVariant.RELEASE -> listOf(bin, "assembleRelease")
          BuildVariant.RELEASE_AAB -> listOf(bin, "bundleRelease")
        }
      BuildSystemType.GRADLE_JVM -> listOf(bin, "build")
      BuildSystemType.FLUTTER ->
        when (variant) {
          BuildVariant.DEBUG -> listOf(bin, "build", "apk", "--debug")
          BuildVariant.RELEASE -> listOf(bin, "build", "apk", "--release", "--obfuscate", "--split-debug-info=build/flutter-symbols")
          BuildVariant.RELEASE_AAB -> listOf(bin, "build", "appbundle", "--release", "--obfuscate", "--split-debug-info=build/flutter-symbols")
        }
      BuildSystemType.REACT_NATIVE ->
        if (variant == BuildVariant.RELEASE_AAB) listOf(bin, "run", "android", "--", "--mode=release")
        else listOf(bin, "run", "android")
      BuildSystemType.CPP_CMAKE -> listOf(bin, "-S", ".", "-B", "build/cmake", "-DCMAKE_BUILD_TYPE=${if (variant == BuildVariant.DEBUG) "Debug" else "Release"}")
      BuildSystemType.PYTHON -> {
        val entry = File(root, "main.py").takeIf { it.exists() }?.name ?: "."
        listOf(bin, "-m", "py_compile", entry)
      }
      BuildSystemType.JAVA_STANDALONE -> {
        val outDir = File(root, "build/classes").apply { mkdirs() }
        val javaFiles = root.walkTopDown().filter { it.isFile && it.extension == "java" }.map { it.absolutePath }.toList()
        listOf(bin, "-d", outDir.absolutePath) + javaFiles
      }
      BuildSystemType.KOTLIN_STANDALONE -> {
        val outJar = File(root, "build/app.jar").apply { parentFile?.mkdirs() }
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.absolutePath }.toList()
        listOf(bin, "-include-runtime", "-d", outJar.absolutePath) + ktFiles
      }
      BuildSystemType.WEB_STATIC -> emptyList()
    }

  private fun discoverArtifacts(
    projectRoot: File,
    variant: BuildVariant,
    signRelease: Boolean,
    appendLog: (String) -> Unit,
  ): List<BuildArtifact> {
    val buildDir = File(projectRoot, "build")
    val appBuildDir = File(projectRoot, "app/build")
    val searchRoots = listOf(buildDir, appBuildDir).filter { it.exists() }
    val found = mutableListOf<BuildArtifact>()
    for (dir in searchRoots) {
      dir.walkTopDown().filter { it.isFile }.forEach { file ->
        when (file.extension.lowercase()) {
          "apk", "aab", "jar", "zip" -> {
            if ((variant == BuildVariant.RELEASE || variant == BuildVariant.RELEASE_AAB) && signRelease && (file.extension == "jar" || file.extension == "zip")) {
              val signed = File(file.parentFile, file.nameWithoutExtension + "-signed." + file.extension)
              val info = signingManager.signJarArchive(file, signed)
              appendLog("[Signer] Signed ${signed.name} (${info.sha256Fingerprint.take(18)}...)")
              found += BuildArtifact(signed.name, signed.absolutePath, signed.length(), file.extension.uppercase(), isSigned = true)
            } else {
              found += BuildArtifact(file.name, file.absolutePath, file.length(), file.extension.uppercase(), isSigned = false)
            }
          }
        }
      }
    }
    return found.distinctBy { it.path }
  }

  private suspend fun finalizeResult(
    project: ProjectEntity,
    buildSystem: BuildSystemType,
    variant: BuildVariant,
    status: BuildStatus,
    startMillis: Long,
    logs: String,
    artifacts: List<BuildArtifact>,
    errorCount: Int,
    warningCount: Int,
    limitation: String?,
    protectionReport: ProtectionExecutionReport?,
  ): BuildExecutionResult {
    val duration = (System.currentTimeMillis() - startMillis).coerceAtLeast(1L)
    val classified = BuildErrorDiagnosticClassifier.classify(logs)
    buildHistoryDao.insert(
      BuildHistoryEntity(
        projectId = project.id,
        projectName = project.name,
        buildSystem = buildSystem.displayName,
        buildType = variant.label,
        status = status.name,
        durationMillis = duration,
        outputLog = logs,
        artifactPath = artifacts.firstOrNull()?.path,
        errorCount = errorCount,
        warningCount = warningCount,
        timestampMillis = System.currentTimeMillis(),
      )
    )
    return BuildExecutionResult(
      buildSystem = buildSystem,
      variant = variant,
      status = status,
      durationMillis = duration,
      logs = logs,
      artifacts = artifacts,
      errorCount = errorCount,
      warningCount = warningCount,
      limitationReason = limitation,
      diagnostics = classified,
      protectionReport = protectionReport,
    )
  }
}
