package com.example.sdk

enum class SdkCategory(val label: String) {
  JDK("Java / JDK"),
  ANDROID("Android SDK & Build"),
  KOTLIN("Kotlin Compiler"),
  GRADLE("Gradle"),
  FLUTTER("Flutter & Dart"),
  NODE("Node.js"),
}

data class SdkPackageSpec(
  val id: String,
  val displayName: String,
  val category: SdkCategory,
  val version: String,
  val downloadUrl: String,
  val archiveType: String,
  val approximateSizeBytes: Long,
  val executableProbeName: String,
  val androidExecutionNote: String,
)

data class SdkPackageUiState(
  val spec: SdkPackageSpec,
  val isInstalledLocally: Boolean = false,
  val installedVersion: String? = null,
  val installPath: String? = null,
  val installedSizeBytes: Long = 0L,
  val recordedSha256: String? = null,
  val isSystemDetected: Boolean = false,
  val systemBinaryPath: String? = null,
  val isExecutableOnDevice: Boolean = false,
  val isDownloading: Boolean = false,
  val downloadProgress: Float = 0f,
  val downloadedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val statusMessage: String? = null,
)

object SdkCatalog {
  val packages: List<SdkPackageSpec> =
    listOf(
      SdkPackageSpec(
        id = "openjdk-17-linux-aarch64",
        displayName = "Eclipse Temurin OpenJDK 17 (ARM64)",
        category = SdkCategory.JDK,
        version = "17.0.13+11",
        downloadUrl =
          "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.13_11.tar.gz",
        archiveType = "tar.gz",
        approximateSizeBytes = 195L * 1024 * 1024,
        executableProbeName = "javac",
        androidExecutionNote = "Linux glibc ARM64 archive; requires Termux/proot or compatible dynamic linker on Android.",
      ),
      SdkPackageSpec(
        id = "kotlin-compiler-2.0.21",
        displayName = "Kotlin Compiler (kotlinc)",
        category = SdkCategory.KOTLIN,
        version = "2.0.21",
        downloadUrl =
          "https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip",
        archiveType = "zip",
        approximateSizeBytes = 78L * 1024 * 1024,
        executableProbeName = "kotlinc",
        androidExecutionNote = "Pure JVM JAR distribution; requires an executable JDK/JVM runtime on PATH.",
      ),
      SdkPackageSpec(
        id = "gradle-8.10.2",
        displayName = "Gradle Build Tool (bin)",
        category = SdkCategory.GRADLE,
        version = "8.10.2",
        downloadUrl = "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip",
        archiveType = "zip",
        approximateSizeBytes = 132L * 1024 * 1024,
        executableProbeName = "gradle",
        androidExecutionNote = "Requires JDK 17+ executable runtime and writable Gradle daemon environment.",
      ),
      SdkPackageSpec(
        id = "android-cmdline-tools",
        displayName = "Android Command-line Tools",
        category = SdkCategory.ANDROID,
        version = "11076708",
        downloadUrl = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip",
        archiveType = "zip",
        approximateSizeBytes = 152L * 1024 * 1024,
        executableProbeName = "sdkmanager",
        androidExecutionNote = "Desktop x86_64/JVM tools; Android build-tools (aapt2/d8) require compatible ARM64 host binaries.",
      ),
      SdkPackageSpec(
        id = "flutter-sdk-3.24",
        displayName = "Flutter SDK (Linux ARM64/x64)",
        category = SdkCategory.FLUTTER,
        version = "3.24.5-stable",
        downloadUrl = "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.24.5-stable.tar.xz",
        archiveType = "tar.xz",
        approximateSizeBytes = 640L * 1024 * 1024,
        executableProbeName = "flutter",
        androidExecutionNote = "Desktop Linux SDK; cannot execute ELF binaries inside unrooted Android SELinux app sandbox.",
      ),
      SdkPackageSpec(
        id = "dart-sdk-3.5",
        displayName = "Dart SDK (ARM64 Linux)",
        category = SdkCategory.FLUTTER,
        version = "3.5.4",
        downloadUrl = "https://storage.googleapis.com/dart-archive/channels/stable/release/3.5.4/sdk/dartsdk-linux-arm64-release.zip",
        archiveType = "zip",
        approximateSizeBytes = 115L * 1024 * 1024,
        executableProbeName = "dart",
        androidExecutionNote = "ELF binary requires Linux execution environment (Termux/proot) on Android 10+.",
      ),
      SdkPackageSpec(
        id = "nodejs-20-arm64",
        displayName = "Node.js LTS (Linux ARM64)",
        category = SdkCategory.NODE,
        version = "20.18.0",
        downloadUrl = "https://nodejs.org/dist/v20.18.0/node-v20.18.0-linux-arm64.tar.xz",
        archiveType = "tar.xz",
        approximateSizeBytes = 25L * 1024 * 1024,
        executableProbeName = "node",
        androidExecutionNote = "Standard glibc ARM64 binary; browser JS executes natively via DevForge WebView.",
      ),
    )
}
