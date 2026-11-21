package com.example.compiler

object BuildErrorDiagnosticClassifier {
  private val fileLineRegex = Regex("""(?:file://)?([^\s:]+\.(?:kt|kts|java|dart|ts|tsx|js|xml|cpp|c|h|py)):(\d+)(?::\d+)?""")

  fun classify(logs: String): List<BuildDiagnosticItem> {
    if (logs.isBlank()) return emptyList()
    val items = mutableListOf<BuildDiagnosticItem>()
    val lines = logs.lines()

    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      val lower = trimmed.lowercase()

      val isLikelyFailureLine =
        lower.contains("error") ||
          lower.contains("failed") ||
          lower.contains("exception") ||
          lower.startsWith("e:") ||
          lower.contains("unable to") ||
          lower.contains("could not ") ||
          lower.contains("cannot ") ||
          lower.startsWith("d8:") ||
          lower.contains("@composable") ||
          lower.contains("unresolved") ||
          lower.contains("fatal") ||
          lower.contains("outofmemory")

      if (!isLikelyFailureLine) continue

      val match = fileLineRegex.find(trimmed)
      val filePath = match?.groupValues?.getOrNull(1)
      val lineNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()

      val (category, summary, fix) =
        when {
          lower.contains("outofmemoryerror") || lower.contains("metaspace") || lower.contains("heap space") ->
            Triple(
              BuildErrorCategory.OUT_OF_MEMORY,
              "JVM / Gradle daemon ran out of memory.",
              "Reduce daemon heap in gradle.properties (-Xmx) or close background apps to free device RAM.",
            )
          lower.contains("unknownhostexception") || lower.contains("could not resolve") && lower.contains("connect") ||
            lower.contains("read timed out") || lower.contains("connection refused") ->
            Triple(
              BuildErrorCategory.NETWORK_DEPENDENCY_FAILURE,
              "Failed to download dependencies over the network.",
              "Check internet connectivity and proxy/repository URLs, or enable offline cache if dependencies are already downloaded.",
            )
          lower.contains("sdk location not found") || lower.contains("android_sdk_root") ||
            lower.contains("failed to find target with hash string") || lower.contains("failed to find build tools") ->
            Triple(
              BuildErrorCategory.MISSING_SDK,
              "Required Android SDK platform or build-tools not found.",
              "Install the matching Android SDK platform/build-tools in SDK Manager or configure ANDROID_HOME.",
            )
          lower.contains("java_home") || lower.contains("no java") || lower.contains("unsupported class file major version") ||
            lower.contains("cannot find javac") ->
            Triple(
              BuildErrorCategory.MISSING_JDK,
              "Missing or incompatible JDK installation.",
              "Attach a compatible JDK 17+ toolchain in Settings -> Custom Toolchain PATH.",
            )
          lower.contains("aapt2") || lower.contains("android resource linking failed") || lower.contains("aapt:") ->
            Triple(
              BuildErrorCategory.AAPT2_FAILURE,
              "AAPT2 Android resource compilation or linking failed.",
              "Inspect XML manifests/layouts/values around the reported resource line for invalid attributes or missing IDs.",
            )
          lower.contains("d8") || lower.contains("dex") && (lower.contains("merge") || lower.contains("64k") || lower.contains("duplicate")) ->
            Triple(
              BuildErrorCategory.DEX_FAILURE,
              "DEX / D8 bytecode conversion or dex merging failed.",
              "Check duplicate classes across dependencies or enable multiDexEnabled = true.",
            )
          lower.contains("r8") || lower.contains("proguard") || lower.contains("missing classes detected while running r8") ->
            Triple(
              BuildErrorCategory.R8_FAILURE,
              "R8 shrinking/obfuscation reported missing classes or invalid keep rules.",
              "Add -dontwarn or specific -keep rules in Code Protection -> Keep Rules Editor.",
            )
          lower.contains("signing") || lower.contains("keystore") || lower.contains("v2 signing") || lower.contains("apksigner") ->
            Triple(
              BuildErrorCategory.SIGNING_ERROR,
              "APK / AAB signing failed.",
              "Verify signing keystore generation in Settings -> Release Archive Signing Key.",
            )
          lower.contains("permission denied") || lower.contains("selinux") || lower.contains("exec format error") ||
            lower.contains("no executable") || lower.contains("error=13") || lower.contains("error=2") ->
            Triple(
              BuildErrorCategory.NATIVE_EXEC_FAILURE,
              "Native toolchain binary could not execute on this Android device.",
              "Unrooted Android 10+ restricts executing downloaded ELF binaries (SELinux W^X). Use Termux/chroot PATH or export ZIP.",
            )
          lower.contains("@composable") || lower.contains("compose") && lower.contains("compiler") ->
            Triple(
              BuildErrorCategory.COMPOSE_ERROR,
              "Jetpack Compose compiler error.",
              "Verify @Composable invocation context and Compose compiler plugin alignment.",
            )
          lower.contains("could not find ") || lower.contains("could not resolve all files") || lower.contains("dependency") ->
            Triple(
              BuildErrorCategory.MISSING_DEPENDENCY,
              "Unresolved library or plugin dependency.",
              "Check repository declarations (google(), mavenCentral()) and artifact coordinates/versions.",
            )
          lower.contains("gradle sync") || lower.contains("settings.gradle") || lower.contains("build.gradle") && lower.contains("could not") ->
            Triple(
              BuildErrorCategory.GRADLE_SYNC,
              "Gradle project configuration / sync error.",
              "Review settings.gradle.kts, build.gradle.kts, and plugin versions.",
            )
          lower.startsWith("e:") || lower.contains(".kt:") || lower.contains("unresolved reference") || lower.contains("type mismatch") ->
            Triple(
              BuildErrorCategory.KOTLIN_COMPILER,
              "Kotlin compilation error.",
              "Inspect the reported Kotlin file and line number to resolve syntax or type errors.",
            )
          else ->
            Triple(
              BuildErrorCategory.GENERAL_ERROR,
              "Build error reported.",
              "Review the raw build log excerpt below.",
            )
        }

      items +=
        BuildDiagnosticItem(
          category = category,
          summary = summary,
          rawExcerpt = trimmed.take(360),
          suggestedFix = fix,
          filePath = filePath,
          line = lineNum,
        )
    }

    return items.distinctBy { it.category to it.rawExcerpt }.take(40)
  }
}
