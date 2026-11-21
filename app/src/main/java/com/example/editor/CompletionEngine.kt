package com.example.editor

data class CompletionItem(
  val label: String,
  val kind: String,
  val insertText: String = label,
)

object CompletionEngine {

  private val declarationRegex =
    Regex("""(?:fun|class|interface|object|val|var|def|function|const|let|struct|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")

  fun suggest(
    content: String,
    cursorOffset: Int,
    language: CodeLanguage,
    extraProjectSymbols: List<String> = emptyList(),
  ): List<CompletionItem> {
    val safeCursor = cursorOffset.coerceIn(0, content.length)
    var start = safeCursor
    while (start > 0 && (content[start - 1].isLetterOrDigit() || content[start - 1] == '_')) {
      start--
    }
    val prefix = content.substring(start, safeCursor)
    if (prefix.length < 2) return emptyList()

    val candidates = linkedMapOf<String, CompletionItem>()

    // 1. Symbols declared in the current file
    declarationRegex.findAll(content).forEach { m ->
      val sym = m.groupValues[1]
      if (sym.startsWith(prefix, ignoreCase = true) && sym != prefix) {
        candidates.putIfAbsent(sym, CompletionItem(sym, "symbol"))
      }
    }

    // 2. Project-wide symbols
    for (sym in extraProjectSymbols) {
      if (sym.startsWith(prefix, ignoreCase = true) && sym != prefix) {
        candidates.putIfAbsent(sym, CompletionItem(sym, "project"))
      }
    }

    // 3. Language keywords & snippets
    for (kw in SyntaxHighlighter.keywordsFor(language)) {
      if (kw.startsWith(prefix, ignoreCase = true) && kw != prefix) {
        candidates.putIfAbsent(kw, CompletionItem(kw, "keyword"))
      }
    }

    return candidates.values.take(12)
  }

  fun extractSymbols(content: String): List<String> =
    declarationRegex.findAll(content).map { it.groupValues[1] }.distinct().toList()

  fun currentWordRange(content: String, cursorOffset: Int): IntRange {
    val safeCursor = cursorOffset.coerceIn(0, content.length)
    var start = safeCursor
    while (start > 0 && (content[start - 1].isLetterOrDigit() || content[start - 1] == '_')) {
      start--
    }
    return start until safeCursor
  }
}
