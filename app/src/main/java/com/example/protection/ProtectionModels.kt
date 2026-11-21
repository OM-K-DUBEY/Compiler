package com.example.protection

enum class ProtectionProfileMode(val displayName: String, val summary: String) {
  NONE("None", "No code shrinking, obfuscation, or asset processing applied."),
  BASIC("Basic", "Safe identifier shrinking & whitespace/comment minification where supported."),
  BALANCED("Balanced (Recommended Release)", "R8 shrinking/obfuscation, Flutter split debug info, native symbol stripping, and web/JSON minification."),
  STRONG("Strong (Verify Compatibility)", "Aggressive R8 optimization, resource shrinking, JS obfuscation/minification, and optional asset encryption. Not impossible to reverse engineer — test thoroughly."),
  CUSTOM("Custom", "User-selected per-language protection tools and custom ProGuard/R8 keep rules."),
}

enum class LanguageFamily(val displayName: String) {
  JAVA_KOTLIN("Java / Kotlin"),
  DART_FLUTTER("Dart / Flutter"),
  JS_TS("JavaScript / TypeScript"),
  PYTHON("Python"),
  CPP_NATIVE("C / C++ Native"),
  WEB_MARKUP("HTML / CSS / JSON"),
  RESOURCES_MEDIA("XML / Images / Fonts"),
  UNKNOWN("Other"),
}

enum class DetectedFramework(val displayName: String) {
  ANDROID_GRADLE("Android Gradle"),
  JVM_GRADLE("JVM Gradle"),
  FLUTTER("Flutter"),
  REACT_NATIVE("React Native"),
  WEB_JS_TS("Web / JS / TS"),
  PYTHON_APP("Python"),
  NATIVE_CPP("C / C++ CMake"),
}

enum class ToolExecutionStatus {
  APPLIED,
  CONFIGURED_FOR_BUILD,
  SKIPPED,
  UNAVAILABLE,
  FAILED,
}

enum class MappingArtifactType(val displayName: String) {
  R8_MAPPING("R8 mapping.txt"),
  R8_USAGE("R8 usage.txt"),
  R8_SEEDS("R8 seeds.txt"),
  FLUTTER_SYMBOLS("Flutter Debug Symbols"),
  JS_SOURCEMAP("JavaScript Source Map"),
  NATIVE_SYMBOLS("Native Debug Symbols"),
  ASSET_KEY_META("Asset Encryption Key Metadata"),
}

data class ProtectionConfig(
  val enableAndroidR8: Boolean = true,
  val enableResourceShrinking: Boolean = true,
  val enableFlutterObfuscate: Boolean = true,
  val enableJsMinification: Boolean = true,
  val enableJsObfuscator: Boolean = false,
  val enableNativeStrip: Boolean = true,
  val enableWebMinification: Boolean = true,
  val enableOptInAssetEncryption: Boolean = false,
) {
  companion object {
    fun forProfile(mode: ProtectionProfileMode): ProtectionConfig =
      when (mode) {
        ProtectionProfileMode.NONE ->
          ProtectionConfig(
            enableAndroidR8 = false,
            enableResourceShrinking = false,
            enableFlutterObfuscate = false,
            enableJsMinification = false,
            enableJsObfuscator = false,
            enableNativeStrip = false,
            enableWebMinification = false,
            enableOptInAssetEncryption = false,
          )
        ProtectionProfileMode.BASIC ->
          ProtectionConfig(
            enableAndroidR8 = true,
            enableResourceShrinking = false,
            enableFlutterObfuscate = true,
            enableJsMinification = true,
            enableJsObfuscator = false,
            enableNativeStrip = false,
            enableWebMinification = true,
            enableOptInAssetEncryption = false,
          )
        ProtectionProfileMode.BALANCED ->
          ProtectionConfig(
            enableAndroidR8 = true,
            enableResourceShrinking = true,
            enableFlutterObfuscate = true,
            enableJsMinification = true,
            enableJsObfuscator = false,
            enableNativeStrip = true,
            enableWebMinification = true,
            enableOptInAssetEncryption = false,
          )
        ProtectionProfileMode.STRONG ->
          ProtectionConfig(
            enableAndroidR8 = true,
            enableResourceShrinking = true,
            enableFlutterObfuscate = true,
            enableJsMinification = true,
            enableJsObfuscator = true,
            enableNativeStrip = true,
            enableWebMinification = true,
            enableOptInAssetEncryption = true,
          )
        ProtectionProfileMode.CUSTOM -> ProtectionConfig()
      }
  }
}

data class ToolAvailability(
  val toolId: String,
  val displayName: String,
  val isAvailable: Boolean,
  val versionOrPath: String,
  val reasonIfUnavailable: String? = null,
)

data class ProtectionCompatibilityIssue(
  val severity: String, // "WARNING" | "ERROR"
  val title: String,
  val description: String,
)

data class ProtectionStepResult(
  val toolId: String,
  val toolName: String,
  val targetSummary: String,
  val status: ToolExecutionStatus,
  val detail: String,
  val processedFiles: Int = 0,
  val generatedMappingPaths: List<Pair<MappingArtifactType, String>> = emptyList(),
)

data class ProtectionPlan(
  val projectId: String,
  val profileMode: ProtectionProfileMode,
  val config: ProtectionConfig,
  val detectedLanguages: Set<LanguageFamily>,
  val detectedFrameworks: Set<DetectedFramework>,
  val fileCountsByLanguage: Map<LanguageFamily, Int>,
  val toolAvailabilities: List<ToolAvailability>,
  val compatibilityIssues: List<ProtectionCompatibilityIssue>,
)

data class ProtectionExecutionReport(
  val buildId: Long,
  val projectId: String,
  val profileMode: ProtectionProfileMode,
  val detectedFrameworks: Set<DetectedFramework>,
  val steps: List<ProtectionStepResult>,
  val compatibilityIssues: List<ProtectionCompatibilityIssue>,
  val artifactLeakCheckPassed: Boolean,
  val artifactLeakDetails: String,
  val summaryText: String,
  val createdAtMillis: Long,
)
