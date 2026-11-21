package com.example.protection

import java.io.File

object FileTypeDetector {
  private val ignoredDirNames =
    setOf("build", ".git", ".gradle", ".devforge_checkpoints", ".devforge_protection", "node_modules", ".dart_tool")

  fun classifyFile(file: File): LanguageFamily {
    return when (file.extension.lowercase()) {
      "kt", "kts", "java" -> LanguageFamily.JAVA_KOTLIN
      "dart" -> LanguageFamily.DART_FLUTTER
      "js", "jsx", "mjs", "cjs", "ts", "tsx" -> LanguageFamily.JS_TS
      "py", "pyw" -> LanguageFamily.PYTHON
      "c", "cc", "cpp", "cxx", "h", "hh", "hpp" -> LanguageFamily.CPP_NATIVE
      "html", "htm", "css", "json" -> LanguageFamily.WEB_MARKUP
      "xml", "png", "jpg", "jpeg", "webp", "svg", "ttf", "otf", "woff", "woff2" -> LanguageFamily.RESOURCES_MEDIA
      else -> LanguageFamily.UNKNOWN
    }
  }

  fun scanProjectFiles(projectRoot: File): Map<LanguageFamily, List<File>> {
    if (!projectRoot.exists()) return emptyMap()
    val grouped = linkedMapOf<LanguageFamily, MutableList<File>>()
    projectRoot.walkTopDown()
      .onEnter { dir -> dir.name !in ignoredDirNames }
      .filter { it.isFile }
      .forEach { file ->
        val family = classifyFile(file)
        grouped.getOrPut(family) { mutableListOf() }.add(file)
      }
    return grouped
  }
}

object FrameworkDetector {
  fun detectFrameworks(
    projectRoot: File,
    filesByLanguage: Map<LanguageFamily, List<File>> = FileTypeDetector.scanProjectFiles(projectRoot),
  ): Set<DetectedFramework> {
    val found = linkedSetOf<DetectedFramework>()
    val hasGradle = File(projectRoot, "build.gradle.kts").exists() || File(projectRoot, "build.gradle").exists() ||
      File(projectRoot, "app/build.gradle.kts").exists() || File(projectRoot, "app/build.gradle").exists()
    val hasManifest = File(projectRoot, "app/src/main/AndroidManifest.xml").exists() ||
      File(projectRoot, "AndroidManifest.xml").exists()

    if (hasGradle && hasManifest) found += DetectedFramework.ANDROID_GRADLE
    else if (hasGradle) found += DetectedFramework.JVM_GRADLE

    if (File(projectRoot, "pubspec.yaml").exists() || !filesByLanguage[LanguageFamily.DART_FLUTTER].isNullOrEmpty()) {
      found += DetectedFramework.FLUTTER
    }

    val pkgJson = File(projectRoot, "package.json")
    if (pkgJson.exists()) {
      val text = runCatching { pkgJson.readText() }.getOrDefault("")
      if (text.contains("react-native")) {
        found += DetectedFramework.REACT_NATIVE
      } else {
        found += DetectedFramework.WEB_JS_TS
      }
    } else if (!filesByLanguage[LanguageFamily.JS_TS].isNullOrEmpty() || !filesByLanguage[LanguageFamily.WEB_MARKUP].isNullOrEmpty()) {
      found += DetectedFramework.WEB_JS_TS
    }

    if (File(projectRoot, "CMakeLists.txt").exists() || !filesByLanguage[LanguageFamily.CPP_NATIVE].isNullOrEmpty()) {
      found += DetectedFramework.NATIVE_CPP
    }

    if (File(projectRoot, "requirements.txt").exists() || File(projectRoot, "pyproject.toml").exists() ||
      !filesByLanguage[LanguageFamily.PYTHON].isNullOrEmpty()
    ) {
      found += DetectedFramework.PYTHON_APP
    }

    return found
  }
}
