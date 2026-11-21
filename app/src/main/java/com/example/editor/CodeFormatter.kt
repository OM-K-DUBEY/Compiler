package com.example.editor

import org.json.JSONArray
import org.json.JSONObject

object CodeFormatter {

  fun format(content: String, language: CodeLanguage, tabSize: Int = 4): String {
    if (language == CodeLanguage.JSON) {
      val trimmed = content.trim()
      return try {
        when {
          trimmed.startsWith("{") -> JSONObject(trimmed).toString(tabSize)
          trimmed.startsWith("[") -> JSONArray(trimmed).toString(tabSize)
          else -> content
        }
      } catch (_: Exception) {
        content
      }
    }

    if (!language.usesBraces) {
      return content.lines().joinToString("\n") { it.trimEnd() }
    }

    val indentUnit = " ".repeat(tabSize.coerceIn(2, 8))
    var depth = 0
    val out = StringBuilder()
    val lines = content.lines()
    lines.forEachIndexed { idx, raw ->
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) {
        if (idx < lines.lastIndex) out.append('\n')
        return@forEachIndexed
      }
      val leadingClosers = trimmed.takeWhile { it == '}' || it == ']' || it == ')' }.length
      val effectiveDepth = (depth - leadingClosers).coerceAtLeast(0)
      out.append(indentUnit.repeat(effectiveDepth)).append(trimmed)
      if (idx < lines.lastIndex) out.append('\n')

      var delta = 0
      var inQuote: Char? = null
      var i = 0
      while (i < trimmed.length) {
        val c = trimmed[i]
        if (inQuote != null) {
          if (c == '\\') {
            i += 2
            continue
          }
          if (c == inQuote) inQuote = null
        } else {
          if (c == '/' && i + 1 < trimmed.length && trimmed[i + 1] == '/') break
          when (c) {
            '"', '\'' -> inQuote = c
            '{', '[' -> delta++
            '}', ']' -> delta--
          }
        }
        i++
      }
      depth = (depth + delta).coerceAtLeast(0)
    }
    return out.toString()
  }

  fun computeAutoIndentForNewline(textBeforeCursor: String, tabSize: Int = 4): String {
    val lastLine = textBeforeCursor.substringAfterLast('\n')
    val baseIndent = lastLine.takeWhile { it == ' ' || it == '\t' }
    val trimmed = lastLine.trimEnd()
    val shouldIncrease = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith(":")
    return if (shouldIncrease) {
      baseIndent + " ".repeat(tabSize.coerceIn(2, 8))
    } else {
      baseIndent
    }
  }
}
