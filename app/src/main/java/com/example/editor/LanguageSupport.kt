package com.example.editor

enum class CodeLanguage(
  val displayName: String,
  val commentPrefix: String,
  val usesBraces: Boolean,
) {
  KOTLIN("Kotlin", "//", true),
  JAVA("Java", "//", true),
  DART("Dart", "//", true),
  TYPESCRIPT("TypeScript", "//", true),
  TSX("TSX", "//", true),
  JAVASCRIPT("JavaScript", "//", true),
  HTML("HTML", "<!--", false),
  CSS("CSS", "/*", true),
  XML("XML", "<!--", false),
  JSON("JSON", "//", true),
  YAML("YAML", "#", false),
  GRADLE("Gradle", "//", true),
  MARKDOWN("Markdown", "//", false),
  PLAIN("Plain Text", "#", false);

  companion object {
    fun fromFileName(fileName: String): CodeLanguage {
      val lower = fileName.lowercase()
      if (lower.endsWith(".gradle.kts") || lower.endsWith(".gradle")) return GRADLE
      val ext = lower.substringAfterLast('.', "")
      return when (ext) {
        "kt", "kts" -> KOTLIN
        "java" -> JAVA
        "dart" -> DART
        "ts" -> TYPESCRIPT
        "tsx" -> TSX
        "js", "jsx", "mjs", "cjs" -> JAVASCRIPT
        "html", "htm" -> HTML
        "css", "scss" -> CSS
        "xml" -> XML
        "json" -> JSON
        "yaml", "yml" -> YAML
        "md", "markdown" -> MARKDOWN
        else -> PLAIN
      }
    }
  }
}
