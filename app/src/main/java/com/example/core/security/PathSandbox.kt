package com.example.core.security

import java.io.File
import java.io.IOException

object PathSandbox {
  fun resolveSafe(rootDir: File, relativePath: String): File {
    val cleanRel = relativePath.trim().replace('\\', '/')
    if (cleanRel.isEmpty()) {
      throw SecurityException("Path cannot be empty")
    }
    if (cleanRel.contains('\u0000')) {
      throw SecurityException("Path contains null byte")
    }
    if (cleanRel.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(cleanRel)) {
      throw SecurityException("Absolute paths are not permitted inside workspace: $relativePath")
    }
    val segments = cleanRel.split('/')
    if (segments.any { it == ".." }) {
      throw SecurityException("Path traversal '..' is not permitted: $relativePath")
    }

    val rootCanonical = rootDir.canonicalFile
    val target = File(rootCanonical, cleanRel)
    val targetCanonical = target.canonicalFile

    val rootPrefix = rootCanonical.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
    if (targetCanonical.path != rootCanonical.path && !targetCanonical.path.startsWith(rootPrefix)) {
      throw SecurityException("Resolved path escapes workspace root: $relativePath")
    }
    return targetCanonical
  }

  fun relativePath(rootDir: File, file: File): String {
    val rootPath = rootDir.canonicalFile.path
    val filePath = file.canonicalFile.path
    if (filePath == rootPath) return ""
    val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
    if (!filePath.startsWith(prefix)) {
      throw IOException("File ${file.path} is outside workspace root ${rootDir.path}")
    }
    return filePath.removePrefix(prefix).replace(File.separatorChar, '/')
  }
}
