package com.example.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.SyntaxPaletteDark
import com.example.ui.theme.SyntaxPaletteLight

object SyntaxHighlighter {

  private val commonKeywords =
    setOf(
      "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
      "return", "try", "catch", "finally", "throw", "new", "true", "false", "null",
      "import", "package", "export", "from", "default", "class", "interface", "enum",
      "public", "private", "protected", "static", "final", "const", "void",
    )

  private val kotlinKeywords =
    commonKeywords +
      setOf(
        "fun", "val", "var", "object", "companion", "data", "sealed", "open",
        "override", "suspend", "inline", "when", "is", "as", "in", "by", "init",
        "lateinit", "internal", "abstract", "typealias", "this", "super", "it",
      )

  private val dartKeywords =
    commonKeywords +
      setOf(
        "var", "final", "const", "late", "required", "async", "await", "yield",
        "extends", "implements", "with", "mixin", "factory", "get", "set", "super", "this",
      )

  private val jsTsKeywords =
    commonKeywords +
      setOf(
        "function", "let", "var", "async", "await", "type", "typeof", "keyof",
        "extends", "implements", "readonly", "undefined", "this", "super", "declare",
      )

  private val gradleKeywords =
    kotlinKeywords + setOf("plugins", "dependencies", "implementation", "testImplementation", "android", "repositories", "id", "alias")

  fun keywordsFor(language: CodeLanguage): Set<String> =
    when (language) {
      CodeLanguage.KOTLIN -> kotlinKeywords
      CodeLanguage.GRADLE -> gradleKeywords
      CodeLanguage.JAVA -> commonKeywords + setOf("extends", "implements", "throws", "synchronized", "boolean", "int", "long", "double", "float", "char")
      CodeLanguage.DART -> dartKeywords
      CodeLanguage.TYPESCRIPT, CodeLanguage.TSX, CodeLanguage.JAVASCRIPT -> jsTsKeywords
      else -> emptySet()
    }

  fun highlight(
    text: String,
    language: CodeLanguage,
    isDark: Boolean,
    matchedBracketIndices: Pair<Int, Int>? = null,
  ): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val kwColor = if (isDark) SyntaxPaletteDark.Keyword else SyntaxPaletteLight.Keyword
    val typeColor = if (isDark) SyntaxPaletteDark.TypeName else SyntaxPaletteLight.TypeName
    val strColor = if (isDark) SyntaxPaletteDark.StringLiteral else SyntaxPaletteLight.StringLiteral
    val cmtColor = if (isDark) SyntaxPaletteDark.Comment else SyntaxPaletteLight.Comment
    val numColor = if (isDark) SyntaxPaletteDark.Number else SyntaxPaletteLight.Number
    val annColor = if (isDark) SyntaxPaletteDark.Annotation else SyntaxPaletteLight.Annotation
    val tagColor = if (isDark) SyntaxPaletteDark.Tag else SyntaxPaletteLight.Tag
    val plainColor = if (isDark) SyntaxPaletteDark.Plain else SyntaxPaletteLight.Plain

    val keywords = keywordsFor(language)
    val n = text.length

    return buildAnnotatedString {
      append(text)
      addStyle(SpanStyle(color = plainColor), 0, n)

      if (language == CodeLanguage.XML || language == CodeLanguage.HTML) {
        highlightMarkup(text, tagColor, typeColor, strColor, cmtColor)
      } else {
        var i = 0
        while (i < n) {
          val ch = text[i]
          when {
            // Line comments // or #
            (ch == '/' && i + 1 < n && text[i + 1] == '/') ||
              (ch == '#' && (language == CodeLanguage.YAML || language == CodeLanguage.PLAIN)) -> {
              val end = text.indexOf('\n', i).let { if (it == -1) n else it }
              addStyle(SpanStyle(color = cmtColor, fontStyle = FontStyle.Italic), i, end)
              i = end
            }
            // Block comments /* ... */
            ch == '/' && i + 1 < n && text[i + 1] == '*' -> {
              val endIdx = text.indexOf("*/", i + 2)
              val end = if (endIdx == -1) n else endIdx + 2
              addStyle(SpanStyle(color = cmtColor, fontStyle = FontStyle.Italic), i, end)
              i = end
            }
            // String literals "..." or '...' or `...`
            ch == '"' || ch == '\'' || ch == '`' -> {
              val quote = ch
              var j = i + 1
              while (j < n) {
                if (text[j] == '\\' && j + 1 < n) {
                  j += 2
                  continue
                }
                if (text[j] == quote) {
                  j++
                  break
                }
                if (text[j] == '\n' && quote != '`') break
                j++
              }
              addStyle(SpanStyle(color = strColor), i, j)
              i = j
            }
            // Annotations / Decorators @Name
            ch == '@' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
              var j = i + 1
              while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
              addStyle(SpanStyle(color = annColor), i, j)
              i = j
            }
            // Numbers
            ch.isDigit() -> {
              var j = i + 1
              while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
              addStyle(SpanStyle(color = numColor), i, j)
              i = j
            }
            // Identifiers / Keywords / Types
            ch.isLetter() || ch == '_' -> {
              var j = i + 1
              while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
              val word = text.substring(i, j)
              when {
                word in keywords -> {
                  addStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.Medium), i, j)
                }
                word.first().isUpperCase() && word.any { it.isLowerCase() } -> {
                  addStyle(SpanStyle(color = typeColor), i, j)
                }
              }
              i = j
            }
            else -> i++
          }
        }
      }

      if (matchedBracketIndices != null) {
        val (a, b) = matchedBracketIndices
        val bracketBg = if (isDark) Color(0xFF264F78) else Color(0xFFBAE6FD)
        if (a in 0 until n) addStyle(SpanStyle(background = bracketBg, fontWeight = FontWeight.Bold), a, a + 1)
        if (b in 0 until n) addStyle(SpanStyle(background = bracketBg, fontWeight = FontWeight.Bold), b, b + 1)
      }
    }
  }

  private fun AnnotatedString.Builder.highlightMarkup(
    text: String,
    tagColor: Color,
    attrColor: Color,
    strColor: Color,
    cmtColor: Color,
  ) {
    val n = text.length
    var i = 0
    while (i < n) {
      if (text.startsWith("<!--", i)) {
        val end = text.indexOf("-->", i + 4).let { if (it == -1) n else it + 3 }
        addStyle(SpanStyle(color = cmtColor, fontStyle = FontStyle.Italic), i, end)
        i = end
      } else if (text[i] == '<') {
        val close = text.indexOf('>', i + 1).let { if (it == -1) n else it + 1 }
        var j = i + 1
        if (j < close && (text[j] == '/' || text[j] == '?' || text[j] == '!')) j++
        val nameStart = j
        while (j < close && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
        if (j > nameStart) {
          addStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Medium), i, j)
        }
        while (j < close) {
          val c = text[j]
          if (c == '"' || c == '\'') {
            var q = j + 1
            while (q < close && text[q] != c) q++
            if (q < close) q++
            addStyle(SpanStyle(color = strColor), j, q)
            j = q
          } else if (c.isLetter() || c == '_') {
            val aStart = j
            while (j < close && (text[j].isLetterOrDigit() || text[j] == ':' || text[j] == '-' || text[j] == '_')) j++
            addStyle(SpanStyle(color = attrColor), aStart, j)
          } else {
            j++
          }
        }
        i = close
      } else {
        i++
      }
    }
  }

  fun findMatchingBracket(text: String, cursorOffset: Int): Pair<Int, Int>? {
    if (text.isEmpty()) return null
    val candidates = listOf(cursorOffset - 1, cursorOffset).filter { it in text.indices }
    for (pos in candidates) {
      val ch = text[pos]
      val match =
        when (ch) {
          '(' -> findForward(text, pos, '(', ')')
          '{' -> findForward(text, pos, '{', '}')
          '[' -> findForward(text, pos, '[', ']')
          ')' -> findBackward(text, pos, ')', '(')
          '}' -> findBackward(text, pos, '}', '{')
          ']' -> findBackward(text, pos, ']', '[')
          else -> null
        }
      if (match != null) return pos to match
    }
    return null
  }

  private fun findForward(text: String, start: Int, open: Char, close: Char): Int? {
    var depth = 0
    for (i in start until text.length) {
      when (text[i]) {
        open -> depth++
        close -> {
          depth--
          if (depth == 0) return i
        }
      }
    }
    return null
  }

  private fun findBackward(text: String, start: Int, close: Char, open: Char): Int? {
    var depth = 0
    for (i in start downTo 0) {
      when (text[i]) {
        close -> depth++
        open -> {
          depth--
          if (depth == 0) return i
        }
      }
    }
    return null
  }
}
