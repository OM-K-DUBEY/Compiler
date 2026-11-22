package com.example.compiler

import java.io.File

object ProjectDetector {

  fun detectBuildSystems(projectRoot: File): List<BuildSystemType> {
    if (!projectRoot.exists() || !projectRoot.isDirectory) return emptyList()
    val detected = linkedSetOf<BuildSystemType>()

    val hasGradle =
      File(projectRoot, "build.gradle.kts").exists() ||
        File(projectRoot, "build.gradle").exists() ||
        File(projectRoot, "app/build.gradle.kts").exists() ||
        File(projectRoot, "app/build.gradle").exists()

    val hasManifest =
      File(projectRoot, "app/src/main/AndroidManifest.xml").exists() ||
        File(projectRoot, "src/main/AndroidManifest.xml").exists() ||
        File(projectRoot, "AndroidManifest.xml").exists()

    if (hasGradle && hasManifest) {
      detected += BuildSystemType.GRADLE_ANDROID
    } else if (hasGradle) {
      detected += BuildSystemType.GRADLE_JVM
    }

    if (File(projectRoot, "pubspec.yaml").exists()) {
      detected += BuildSystemType.FLUTTER
    }
    if (File(projectRoot, "index.html").exists()) {
      detected += BuildSystemType.WEB_STATIC
    }

    val files = projectRoot.walkTopDown().maxDepth(4).filter { it.isFile }.toList()
    if (BuildSystemType.GRADLE_ANDROID !in detected && BuildSystemType.GRADLE_JVM !in detected) {
      if (files.any { it.extension.equals("kt", ignoreCase = true) }) {
        detected += BuildSystemType.KOTLIN_STANDALONE
      }
      if (files.any { it.extension.equals("java", ignoreCase = true) }) {
        detected += BuildSystemType.JAVA_STANDALONE
      }
    }

    return detected.toList()
  }
}
