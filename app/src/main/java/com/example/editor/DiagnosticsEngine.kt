package com.example.editor

import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class CodeDiagnostic(
  val filePath: String,
  val line: Int,
  val column: Int,
  val severity: DiagnosticSeverity,
  val message: String,
)

object DiagnosticsEngine {

  fun analyze(filePath: String, content: String): List<CodeDiagnostic> {
    val lang = CodeLanguage.fromFileName(filePath)
    val results = mutableListOf<CodeDiagnostic>()

    when (lang) {
      CodeLanguage.JSON -> analyzeJson(filePath, content, results)
      CodeLanguage.XML -> analyzeXml(filePath, content, results)
      else -> analyzeDelimitersAndLines(filePath, content, lang, results)
    }

    // Scan TODO/FIXME comments across all languages
    content.lines().forEachIndexed { idx, lineText ->
      val todoIdx = lineText.indexOf("TODO").takeIf { it >= 0 } ?: lineText.indexOf("FIXME")
      if (todoIdx >= 0) {
        results +=
          CodeDiagnostic(
            filePath = filePath,
            line = idx + 1,
            column = todoIdx + 1,
            severity = DiagnosticSeverity.INFO,
            message = lineText.substring(todoIdx).trim().take(80),
          )
      }
    }

    return results
  }

  private fun analyzeJson(filePath: String, content: String, out: MutableList<CodeDiagnostic>) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) {
      out += CodeDiagnostic(filePath, 1, 1, DiagnosticSeverity.WARNING, "Empty JSON document")
      return
    }
    try {
      if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
    } catch (e: Exception) {
      val msg = e.message ?: "Invalid JSON syntax"
      val charPos = Regex("""character (\d+)""").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
      val (line, col) = if (charPos != null) offsetToLineCol(content, charPos) else (1 to 1)
      out += CodeDiagnostic(filePath, line, col, DiagnosticSeverity.ERROR, msg)
    }
  }

  private fun analyzeXml(filePath: String, content: String, out: MutableList<CodeDiagnostic>) {
    if (content.isBlank()) {
      out += CodeDiagnostic(filePath, 1, 1, DiagnosticSeverity.WARNING, "Empty XML file")
      return
    }
    try {
      val factory = XmlPullParserFactory.newInstance()
      val parser = factory.newPullParser()
      parser.setInput(StringReader(content))
      while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        parser.next()
      }
    } catch (e: Exception) {
      val line = Regex("""line:(\d+)|line (\d+)""").find(e.message.orEmpty())
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1
      out +=
        CodeDiagnostic(
          filePath = filePath,
          line = line,
          column = 1,
          severity = DiagnosticSeverity.ERROR,
          message = e.message?.lineSequence()?.firstOrNull() ?: "Malformed XML",
        )
    }
  }

  private data class OpenToken(val char: Char, val line: Int, val col: Int)

  private fun analyzeDelimitersAndLines(
    filePath: String,
    content: String,
    lang: CodeLanguage,
    out: MutableList<CodeDiagnostic>,
  ) {
    val stack = ArrayDeque<OpenToken>()
    var line = 1
    var col = 1
    var i = 0
    val n = content.length
    var inLineComment = false
    var inBlockComment = false
    var stringChar: Char? = null

    while (i < n) {
      val c = content[i]
      if (c == '\n') {
        if (stringChar != null && stringChar != '`') {
          out +=
            CodeDiagnostic(
              filePath,
              line,
              col,
              DiagnosticSeverity.ERROR,
              "Unterminated string literal ($stringChar)",
            )
          stringChar = null
        }
        inLineComment = false
        line++
        col = 1
        i++
        continue
      }

      if (inLineComment) {
        i++
        col++
        continue
      }

      if (inBlockComment) {
        if (c == '*' && i + 1 < n && content[i + 1] == '/') {
          inBlockComment = false
          i += 2
          col += 2
        } else {
          i++
          col++
        }
        continue
      }

      if (stringChar != null) {
        if (c == '\\' && i + 1 < n) {
          i += 2
          col += 2
          continue
        }
        if (c == stringChar) {
          stringChar = null
        }
        i++
        col++
        continue
      }

      when {
        c == '/' && i + 1 < n && content[i + 1] == '/' -> {
          inLineComment = true
          i += 2
          col += 2
        }
        c == '/' && i + 1 < n && content[i + 1] == '*' -> {
          inBlockComment = true
          i += 2
          col += 2
        }
        c == '#' && (lang == CodeLanguage.PYTHON || lang == CodeLanguage.YAML) -> {
          inLineComment = true
          i++
          col++
        }
        c == '"' || c == '\'' || (c == '`' && (lang == CodeLanguage.JAVASCRIPT || lang == CodeLanguage.TYPESCRIPT || lang == CodeLanguage.TSX)) -> {
          stringChar = c
          i++
          col++
        }
        c == '(' || c == '{' || c == '[' -> {
          stack.addLast(OpenToken(c, line, col))
          i++
          col++
        }
        c == ')' || c == '}' || c == ']' -> {
          val expectedOpen =
            when (c) {
              ')' -> '('
              '}' -> '{'
              else -> '['
            }
          if (stack.isEmpty()) {
            out += CodeDiagnostic(filePath, line, col, DiagnosticSeverity.ERROR, "Unexpected closing '$c'")
          } else if (stack.last().char != expectedOpen) {
            val top = stack.removeLast()
            out +=
              CodeDiagnostic(
                filePath,
                line,
                col,
                DiagnosticSeverity.ERROR,
                "Mismatched '$c' (expected closing for '${top.char}' opened at line ${top.line})",
              )
          } else {
            stack.removeLast()
          }
          i++
          col++
        }
        else -> {
          i++
          col++
        }
      }
    }

    if (inBlockComment) {
      out += CodeDiagnostic(filePath, line, col, DiagnosticSeverity.ERROR, "Unclosed block comment '/*'")
    }
    while (stack.isNotEmpty()) {
      val unclosed = stack.removeLast()
      out +=
        CodeDiagnostic(
          filePath,
          unclosed.line,
          unclosed.col,
          DiagnosticSeverity.ERROR,
          "Unclosed '${unclosed.char}'",
        )
    }

    if (lang == CodeLanguage.JAVA || lang == CodeLanguage.C || lang == CodeLanguage.CPP) {
      checkMissingSemicolons(filePath, content, out)
    }
  }

  private fun checkMissingSemicolons(
    filePath: String,
    content: String,
    out: MutableList<CodeDiagnostic>,
  ) {
    content.lines().forEachIndexed { idx, raw ->
      val line = raw.trim()
      if (
        line.isNotEmpty() &&
          !line.startsWith("//") &&
          !line.startsWith("/*") &&
          !line.startsWith("*") &&
          !line.startsWith("#") &&
          !line.startsWith("@") &&
          !line.endsWith(";") &&
          !line.endsWith("{") &&
          !line.endsWith("}") &&
          !line.endsWith(",") &&
          !line.endsWith("(") &&
          !line.endsWith(")") &&
          (line.startsWith("return ") || line.startsWith("import ") || line.startsWith("package ") || line.contains(" = "))
      ) {
        out +=
          CodeDiagnostic(
            filePath = filePath,
            line = idx + 1,
            column = raw.length,
            severity = DiagnosticSeverity.WARNING,
            message = "Statement may be missing a trailing ';'",
          )
      }
    }
  }

  private fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
    val safeOffset = offset.coerceIn(0, text.length)
    var line = 1
    var col = 1
    for (i in 0 until safeOffset) {
      if (text[i] == '\n') {
        line++
        col = 1
      } else {
        col++
      }
    }
    return line to col
  }
}
