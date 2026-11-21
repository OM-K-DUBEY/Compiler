package com.example.editor

import java.io.File

data class InFileMatch(
  val startOffset: Int,
  val endOffset: Int,
  val line: Int,
)

data class ProjectSearchHit(
  val relativePath: String,
  val line: Int,
  val column: Int,
  val preview: String,
)

object SearchEngine {

  fun findInText(
    text: String,
    query: String,
    caseSensitive: Boolean = false,
    useRegex: Boolean = false,
  ): List<InFileMatch> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<InFileMatch>()
    val regex =
      try {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (useRegex) Regex(query, options) else Regex(Regex.escape(query), options)
      } catch (_: Exception) {
        return emptyList()
      }

    regex.findAll(text).take(500).forEach { result ->
      val line = text.substring(0, result.range.first).count { it == '\n' } + 1
      matches += InFileMatch(result.range.first, result.range.last + 1, line)
    }
    return matches
  }

  fun replaceAllInText(
    text: String,
    query: String,
    replacement: String,
    caseSensitive: Boolean = false,
    useRegex: Boolean = false,
  ): Pair<String, Int> {
    if (query.isEmpty()) return text to 0
    val matches = findInText(text, query, caseSensitive, useRegex)
    if (matches.isEmpty()) return text to 0
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val regex = if (useRegex) Regex(query, options) else Regex(Regex.escape(query), options)
    val replaced =
      if (useRegex) {
        regex.replace(text, replacement)
      } else {
        regex.replace(text) { Regex.escapeReplacement(replacement) }
      }
    return replaced to matches.size
  }

  fun findInProject(
    projectRoot: File,
    relativePaths: List<String>,
    query: String,
    caseSensitive: Boolean = false,
    maxHits: Int = 200,
  ): List<ProjectSearchHit> {
    if (query.isBlank() || !projectRoot.exists()) return emptyList()
    val hits = mutableListOf<ProjectSearchHit>()
    for (rel in relativePaths) {
      val file = File(projectRoot, rel)
      if (!file.exists() || !file.isFile || file.length() > 1024 * 1024) continue
      val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
      lines.forEachIndexed { idx, lineText ->
        if (hits.size >= maxHits) return hits
        val col = lineText.indexOf(query, ignoreCase = !caseSensitive)
        if (col >= 0) {
          hits +=
            ProjectSearchHit(
              relativePath = rel,
              line = idx + 1,
              column = col + 1,
              preview = lineText.trim().take(120),
            )
        }
      }
    }
    return hits
  }
}
