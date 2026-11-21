package com.example.compiler

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
}

enum class BuildStatus(val label: String) {
  IDLE("Idle"),
  RUNNING("Running"),
  SUCCEEDED("Succeeded"),
  FAILED("Failed"),
  CANCELLED("Cancelled"),
  UNAVAILABLE("Toolchain Unavailable"),
}

data class BuildArtifact(
  val name: String,
  val path: String,
  val sizeBytes: Long,
  val kind: String,
  val isSigned: Boolean = false,
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
)
