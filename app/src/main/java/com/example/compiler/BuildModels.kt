package com.example.compiler

import com.example.protection.ProtectionExecutionReport

enum class BuildSystemType(val displayName: String) {
  GRADLE_ANDROID("Android Gradle"),
  GRADLE_JVM("Kotlin/Java Gradle"),
  FLUTTER("Flutter / Dart"),
  REACT_NATIVE("React Native / Node"),
  CPP_CMAKE("C/C++ CMake"),
  WEB_STATIC("Web (HTML/CSS/JS)"),
  PYTHON("Python"),
  JAVA_STANDALONE("Java (javac)"),
  KOTLIN_STANDALONE("Kotlin (kotlinc)"),
}

enum class BuildVariant(val label: String) {
  DEBUG("Debug"),
  RELEASE("Release"),
  RELEASE_AAB("Release AAB"),
}

enum class BuildStatus(val label: String) {
  IDLE("Idle"),
  RUNNING("Running"),
  SUCCEEDED("Succeeded"),
  FAILED("Failed"),
  CANCELLED("Cancelled"),
  UNAVAILABLE("Toolchain Unavailable"),
}

enum class BuildErrorCategory(val label: String) {
  GRADLE_SYNC("Gradle Sync Error"),
  KOTLIN_COMPILER("Kotlin Compilation Error"),
  COMPOSE_ERROR("Jetpack Compose Error"),
  MISSING_SDK("Missing Android SDK"),
  MISSING_JDK("Missing JDK"),
  MISSING_DEPENDENCY("Missing Dependency"),
  AAPT2_FAILURE("AAPT2 Resource Failure"),
  DEX_FAILURE("DEX / D8 Failure"),
  R8_FAILURE("R8 / ProGuard Failure"),
  SIGNING_ERROR("Signing Error"),
  NATIVE_EXEC_FAILURE("Native Execution Failure"),
  OUT_OF_MEMORY("Out of Memory (OOM)"),
  NETWORK_DEPENDENCY_FAILURE("Network Dependency Failure"),
  GENERAL_ERROR("Build Error"),
}

data class BuildDiagnosticItem(
  val category: BuildErrorCategory,
  val summary: String,
  val rawExcerpt: String,
  val suggestedFix: String,
  val filePath: String? = null,
  val line: Int? = null,
)

data class BuildArtifact(
  val name: String,
  val path: String,
  val sizeBytes: Long,
  val kind: String,
  val isSigned: Boolean = false,
  val isVerifiedClean: Boolean = true,
)

data class BuildExecutionResult(
  val buildSystem: BuildSystemType,
  val variant: BuildVariant,
  val status: BuildStatus,
  val durationMillis: Long,
  val logs: String,
  val artifacts: List<BuildArtifact>,
  val errorCount: Int,
  val warningCount: Int,
  val limitationReason: String? = null,
  val diagnostics: List<BuildDiagnosticItem> = emptyList(),
  val protectionReport: ProtectionExecutionReport? = null,
)
